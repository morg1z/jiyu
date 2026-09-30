package com.haise.jiyu.source

import com.haise.jiyu.download.retryAfterWaitMs
import com.haise.jiyu.source.interceptor.RateLimitInterceptor
import com.haise.jiyu.source.interceptor.SlowdownInterceptor
import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SourceSlowdownTest {

    private var now = 1_000_000L
    private val slowdown = SourceSlowdown(null).apply { nowMs = { now } }

    @Test
    fun `a host without a rate limit is never slowed`() {
        assertEquals(0L, slowdown.reserve("site.test"))
        assertEquals(0L, slowdown.reserve("site.test"))
    }

    @Test
    fun `after a 429 concurrent requests are spaced by the interval`() {
        slowdown.noteRateLimited("site.test")
        assertEquals(0L, slowdown.reserve("site.test"))
        assertEquals(SourceSlowdown.INTERVAL_MS, slowdown.reserve("site.test"))
        assertEquals(2 * SourceSlowdown.INTERVAL_MS, slowdown.reserve("site.test"))
        // Jiný host není dotčen.
        assertEquals(0L, slowdown.reserve("other.test"))
    }

    @Test
    fun `time passing frees the slots and the slowdown expires`() {
        slowdown.noteRateLimited("site.test")
        slowdown.reserve("site.test")
        slowdown.reserve("site.test") // další volný slot je za INTERVAL_MS
        now += 5 * SourceSlowdown.INTERVAL_MS
        assertEquals("sloty mezitím uplynuly", 0L, slowdown.reserve("site.test"))
        now += SourceSlowdown.SLOWDOWN_WINDOW_MS
        assertEquals("zpomalení vypršelo", 0L, slowdown.reserve("site.test"))
        assertEquals(0L, slowdown.reserve("site.test"))
    }

    @Test
    fun `repeated 429 inside the window escalates the interval`() {
        // Audit comicknew: fixní 1,6 s odstup se nikdy nedostal pod serverový limit,
        // takže každá nová 429 jen re-armovala okno. Interval se má zdvojnásobovat.
        slowdown.noteRateLimited("site.test")
        assertEquals(SourceSlowdown.INTERVAL_MS, slowdown.currentIntervalMs("site.test"))
        slowdown.noteRateLimited("site.test")
        assertEquals(2 * SourceSlowdown.INTERVAL_MS, slowdown.currentIntervalMs("site.test"))
        slowdown.noteRateLimited("site.test")
        assertEquals(4 * SourceSlowdown.INTERVAL_MS, slowdown.currentIntervalMs("site.test"))
    }

    @Test
    fun `interval escalation is capped and resets after the window expires`() {
        repeat(20) { slowdown.noteRateLimited("site.test") }
        assertEquals(SourceSlowdown.MAX_INTERVAL_MS, slowdown.currentIntervalMs("site.test"))
        now += SourceSlowdown.SLOWDOWN_WINDOW_MS
        assertEquals("po expiraci okna bez 429 zpátky naplno", 0L, slowdown.reserve("site.test"))
        assertEquals("interval reset", 0L, slowdown.currentIntervalMs("site.test"))
        // Nová 429 po expiraci začíná opět od základního intervalu.
        slowdown.noteRateLimited("site.test")
        assertEquals(SourceSlowdown.INTERVAL_MS, slowdown.currentIntervalMs("site.test"))
    }

    @Test
    fun `learned interval persists into a new instance`() {
        // Audit comicknew: bez perzistence se po restartu stejný burst naboural do 429
        // znovu. Naučený (eskalovaný) interval se ukládá do filesDir a hydratuje se.
        val dir = java.nio.file.Files.createTempDirectory("slowdown").toFile()
        val ctx = mockk<android.content.Context>()
        every { ctx.filesDir } returns dir
        val s1 = SourceSlowdown(ctx)
        repeat(3) { s1.noteRateLimited("site.test") } // 1600 -> 3200 -> 6400
        val s2 = SourceSlowdown(ctx)
        assertEquals(6_400L, s2.currentIntervalMs("site.test"))
        // Hydratovaný host je zpomalený - sloty drží naučený odstup.
        assertEquals(0L, s2.reserve("site.test")) // první slot volný
        assertEquals(6_400L, s2.reserve("site.test"))
        assertEquals(0L, s2.reserve("site.test", priority = true))
    }

    @Test
    fun `persisted state older than ttl is dropped`() {
        val dir = java.nio.file.Files.createTempDirectory("slowdown").toFile()
        val stale = "{\"t\":${System.currentTimeMillis() - SourceSlowdown.PERSIST_TTL_MS - 1000}," +
            "\"h\":{\"site.test\":6400}}"
        java.io.File(dir, SourceSlowdown.PERSIST_FILE).writeText(stale)
        val ctx = mockk<android.content.Context>()
        every { ctx.filesDir } returns dir
        val s = SourceSlowdown(ctx)
        assertEquals(0L, s.currentIntervalMs("site.test"))
        assertEquals(0L, s.reserve("site.test"))
    }

    @Test
    fun `retry-after is a floor for the interval`() {
        slowdown.noteRateLimited("site.test", retryAfterMs = 8_000L)
        assertEquals(8_000L, slowdown.currentIntervalMs("site.test"))
        // Kratší Retry-Than aktuální interval ho nesníží.
        slowdown.noteRateLimited("site.test", retryAfterMs = 1_000L)
        assertEquals(16_000L.coerceAtMost(SourceSlowdown.MAX_INTERVAL_MS), slowdown.currentIntervalMs("site.test"))
    }

    // ── interceptory ─────────────────────────────────────────────────────────

    private fun ok(request: Request, code: Int = 200, vararg headers: Pair<String, String>) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .body("".toResponseBody()).build()

    private fun chain(code: Int = 200, canceled: Boolean = false, priority: Boolean = false, vararg headers: Pair<String, String>): Interceptor.Chain {
        val request = Request.Builder().url("https://site.test/x")
            .apply { if (priority) header(SlowdownInterceptor.HEADER_PRIORITY, "1") }
            .build()
        val call = mockk<Call>()
        every { call.isCanceled() } returns canceled
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.call() } returns call
        every { chain.proceed(any()) } answers { ok(request, code, *headers) }
        return chain
    }

    @Test
    fun `the interceptor waits out the reserved delay in small steps`() {
        slowdown.noteRateLimited("site.test")
        slowdown.reserve("site.test") // první slot je volný, další počká
        val slept = mutableListOf<Long>()
        SlowdownInterceptor(slowdown) { slept += it }.intercept(chain()).close()
        assertEquals(SourceSlowdown.INTERVAL_MS, slept.sum())
        assertEquals(true, slept.all { it <= 100 })
    }

    @Test
    fun `no waiting for a healthy host and a cancelled call stops waiting`() {
        val slept = mutableListOf<Long>()
        SlowdownInterceptor(slowdown) { slept += it }.intercept(chain()).close()
        assertEquals(emptyList<Long>(), slept)

        slowdown.noteRateLimited("site.test")
        slowdown.reserve("site.test")
        assertThrows(IOException::class.java) {
            SlowdownInterceptor(slowdown) { slept += it }.intercept(chain(canceled = true))
        }
    }

    @Test
    fun `a 429 notes the host and throws with the retry-after`() {
        val e = assertThrows(SourceRateLimitedException::class.java) {
            RateLimitInterceptor(slowdown).intercept(chain(429, headers = arrayOf("Retry-After" to "30")))
        }
        assertEquals(30_000L, e.retryAfterMs)
        // Retry-After:30 posune celou frontu (cap MAX_QUEUE_MS=30s) i interval (cap 12,8s).
        assertEquals("fronta posunutá za Retry-After", 30_000L, slowdown.reserve("site.test"))
        // Další slot (30 s + 12,8 s) je už za horizontem fronty - pustí se hned.
        assertEquals("za horizontem fronty = bez čekání", 0L, slowdown.reserve("site.test"))
    }

    @Test
    fun `other statuses pass through untouched`() {
        RateLimitInterceptor(slowdown).intercept(chain(200)).use { assertEquals(200, it.code) }
        assertEquals(0L, slowdown.reserve("site.test"))
    }

    @Test
    fun `retry-after wait is capped`() {
        assertEquals(0L, retryAfterWaitMs(0))
        assertEquals(0L, retryAfterWaitMs(-5))
        assertEquals(30_000L, retryAfterWaitMs(30_000))
        assertEquals(5L * 60 * 1000, retryAfterWaitMs(2L * 60 * 60 * 1000))
    }

    // ── priority (interaktivní requesty přeskakují frontu) ───────────────────

    @Test
    fun `priority request skips the reserved queue but keeps the frontier spaced`() {
        slowdown.noteRateLimited("site.test")
        slowdown.reserve("site.test") // slot t=0
        assertEquals(SourceSlowdown.INTERVAL_MS, slowdown.reserve("site.test")) // slot t=1
        // Prioritní jde hned, i když normální sloty už jsou rezervované dopředu.
        assertEquals(0L, slowdown.reserve("site.test", priority = true))
        assertEquals(0L, slowdown.reserve("site.test", priority = true))
        // ...a normální fronta za nimi pokračuje od narezervované hranice (t=2*INTERVAL).
        assertEquals(2 * SourceSlowdown.INTERVAL_MS, slowdown.reserve("site.test"))
    }

    @Test
    fun `priority request on a healthy host is also immediate`() {
        assertEquals(0L, slowdown.reserve("site.test", priority = true))
    }

    @Test
    fun `the interceptor does not wait for a request marked priority`() {
        slowdown.noteRateLimited("site.test")
        slowdown.reserve("site.test") // první slot volný, fronta existuje
        val slept = mutableListOf<Long>()
        SlowdownInterceptor(slowdown) { slept += it }.intercept(chain(priority = true)).close()
        assertEquals(emptyList<Long>(), slept)
    }

    @Test
    fun `the interceptor strips the priority header before sending`() {
        slowdown.noteRateLimited("site.test")
        var sentRequest: Request? = null
        val request = Request.Builder().url("https://site.test/x")
            .header(SlowdownInterceptor.HEADER_PRIORITY, "1")
            .build()
        val call = mockk<Call>()
        every { call.isCanceled() } returns false
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.call() } returns call
        every { chain.proceed(any()) } answers {
            sentRequest = firstArg()
            ok(firstArg())
        }
        SlowdownInterceptor(slowdown).intercept(chain).close()
        assertEquals(null, sentRequest?.header(SlowdownInterceptor.HEADER_PRIORITY))
    }
}

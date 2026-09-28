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
    private val slowdown = SourceSlowdown().apply { nowMs = { now } }

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
        slowdown.reserve("site.test") // první slot volný
        assertEquals("host je teď zpomalený", SourceSlowdown.INTERVAL_MS, slowdown.reserve("site.test"))
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

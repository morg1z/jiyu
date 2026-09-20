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

    private fun chain(code: Int = 200, canceled: Boolean = false, vararg headers: Pair<String, String>): Interceptor.Chain {
        val request = Request.Builder().url("https://site.test/x").build()
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
}

package com.haise.jiyu.source.interceptor

import com.haise.jiyu.util.NoNetworkException
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RetryInterceptorTest {

    private var clock = 0L
    private val sleeps = mutableListOf<Long>()

    private fun interceptor(maxAttempts: Int = 2, budget: Long = 25_000) = RetryInterceptor(
        maxAttempts = maxAttempts,
        totalBudgetMs = budget,
        sleep = { sleeps += it; clock += it },
        nowMs = { clock },
        jitterMs = { 0L },
    )

    private fun ok(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
        .message("OK").body("".toResponseBody()).build()

    /** Falešný řetěz: každé volání `proceed` spotřebuje jednu položku - buď výjimku (s dobou trvání), nebo úspěch. */
    private class Step(val failure: IOException?, val durationMs: Long = 0)

    private fun run(
        interceptor: RetryInterceptor,
        method: String = "GET",
        canceled: Boolean = false,
        vararg steps: Step,
    ): Pair<Result<Response>, Int> {
        val request = Request.Builder().url("https://site.test/x")
            .method(method, if (method == "POST") "".toRequestBody() else null).build()
        val call = mockk<Call>()
        every { call.isCanceled() } returns canceled
        var calls = 0
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.call() } returns call
        every { chain.proceed(any()) } answers {
            val step = steps[calls++]
            clock += step.durationMs
            step.failure?.let { throw it }
            ok(request)
        }
        return runCatching { interceptor.intercept(chain) } to calls
    }

    private fun String.toRequestBody() = okhttp3.RequestBody.create(null, this)

    @Test
    fun `a dropped connection is retried once and then succeeds`() {
        val (result, calls) = run(interceptor(), steps = arrayOf(Step(ConnectException("refused")), Step(null)))
        assertTrue(result.isSuccess)
        assertEquals(2, calls)
        assertEquals(listOf(400L), sleeps)
    }

    @Test
    fun `it gives up after the attempt limit and rethrows the last error`() {
        val (result, calls) = run(interceptor(), steps = arrayOf(Step(ConnectException("a")), Step(ConnectException("b"))))
        assertEquals(2, calls)
        assertEquals("b", result.exceptionOrNull()?.message)
    }

    @Test
    fun `only GET and HEAD are retried`() {
        val (result, calls) = run(interceptor(), method = "POST", steps = arrayOf(Step(ConnectException("x")), Step(null)))
        assertEquals(1, calls)
        assertTrue(result.isFailure)
        val (head, headCalls) = run(interceptor(), method = "HEAD", steps = arrayOf(Step(ConnectException("x")), Step(null)))
        assertEquals(2, headCalls)
        assertTrue(head.isSuccess)
    }

    @Test
    fun `dns and tls failures are not retried`() {
        assertEquals(1, run(interceptor(), steps = arrayOf(Step(UnknownHostException("h")), Step(null))).second)
        assertEquals(1, run(interceptor(), steps = arrayOf(Step(SSLHandshakeException("tls")), Step(null))).second)
    }

    @Test
    fun `typed non retryable errors are not retried`() {
        assertEquals(1, run(interceptor(), steps = arrayOf(Step(NoNetworkException()), Step(null))).second)
    }

    @Test
    fun `a fast timeout is retried but a long read timeout is not`() {
        assertEquals(2, run(interceptor(), steps = arrayOf(Step(SocketTimeoutException("connect"), durationMs = 15_000), Step(null))).second)
        assertEquals(1, run(interceptor(), steps = arrayOf(Step(SocketTimeoutException("read"), durationMs = 30_000), Step(null))).second)
    }

    @Test
    fun `a cancelled call is not retried`() {
        assertEquals(1, run(interceptor(), canceled = true, steps = arrayOf(Step(ConnectException("x")), Step(null))).second)
    }

    @Test
    fun `the time budget stops further attempts`() {
        val (result, calls) = run(interceptor(maxAttempts = 3, budget = 10_000), steps = arrayOf(Step(ConnectException("slow"), durationMs = 12_000), Step(null)))
        assertEquals(1, calls)
        assertThrows(ConnectException::class.java) { result.getOrThrow() }
    }
}

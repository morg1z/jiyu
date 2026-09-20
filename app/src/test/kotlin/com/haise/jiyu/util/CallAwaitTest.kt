package com.haise.jiyu.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CallAwaitTest {

    @Test
    fun `returns the handler result for a normal response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("hello"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())

            val body = withContext(Dispatchers.IO) { call.executeCancellable { it.body!!.string() } }

            assertEquals("hello", body)
        }
    }

    @Test
    fun `cancelling the coroutine cancels the HTTP call instead of waiting for the timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url(server.url("/")).build())

            val job = async(Dispatchers.IO) { call.executeCancellable { it.body!!.string() } }
            server.takeRequest(5, TimeUnit.SECONDS)
            val started = System.nanoTime()
            job.cancel()
            try {
                job.await()
            } catch (_: CancellationException) {
            }

            assertTrue(call.isCanceled())
            assertTrue("zrušení nemá čekat na read timeout", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
        }
    }
}

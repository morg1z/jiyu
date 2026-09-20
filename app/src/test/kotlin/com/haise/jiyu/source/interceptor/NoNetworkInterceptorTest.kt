package com.haise.jiyu.source.interceptor

import com.haise.jiyu.util.NoNetworkException
import com.haise.jiyu.util.toFriendlyMessage
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoNetworkInterceptorTest {

    private var reached = 0

    private fun client(online: () -> Boolean) = OkHttpClient.Builder()
        .addInterceptor(NoNetworkInterceptor(online))
        .addInterceptor(Interceptor { chain ->
            reached++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        })
        .build()

    @Test
    fun `offline fails immediately without reaching the network`() {
        val c = client { false }
        assertThrows(NoNetworkException::class.java) {
            c.newCall(Request.Builder().url("https://site.test/").build()).execute()
        }
        assertEquals(0, reached)
    }

    @Test
    fun `online passes through and connectivity is re-evaluated per request`() {
        var online = false
        val c = client { online }
        assertThrows(NoNetworkException::class.java) { c.newCall(Request.Builder().url("https://site.test/").build()).execute() }
        online = true
        c.newCall(Request.Builder().url("https://site.test/").build()).execute().close()
        assertEquals(1, reached)
    }

    @Test
    fun `friendly message for the offline error`() {
        assertEquals("Bez připojení k internetu", NoNetworkException().toFriendlyMessage())
    }
}

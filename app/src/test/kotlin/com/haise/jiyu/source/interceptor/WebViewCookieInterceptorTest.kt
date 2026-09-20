package com.haise.jiyu.source.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewCookieInterceptorTest {

    private class FakeStore(var cookies: String? = null) : SharedCookieStore {
        val stored = mutableListOf<Pair<String, String>>()
        override fun cookiesFor(url: String) = cookies
        override fun store(url: String, setCookieHeader: String) { stored += url to setCookieHeader }
    }

    private val seen = mutableListOf<Request>()
    private var responseCookies: List<String> = emptyList()

    private fun client(store: SharedCookieStore, maxHeader: Int = 4096) = OkHttpClient.Builder()
        .addInterceptor(WebViewCookieInterceptor(store, maxHeader))
        .addInterceptor(Interceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .apply { responseCookies.forEach { addHeader("Set-Cookie", it) } }
                .body("".toResponseBody()).build()
        })
        .build()

    private fun call(c: OkHttpClient, cookie: String? = null) {
        val req = Request.Builder().url("https://site.test/x").apply { cookie?.let { header("Cookie", it) } }.build()
        c.newCall(req).execute().close()
    }

    @Test
    fun `webview cookies are added to a request that has none`() {
        call(client(FakeStore("cf_clearance=abc; lang=cs")))
        assertEquals("cf_clearance=abc; lang=cs", seen.single().header("Cookie"))
    }

    @Test
    fun `cookies set explicitly on the request win over shared ones`() {
        call(client(FakeStore("session=web; lang=cs")), cookie = "session=mine")
        assertEquals("session=mine; lang=cs", seen.single().header("Cookie"))
    }

    @Test
    fun `no shared cookies leaves the request untouched`() {
        call(client(FakeStore(null)), cookie = "a=b")
        assertEquals("a=b", seen.single().header("Cookie"))
        seen.clear()
        call(client(FakeStore(null)))
        assertNull(seen.single().header("Cookie"))
    }

    @Test
    fun `set-cookie headers of the response are stored back`() {
        val store = FakeStore()
        responseCookies = listOf("a=1; Path=/", "b=2; HttpOnly")
        call(client(store))
        assertEquals(listOf("https://site.test/x" to "a=1; Path=/", "https://site.test/x" to "b=2; HttpOnly"), store.stored)
    }

    @Test
    fun `an oversized cookie header is not sent`() {
        call(client(FakeStore("x=" + "y".repeat(200)), maxHeader = 50))
        assertNull(seen.single().header("Cookie"))
    }

    @Test
    fun `merge keeps existing cookies first and skips duplicates`() {
        assertEquals("a=1; b=2", mergeKeepingExisting("a=1", "a=9; b=2"))
        assertEquals("a=9", mergeKeepingExisting(null, "a=9"))
        assertEquals("a=1", mergeKeepingExisting("a=1", "a=2"))
    }
}

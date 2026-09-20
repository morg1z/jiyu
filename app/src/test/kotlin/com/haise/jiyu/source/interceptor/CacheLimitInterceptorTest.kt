package com.haise.jiyu.source.interceptor

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheLimitInterceptorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .cache(Cache(tmp.newFolder("http"), 5L * 1024 * 1024))
            .addNetworkInterceptor(CacheLimitInterceptor(maxAgeSeconds = 600))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(path: String = "/") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `a long max-age is shortened`() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=86400").setBody("a"))
        get().use { assertEquals("a", it.body!!.string()) }
        // Druhé volání se obslouží z cache (max-age nevypršel) - server se znovu neptá.
        get().use { assertEquals("a", it.body!!.string()) }
        assertEquals(1, server.requestCount)
        // Upravená hlavička je ta uložená v cache.
        get().use { assertEquals("max-age=600", it.cacheResponse?.header("Cache-Control")) }
    }

    @Test
    fun `no-store is left alone`() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "no-store").setBody("x"))
        server.enqueue(MockResponse().setHeader("Cache-Control", "no-store").setBody("y"))
        get().use { it.body!!.string() }
        get().use { assertEquals("y", it.body!!.string()) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an etag without cache-control is always revalidated instead of served stale`() {
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("page"))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""))
        get().use { assertEquals("page", it.body!!.string()) }
        get().use { assertEquals("page", it.body!!.string()) }
        assertEquals("obě volání jdou na server (ověření)", 2, server.requestCount)
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a response without cache headers and validators is not cached`() {
        server.enqueue(MockResponse().setBody("1"))
        server.enqueue(MockResponse().setBody("2"))
        get().use { it.body!!.string() }
        get().use {
            assertEquals("2", it.body!!.string())
            assertNull(it.cacheResponse)
        }
        assertEquals(2, server.requestCount)
    }
}

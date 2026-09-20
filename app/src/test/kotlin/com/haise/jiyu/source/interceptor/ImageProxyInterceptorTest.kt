package com.haise.jiyu.source.interceptor

import com.haise.jiyu.source.SourceRateLimitedException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageProxyInterceptorTest {

    private val config = ImageProxyConfig()
    private val blocked = mutableSetOf<String>()
    private val seen = mutableListOf<Request>()

    /** Konec řetězce: [handler] rozhodne odpověď podle požadavku (proxy vs. původní adresa). */
    private fun client(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ImageProxyInterceptor(config, blocked))
        .addInterceptor(Interceptor { chain -> seen += chain.request(); handler(chain.request()) })
        .build()

    private fun response(request: Request, code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body("img".toResponseBody()).build()

    private fun get(c: OkHttpClient, url: String = "https://cdn.site.test/p/1.jpg", original: Boolean = false, method: String = "GET") {
        val b = Request.Builder().url(url)
        if (original) b.header(ImageProxyInterceptor.HEADER_ORIGINAL, "1")
        if (method == "HEAD") b.head()
        c.newCall(b.build()).execute().close()
    }

    @Test
    fun `when disabled the request goes straight to the source`() {
        get(client { response(it) })
        assertEquals(listOf("https://cdn.site.test/p/1.jpg"), seen.map { it.url.toString() })
    }

    @Test
    fun `when enabled the image is fetched through the proxy as webp`() {
        config.enabled = true
        get(client { response(it) })
        val proxied = seen.single().url
        assertEquals("wsrv.nl", proxied.host)
        assertEquals("https://cdn.site.test/p/1.jpg", proxied.queryParameter("url"))
        assertEquals("webp", proxied.queryParameter("output"))
        assertEquals("80", proxied.queryParameter("q"))
        assertNull("bez Refereru zdroje", seen.single().header("Referer"))
    }

    @Test
    fun `originals are never proxied and the marker header is removed`() {
        config.enabled = true
        get(client { response(it) }, original = true)
        assertEquals("cdn.site.test", seen.single().url.host)
        assertNull(seen.single().header(ImageProxyInterceptor.HEADER_ORIGINAL))
    }

    @Test
    fun `a failing proxy falls back to the direct request and the host is skipped afterwards`() {
        config.enabled = true
        val c = client { if (it.url.host == "wsrv.nl") response(it, 502) else response(it) }
        get(c)
        assertEquals(listOf("wsrv.nl", "cdn.site.test"), seen.map { it.url.host })
        assertTrue("cdn.site.test" in blocked)

        seen.clear()
        get(c)
        assertEquals("host už se zkouší jen přímo", listOf("cdn.site.test"), seen.map { it.url.host })
    }

    @Test
    fun `proxy exceptions including rate limits also fall back`() {
        config.enabled = true
        get(client { if (it.url.host == "wsrv.nl") throw IOException("down") else response(it) })
        assertEquals(listOf("wsrv.nl", "cdn.site.test"), seen.map { it.url.host })

        seen.clear(); blocked.clear()
        get(client { if (it.url.host == "wsrv.nl") throw SourceRateLimitedException(1000) else response(it) })
        assertEquals(listOf("wsrv.nl", "cdn.site.test"), seen.map { it.url.host })
    }

    @Test
    fun `a broken direct request is not blamed on the proxy`() {
        config.enabled = true
        val c = client { response(it, 502) }
        get(c)
        assertTrue("když selže i přímý požadavek, host se nevyřazuje", blocked.isEmpty())
    }

    @Test
    fun `non image requests and local hosts are left alone`() {
        config.enabled = true
        get(client { response(it) }, method = "HEAD")
        get(client { response(it) }, url = "http://192.168.1.10/p.jpg")
        get(client { response(it) }, url = "https://wsrv.nl/?url=x")
        assertEquals(listOf("cdn.site.test", "192.168.1.10", "wsrv.nl"), seen.map { it.url.host })
    }
}

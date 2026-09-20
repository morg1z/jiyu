package com.haise.jiyu.source

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.util.ErrorAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.net.ConnectException
import java.net.UnknownHostException

class MirrorProbeTest {

    /** Klient, který na HEAD odpoví, jako by ho server přesměroval na [finalUrl] (nebo vrátí [code]/vyhodí chybu). */
    private fun client(finalUrl: String?, code: Int = 200, fail: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (fail) throw IOException("dead")
            val req = chain.request()
            val landed = Request.Builder().url((finalUrl ?: req.url.toString()).toHttpUrl()).build()
            Response.Builder().request(landed).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("".toResponseBody()).build()
        }).build()

    @Test
    fun `redirect to the same brand is applied automatically`() = runBlocking {
        val c = MirrorProbe(client("https://www.site.net/")).detect("src", "https://site.com/")
        assertEquals(MirrorCandidate("src", "site.net", autoApply = true), c)
    }

    @Test
    fun `redirect to another brand is only suggested`() = runBlocking {
        val c = MirrorProbe(client("https://parked-domain.xyz/")).detect("src", "https://site.com/")
        assertEquals(MirrorCandidate("src", "parked-domain.xyz", autoApply = false), c)
    }

    @Test
    fun `no redirect, www only difference, failures and errors give nothing`() = runBlocking {
        assertNull(MirrorProbe(client(null)).detect("src", "https://site.com/"))
        assertNull(MirrorProbe(client("https://www.site.com/")).detect("src", "https://site.com/"))
        assertNull(MirrorProbe(client("https://other.net/", code = 503)).detect("src", "https://site.com/"))
        assertNull(MirrorProbe(client(null, fail = true)).detect("src", "https://site.com/"))
        assertNull(MirrorProbe(client("https://192.168.1.5/")).detect("src", "https://site.com/"))
        assertNull(MirrorProbe(client("https://x.net/")).detect("src", "not a url"))
    }

    @Test
    fun `brand ignores subdomains, hyphens and two-part suffixes`() {
        assertEquals("site", MirrorProbe.brand("www.site.com"))
        assertEquals("mysite", MirrorProbe.brand("my-site.net"))
        assertEquals("site", MirrorProbe.brand("cdn.site.co.uk"))
        assertTrue(MirrorProbe.sameBrand("site.com", "www.site.io"))
        assertTrue(!MirrorProbe.sameBrand("site.com", "other.com"))
    }

    // ── ErrorActionHandler.resolveConnectionError ───────────────────────────

    private fun handler(probe: MirrorProbe, settings: SettingsRepository, home: String? = "https://site.com/"): ErrorActionHandler {
        val sources = mockk<SourceManager>()
        val source = mockk<MangaSource>()
        io.mockk.every { source.homepageUrl } returns home
        coEvery { sources.getById("src") } returns source
        return ErrorActionHandler(mockk<CloudflareInterceptor>(), settings, sources, probe)
    }

    @Test
    fun `a connection failure with a same-brand redirect stores the override`() = runBlocking {
        val settings = mockk<SettingsRepository>(relaxed = true)
        val h = handler(MirrorProbe(client("https://site.net/")), settings)
        assertEquals(MirrorResolution.Applied("site.net"), h.resolveConnectionError("src", UnknownHostException("x")))
        coVerify { settings.setSourceDomainOverride("src", "site.net") }
    }

    @Test
    fun `a different brand is suggested and nothing is stored`() = runBlocking {
        val settings = mockk<SettingsRepository>(relaxed = true)
        val h = handler(MirrorProbe(client("https://elsewhere.org/")), settings)
        assertEquals(
            MirrorResolution.Suggested(ErrorAction.UseNewDomain("src", "elsewhere.org")),
            h.resolveConnectionError("src", ConnectException("refused")),
        )
        coVerify(exactly = 0) { settings.setSourceDomainOverride(any(), any()) }
    }

    @Test
    fun `only connection failures trigger the probe`() = runBlocking {
        val settings = mockk<SettingsRepository>(relaxed = true)
        val h = handler(MirrorProbe(client("https://site.net/")), settings)
        assertEquals(MirrorResolution.None, h.resolveConnectionError("src", RuntimeException("parse")))
        assertEquals(MirrorResolution.None, h.resolveConnectionError("src", IOException("HTTP 500")))
        // ... ale i obalená příčina se pozná
        assertEquals(MirrorResolution.Applied("site.net"), h.resolveConnectionError("src", RuntimeException("w", UnknownHostException("x"))))
    }
}

package com.haise.jiyu.source.interceptor

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudflareSupportTest {

    @Before
    fun setUp() = CloudflareUserAgent.resetForTest()

    // ── User-Agent ───────────────────────────────────────────────────────────

    @Test
    fun `the real webview user agent is used and remembered`() {
        val context = mockk<Context>()
        val real = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A; wv) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile Safari/537.36"
        var calls = 0
        assertEquals(real, CloudflareUserAgent.value(context) { calls++; real })
        assertEquals(real, CloudflareUserAgent.value(context) { calls++; "jiny" })
        assertEquals("druhé volání se bere z paměti", 1, calls)
    }

    @Test
    fun `an unusable engine user agent falls back to the constant without caching it`() {
        val context = mockk<Context>()
        assertEquals(CloudflareInterceptor.CHROME_UA, CloudflareUserAgent.value(context) { null })
        assertEquals(CloudflareInterceptor.CHROME_UA, CloudflareUserAgent.value(context) { "   " })
        assertEquals(CloudflareInterceptor.CHROME_UA, CloudflareUserAgent.value(context) { "Mozilla/5.0 (X11; Linux) Firefox/1" })
        // Když se UA později podaří zjistit, použije se.
        val real = "Mozilla/5.0 (Linux; Android 14) Chrome/140 Mobile"
        assertEquals(real, CloudflareUserAgent.value(context) { real })
    }

    // ── SolveTracker ─────────────────────────────────────────────────────────

    @Test
    fun `solved only after consecutive ok probes with the clearance cookie`() {
        val t = SolveTracker(requiredStable = 2)
        assertFalse(t.onPoll(false, "ok"))
        assertFalse("jeden ok průchod nestačí", t.onPoll(true, "ok"))
        assertTrue(t.onPoll(true, "ok"))
    }

    @Test
    fun `a wait state or a lost cookie resets the streak`() {
        val t = SolveTracker(requiredStable = 2)
        assertFalse(t.onPoll(true, "ok"))
        assertFalse(t.onPoll(true, "wait"))
        assertFalse("po přerušení se počítá znovu", t.onPoll(true, "ok"))
        assertFalse(t.onPoll(false, null))
        assertFalse(t.onPoll(true, "ok"))
        assertTrue(t.onPoll(true, "ok"))
    }

    @Test
    fun `an unusual page is accepted after enough cookie only polls`() {
        val t = SolveTracker(requiredStable = 2, maxCookieOnlyPolls = 4)
        assertFalse(t.onPoll(true, "wait"))
        assertFalse(t.onPoll(true, null))
        assertFalse(t.onPoll(true, "error"))
        assertTrue(t.onPoll(true, "wait"))
    }

    // ── čištění cookies ──────────────────────────────────────────────────────

    private class FakeEditor(private val header: String?) : CookieEditor {
        val set = mutableListOf<String>()
        var flushed = false
        override fun cookieHeader(url: String) = header
        override fun set(url: String, cookie: String) { set += cookie }
        override fun flush() { flushed = true }
    }

    @Test
    fun `only cloudflare cookies are expired, on the host and its parent domains`() {
        val editor = FakeEditor("session=abc; cf_clearance=xyz; lang=cs; __cf_bm=q")
        CloudflareCookies.clear("https://www.site.example.com/manga/", editor)

        val names = editor.set.map { it.substringBefore('=') }.toSet()
        assertEquals(setOf("cf_clearance", "__cf_bm"), names)
        assertTrue(editor.set.all { it.contains("Max-Age=0") })
        assertTrue(editor.set.any { it.contains("Domain=www.site.example.com") })
        assertTrue(editor.set.any { it.contains("Domain=.example.com") })
        assertFalse("bez domény nejvyšší úrovně", editor.set.any { it.endsWith("Domain=com") || it.endsWith("Domain=.com") })
        assertTrue(editor.flushed)
    }

    @Test
    fun `nothing happens when there are no cloudflare cookies or the url is invalid`() {
        val none = FakeEditor("session=abc; lang=cs")
        CloudflareCookies.clear("https://site.test/", none)
        assertTrue(none.set.isEmpty())
        assertFalse(none.flushed)

        val bad = FakeEditor("cf_clearance=x")
        CloudflareCookies.clear("not a url", bad)
        assertTrue(bad.set.isEmpty())
    }

    @Test
    fun `cloudflare cookie names and parent domains`() {
        assertTrue(CloudflareCookies.isCloudflareCookie("cf_clearance"))
        assertTrue(CloudflareCookies.isCloudflareCookie("cf_chl_rc_ni"))
        assertTrue(CloudflareCookies.isCloudflareCookie("_cfuvid"))
        assertFalse(CloudflareCookies.isCloudflareCookie("session"))
        assertFalse(CloudflareCookies.isCloudflareCookie("xcf_clearance"))
        assertEquals(listOf("a.b.site.com", "b.site.com", "site.com"), CloudflareCookies.hostAndParents("a.b.site.com"))
        assertEquals(listOf("site.com"), CloudflareCookies.hostAndParents("site.com"))
    }
}

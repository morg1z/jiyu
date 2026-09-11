package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFontRepositoryTest {

    // ── isAcceptableFontContentType ──

    @Test
    fun `a standard font content-type is accepted`() {
        assertTrue(isAcceptableFontContentType("font/ttf", "https://example.com/font.ttf"))
        assertTrue(isAcceptableFontContentType("font/otf", "https://example.com/font.otf"))
        assertTrue(isAcceptableFontContentType("application/x-font-ttf", "https://example.com/font"))
    }

    @Test
    fun `a content-type with a charset suffix is still accepted`() {
        assertTrue(isAcceptableFontContentType("font/ttf; charset=binary", "https://example.com/font.ttf"))
    }

    @Test
    fun `a generic octet-stream content-type falls back to the url extension`() {
        assertTrue(isAcceptableFontContentType("application/octet-stream", "https://example.com/MyFont.ttf"))
        assertFalse(isAcceptableFontContentType("application/octet-stream", "https://example.com/not-a-font"))
    }

    @Test
    fun `a missing content-type falls back to the url extension`() {
        assertTrue(isAcceptableFontContentType("", "https://cdn.example.com/fonts/custom.otf?v=2"))
        assertFalse(isAcceptableFontContentType("", "https://cdn.example.com/page.html"))
    }

    @Test
    fun `an unrelated content-type is rejected regardless of the url`() {
        assertFalse(isAcceptableFontContentType("text/html", "https://example.com/font.ttf"))
        assertFalse(isAcceptableFontContentType("image/png", "https://example.com/font.ttf"))
    }

    // ── cachedFontFileName ──

    @Test
    fun `the cached file name keeps a recognized font extension`() {
        assertTrue(cachedFontFileName("https://example.com/font.otf").endsWith(".otf"))
        assertTrue(cachedFontFileName("https://example.com/font.ttf?v=2").endsWith(".ttf"))
    }

    @Test
    fun `an unrecognized or missing extension defaults to ttf`() {
        assertTrue(cachedFontFileName("https://example.com/font-download").endsWith(".ttf"))
    }

    @Test
    fun `the same url always maps to the same cached file name`() {
        val url = "https://example.com/font.ttf"
        assertEquals(cachedFontFileName(url), cachedFontFileName(url))
    }

    @Test
    fun `different urls map to different cached file names`() {
        assertFalse(
            cachedFontFileName("https://example.com/a.ttf") ==
                cachedFontFileName("https://example.com/b.ttf"),
        )
    }
}

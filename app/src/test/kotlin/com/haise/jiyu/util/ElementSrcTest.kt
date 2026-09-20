package com.haise.jiyu.util

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ElementSrcTest {

    private fun img(html: String) = Jsoup.parse(html).selectFirst("img")!!

    @Test
    fun `lazy attribute wins over placeholder src`() {
        assertEquals("/real.jpg", img("""<img src="data:image/gif;base64,AAA" data-src="/real.jpg">""").lazySrc())
        assertEquals("/real.jpg", img("""<img src="/placeholder.gif" data-lazy-src="/real.jpg">""").lazySrc())
    }

    @Test
    fun `plain src is used when there is no lazy attribute`() {
        assertEquals("/a.jpg", img("""<img src=" /a.jpg ">""").lazySrc()?.trim())
    }

    @Test
    fun `srcset first url is the fallback and data uris are skipped`() {
        assertEquals("/small.jpg", img("""<img src="data:image/gif;base64,AAA" srcset="/small.jpg 300w, /big.jpg 900w">""").lazySrc())
        assertNull(img("""<img src="data:image/gif;base64,AAA">""").lazySrc())
        assertNull(img("<img>").lazySrc())
    }

    @Test
    fun `selectFirstOrThrow throws SourceParseException with the url`() {
        val doc = Jsoup.parse("<div class='a'></div>")
        assertEquals("a", doc.selectFirstOrThrow("div.a").className())
        val e = assertThrows(SourceParseException::class.java) { doc.selectFirstOrThrow("div.missing", "https://x/y") }
        assertEquals(true, e.message!!.contains("div.missing") && e.message!!.contains("https://x/y"))
        assertEquals(true, e is java.io.IOException)
    }
}

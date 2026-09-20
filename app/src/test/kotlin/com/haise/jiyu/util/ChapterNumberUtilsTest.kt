package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNumberUtilsTest {

    @Test
    fun `an abbreviated keyword with a dot is parsed instead of yielding a bare dot`() {
        assertEquals(12f, parseChapterNumber("Cap. 12"))
        assertEquals(5f, parseChapterNumber("Ch. 5"))
        assertEquals(3f, parseChapterNumber("Ep. 3"))
    }

    @Test
    fun `a leading volume marker does not steal the chapter number`() {
        assertEquals(15f, parseChapterNumber("Vol.3 Chapter 15"))
        assertEquals(15f, parseChapterNumber("Vol. 2 Ch.15"))
    }

    @Test
    fun `decimal chapters are kept`() {
        assertEquals(10.5f, parseChapterNumber("Chapter 10.5"))
        assertEquals(10.5f, parseChapterNumber("Ch.10.5"))
    }

    @Test
    fun `a name without a keyword falls back to the first number`() {
        assertEquals(42f, parseChapterNumber("42"))
        assertEquals(7f, parseChapterNumber("Oneshot 7 special"))
    }

    @Test
    fun `a keyword inside a longer word is not treated as a keyword`() {
        // "Church" nesmi vytvorit shodu na "ch" - ani "Much" - cislo se bere az jako prvni cislo v textu.
        assertEquals(3f, parseChapterNumber("Church 3"))
    }

    @Test
    fun `names without any number and a lone dot give null`() {
        assertNull(parseChapterNumber("Extra"))
        assertNull(parseChapterNumber("Omake ."))
    }

    @Test
    fun `localized keywords are recognised`() {
        assertEquals(8f, parseChapterNumber("Capítulo 8"))
        assertEquals(9f, parseChapterNumber("Kapitola 9"))
        assertEquals(4f, parseChapterNumber("Chapitre 4"))
    }
}

package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Nahlášený bug: tituly evidované různými zdroji s drobně jiným přepisem (diakritika)
 * nebo v původním písmu (CJK) se přes [normalizeMangaTitle] nikdy nespárovaly.
 */
class MangaTitleUtilsTest {

    @Test
    fun `diacritics are stripped so accented and plain spellings match`() {
        assertEquals(normalizeMangaTitle("cafe"), normalizeMangaTitle("café"))
        assertEquals(normalizeMangaTitle("Pokemon"), normalizeMangaTitle("Pokémon"))
    }

    @Test
    fun `CJK characters survive normalization instead of being stripped`() {
        val japanese = normalizeMangaTitle("ソロレベリング")
        assertEquals("ソロレベリング", japanese)
        val chinese = normalizeMangaTitle("我独自升级")
        assertEquals("我独自升级", chinese)
        val korean = normalizeMangaTitle("나 혼자만 레벨업")
        assertNotEquals("", korean)
    }

    @Test
    fun `two different CJK titles still normalize to different strings`() {
        assertNotEquals(normalizeMangaTitle("我独自升级"), normalizeMangaTitle("ソロレベリング"))
    }

    @Test
    fun `case, punctuation and extra whitespace are still normalized as before`() {
        assertEquals("solo leveling", normalizeMangaTitle("  Solo   Leveling!! "))
        assertEquals(normalizeMangaTitle("Solo Leveling"), normalizeMangaTitle("SOLO LEVELING"))
    }

    @Test
    fun `cyrillic titles are preserved as before`() {
        assertEquals(normalizeMangaTitle("соло левелинг"), normalizeMangaTitle("Соло Левелинг"))
    }
}

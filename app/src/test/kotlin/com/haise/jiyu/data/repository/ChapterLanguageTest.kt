package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Dedup viceradkovych kapitol podle jazyka u agregatoru (ComicK, comickart) -
 * u cisla s EN verzi se ne-EN radky zahodi, jinde se nic nemeni (audit:
 * nerozlisitelne duplicity a cizojazycne kapitoly u Sato-san).
 */
class ChapterLanguageTest {

    private fun ch(id: String, num: Float, lang: String?) = ChapterEntity(
        id = id, mangaId = "m", sourceId = "comickart", url = "u/$id", name = "Ch.$num",
        chapterNumber = num, dateUpload = 0L, language = lang,
    )

    @Test
    fun `non-EN row is dropped when EN exists for the same number`() {
        val list = listOf(
            ch("5-pt", 5f, "pt-br"), ch("5-en", 5f, "en"),
            ch("4-pt", 4f, "pt"),    ch("4-en", 4f, "en"),
        )
        assertEquals(listOf("5-en", "4-en"), list.preferEnglishChapters().map { it.id })
    }

    @Test
    fun `number without EN keeps all language versions`() {
        val list = listOf(ch("6-pt", 6f, "pt-br"), ch("6-es", 6f, "es-419"))
        assertEquals(listOf("6-pt", "6-es"), list.preferEnglishChapters().map { it.id })
    }

    @Test
    fun `EN variants count as English (en-us)`() {
        val list = listOf(ch("5-pt", 5f, "pt"), ch("5-en", 5f, "en-us"))
        assertEquals(listOf("5-en"), list.preferEnglishChapters().map { it.id })
    }

    @Test
    fun `all EN scanlator versions of one number survive - dedupe is per language, not per group`() {
        val list = listOf(
            ch("5-pt", 5f, "pt"),
            ch("5-enA", 5f, "en"), ch("5-enB", 5f, "en"),
        )
        assertEquals(listOf("5-enA", "5-enB"), list.preferEnglishChapters().map { it.id })
    }

    @Test
    fun `single-language list is a no-op and returns the same instance`() {
        val list = listOf(ch("5", 5f, "pt"), ch("4", 4f, "pt"))
        assertSame(list, list.preferEnglishChapters())
    }

    @Test
    fun `list without any language is a no-op (sources that do not report it)`() {
        val list = listOf(ch("5a", 5f, null), ch("5b", 5f, null))
        assertSame(list, list.preferEnglishChapters())
    }

    @Test
    fun `null-language rows are never dropped - language unknown is not non-English`() {
        val list = listOf(
            ch("5-old", 5f, null),   // stary radek pred backfillem jazyka
            ch("5-en", 5f, "en"), ch("5-pt", 5f, "pt"),
        )
        assertEquals(listOf("5-old", "5-en"), list.preferEnglishChapters().map { it.id })
    }

    @Test
    fun `unnumbered chapters (number 0) are never collapsed`() {
        val list = listOf(ch("a", 0f, "en"), ch("b", 0f, "pt"), ch("c", 0f, "pt"))
        assertEquals(3, list.preferEnglishChapters().size)
    }

    @Test
    fun `input order is preserved`() {
        val list = listOf(
            ch("10-en", 10f, "en"), ch("10-fr", 10f, "fr"),
            ch("9-pt", 9f, "pt"),
            ch("8-en", 8f, "en"),  ch("8-pt", 8f, "pt"),
        )
        assertEquals(listOf("10-en", "9-pt", "8-en"), list.preferEnglishChapters().map { it.id })
    }

    // ── englishOnlyChapters (strict gate pro otevreni - silnejsi nez dedup) ────

    @Test
    fun `englishOnly drops non-EN rows even without EN counterpart - VN-ahead chapter`() {
        // "Coffee wo Shizuka ni" bug: VN scanlation predbehnuta, dedup ne-EN radky
        // necha (kompletnost cisel), ale otevrit se nesmi.
        val list = listOf(ch("10-vi", 10f, "vi"), ch("9-en", 9f, "en"))
        assertEquals(listOf("9-en"), list.englishOnlyChapters().map { it.id })
    }

    @Test
    fun `englishOnly keeps en variants and null language`() {
        val list = listOf(
            ch("5-en", 5f, "en"), ch("5-enus", 5f, "en-us"),
            ch("5-null", 5f, null), ch("5-vi", 5f, "vi"),
        )
        assertEquals(listOf("5-en", "5-enus", "5-null"), list.englishOnlyChapters().map { it.id })
    }

    @Test
    fun `isEnglishChapterLanguage - null and blank are acceptable, families honored`() {
        assert(isEnglishChapterLanguage(null))
        assert(isEnglishChapterLanguage(""))
        assert(isEnglishChapterLanguage("en"))
        assert(isEnglishChapterLanguage("en-US"))
        assert(isEnglishChapterLanguage("EN"))
        assert(!isEnglishChapterLanguage("vi"))
        assert(!isEnglishChapterLanguage("pt-br"))
        assert(!isEnglishChapterLanguage("jp"))
    }
}

package com.haise.jiyu.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaOcrGarbageFilterTest {

    @Test
    fun `normal short dialogue is not flagged`() {
        assertFalse(MangaOcrGarbageFilter.isPathologicalOutput("おはよう"))
        assertFalse(MangaOcrGarbageFilter.isPathologicalOutput("Hello there!"))
    }

    @Test
    fun `very short punctuation-only text is not flagged even if repetitive`() {
        // "..." a "!!" jsou legitimni kratke odpovedi, ne rozbity vystup - min. delka je schvalne.
        assertFalse(MangaOcrGarbageFilter.isPathologicalOutput("..."))
        assertFalse(MangaOcrGarbageFilter.isPathologicalOutput("!!"))
    }

    @Test
    fun `long run of a single repeated punctuation character is flagged`() {
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("．．．．．．．．．．．．"))
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("!!!!!!!!!!!!"))
    }

    @Test
    fun `long run of a single repeated kana character is flagged`() {
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("ああああああああああ"))
    }

    @Test
    fun `long run of a repeated separator character is flagged`() {
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("ーーーーーーーーーー"))
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("・・・・・・・・・・"))
    }

    @Test
    fun `a two-character loop is flagged`() {
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("はいはいはいはいはいはい"))
    }

    @Test
    fun `a repeating pattern that covers only a small fraction of a longer real sentence is not flagged`() {
        // "はは" (matka) je bezne slovo, ne smycka - nesmi se zahodit jen kvuli
        // castecne opakujici se sekvenci uprostred delsi legitimni vety.
        assertFalse(MangaOcrGarbageFilter.isPathologicalOutput("お母さんははいつも優しい人だった"))
    }

    @Test
    fun `three character repeating pattern is flagged`() {
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("あいうあいうあいうあいうあいう"))
    }

    /**
     * Nahlašeno na "herni stat box" (viz BubbleMerge.kt STRUCTURED_FIELD_*, real logcat)
     * - ML Kit omylem precetl teckovy ukazatel postupu vedle "God's Legion Mage"/"Skye Han"
     * jako text "- e******" (confidence 0,371), ktery pak vizualne kolidoval s prekladem
     * realnych poli. Filtr uz presne tenhle vzor resil pro manga-ocr cestu ([OcrEngine.recognizeLines]
     * ho ted pouziva i pro ML Kit).
     */
    @Test
    fun `misread UI dot indicator is flagged`() {
        assertTrue(MangaOcrGarbageFilter.isPathologicalOutput("- e******"))
    }
}

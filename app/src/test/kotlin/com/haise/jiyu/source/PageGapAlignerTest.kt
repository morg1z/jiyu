package com.haise.jiyu.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGapAlignerTest {

    // ── splice ───────────────────────────────────────────────────────────────

    @Test
    fun `splice vlozi stranky pred insertIndex a precisluje indexy`() {
        val pages = (0..5).map { Page(index = it, url = "p$it") }
        val out = PageGapAligner.splice(pages, mapOf(3 to listOf(Page(0, "new1"), Page(0, "new2"))))
        assertEquals(listOf("p0", "p1", "p2", "new1", "new2", "p3", "p4", "p5"), out.map { it.url })
        assertEquals((0 until out.size).toList(), out.map { it.index })
    }

    @Test
    fun `splice s prazdnymi vlozenim vraci puvodni seznam`() {
        val pages = listOf(Page(0, "a"))
        assertSame(pages, PageGapAligner.splice(pages, emptyMap()))
    }

    @Test
    fun `splice na konci seznamu funguje - insertIndex za poslednim prvkem`() {
        val pages = listOf(Page(0, "a"), Page(1, "b"))
        // insertIndex == size: forEachIndexed nikdy nenarazi na klic - takova
        // vlozeni se zahodi (detektor ho nikdy nevrati, jen proverka robustnosti)
        val out = PageGapAligner.splice(pages, mapOf(2 to listOf(Page(0, "tail"))))
        assertEquals(2, out.size)
    }

    // ── alignByNumbers ───────────────────────────────────────────────────────

    @Test
    fun `alignByNumbers najde donor stranky pro mezeru mezi sousedy`() {
        // Zdroj: ...144.webp, 146.webp... ; donor ma 144,145,146
        val src = (0..144).map { "s/$it.webp" } + (146..200).map { "s/$it.webp" }
        val donor = (140..150).map { "d/$it.webp" }
        val gaps = PageGapDetector.detect(src)
        val map = PageGapAligner.alignByNumbers(gaps, src, donor)
        // donor index pro 145 = 5 (140..150 -> 140 na 0)
        assertEquals(mapOf(145 to listOf(5)), map)
    }

    @Test
    fun `alignByNumbers odmitne donor bez souseda - nahodne cislo jine rady`() {
        val src = (0..144).map { "s/$it.webp" } + (146..200).map { "s/$it.webp" }
        // Donor ma 145, ale nema sousedy 144/146 - cislo 145 neni prokazatelne ta stranka
        val donor = listOf("d/145.webp") + (500..510).map { "d/$it.webp" }
        val gaps = PageGapDetector.detect(src)
        assertNull(PageGapAligner.alignByNumbers(gaps, src, donor))
    }

    @Test
    fun `alignByNumbers odmitne nesedle cisla mezi sousedy`() {
        // Donor ma 144 a 146, ale mezi nimi lezi stranka s cislem 900 - ne 145
        val src = (0..144).map { "s/$it.webp" } + (146..200).map { "s/$it.webp" }
        val donor = listOf("d/144.webp", "d/900.webp", "d/146.webp")
        val gaps = PageGapDetector.detect(src)
        assertNull(PageGapAligner.alignByNumbers(gaps, src, donor))
    }

    @Test
    fun `alignByNumbers odmitne donor s jinym cislovanim (page_001 styl)`() {
        val src = (0..144).map { "s/$it.webp" } + (146..200).map { "s/$it.webp" }
        val donor = (1..25).map { "d/page_%03d.webp".format(it) }
        val gaps = PageGapDetector.detect(src)
        assertNull(PageGapAligner.alignByNumbers(gaps, src, donor))
    }

    @Test
    fun `alignByNumbers multi-gap - vsechny nebo nic`() {
        val missing = setOf(145, 265)
        val src = (0..346).filter { it !in missing }.map { "s/$it.webp" }
        // Donor pokryva jen prvni diru
        val donor = (140..150).map { "d/$it.webp" }
        val gaps = PageGapDetector.detect(src)
        assertNull(PageGapAligner.alignByNumbers(gaps, src, donor))
        // Donor pokryva obe
        val donor2 = (0..350).map { "d/$it.webp" }
        val map = PageGapAligner.alignByNumbers(gaps, src, donor2)
        // Klic = insertIndex v puvodnim seznamu, hodnota = indexy v donorovi
        assertEquals(listOf(145), map?.get(145))
        assertEquals(listOf(265), map?.get(264))
    }

    // ── LazyPageUrl.isMarked ─────────────────────────────────────────────────

    @Test
    fun `isMarked rozpozna markovanou i obycejnou url`() {
        val marked = com.haise.jiyu.util.LazyPageUrl.encode("donor", 5, "https://d.x/img/7.webp")
        assertEquals("https://d.x/img/7.webp#jiyu_lazy=donor/5", marked)
        assertTrue(com.haise.jiyu.util.LazyPageUrl.isMarked(marked))
        assertTrue(!com.haise.jiyu.util.LazyPageUrl.isMarked("https://d.x/img/7.webp"))
        assertTrue(!com.haise.jiyu.util.LazyPageUrl.isMarked("https://d.x/img/7.webp#other=1"))
    }

    @Test
    fun `markovana url se dekoduje zpet na donor source a index`() {
        val marked = com.haise.jiyu.util.LazyPageUrl.encode("teamshadowi", 7, "https://images.x/p.webp")
        // fragment = cast za '#' - stejny vstup jako Uri.fragment ve fetcheru
        val (src, idx) = com.haise.jiyu.util.LazyPageUrl.decodeFragment(marked.substringAfter('#'))!!
        assertEquals("teamshadowi", src)
        assertEquals(7, idx)
    }
}

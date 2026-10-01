package com.haise.jiyu.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGapDetectorTest {

    private fun urls(numbers: Iterable<Int>): List<String> =
        numbers.map { "https://cdn.example.com/manga/ch/$it.webp" }

    @Test
    fun `stemNumber vytahne cislo z nazvu souboru`() {
        assertEquals(145, PageGapDetector.stemNumber("https://cdn.x.com/a/b/145.webp"))
        assertEquals(145, PageGapDetector.stemNumber("https://cdn.x.com/a/b/page_145.webp"))
        assertEquals(145, PageGapDetector.stemNumber("https://cdn.x.com/a/b/0145.jpg?token=abc#frag"))
        assertEquals(null, PageGapDetector.stemNumber("https://cdn.x.com/a/b/cover.webp"))
    }

    @Test
    fun `jedna dira v cislovani se detekuje na spravne pozici`() {
        // 0..144, 146..200 -> chybi 145 pred indexem 145
        val nums = (0..144) + (146..200)
        val gaps = PageGapDetector.detect(urls(nums))
        assertEquals(1, gaps.size)
        assertEquals(145, gaps[0].insertIndex)
        assertEquals(145..145, gaps[0].missingNumbers)
    }

    @Test
    fun `vice der se detekuje nezavisle - realny pripad comickart ch215`() {
        // Skutecna data: 0..346 bez 145, 265, 304, 320
        val missing = setOf(145, 265, 304, 320)
        val nums = (0..346).filter { it !in missing }
        val gaps = PageGapDetector.detect(urls(nums))
        assertEquals(4, gaps.size)
        assertEquals(listOf(145..145, 265..265, 304..304, 320..320), gaps.map { it.missingNumbers })
        // insertIndex = index prvku ZA dirou ve filtrovanym seznamu
        assertEquals(145, gaps[0].insertIndex) // 146.webp sedi na indexu 145
        assertEquals(264, gaps[1].insertIndex) // 266.webp na indexu 264 (146..264 = 119 prvku)
        assertEquals(302, gaps[2].insertIndex) // 305.webp na indexu 302
        assertEquals(317, gaps[3].insertIndex) // 321.webp na indexu 317
    }

    @Test
    fun `multi-page dira hlasi vsechna chybejici cisla`() {
        val nums = listOf(0, 1, 2, 6, 7) // chybi 3,4,5
        val gaps = PageGapDetector.detect(urls(nums))
        assertEquals(1, gaps.size)
        assertEquals(3, gaps[0].insertIndex)
        assertEquals(3..5, gaps[0].missingNumbers)
        assertEquals(3, gaps[0].missingCount)
    }

    @Test
    fun `kompletni sekvence nema diry`() {
        assertTrue(PageGapDetector.detect(urls(0..50)).isEmpty())
    }

    @Test
    fun `malo stranek se nedetekuje`() {
        assertTrue(PageGapDetector.detect(urls(listOf(1, 5))).isEmpty())
    }

    @Test
    fun `neciselne url se ignoruji`() {
        val urls = listOf(
            "https://cdn.x.com/a/001.webp",
            "https://cdn.x.com/a/cover-nocislo.webp",
            "https://cdn.x.com/a/003.webp",
            "https://cdn.x.com/a/004.webp",
        )
        // 3/4 ciselnych = pod MIN_PARSE_FRACTION -> nevim, nedetekovat
        assertTrue(PageGapDetector.detect(urls).isEmpty())
    }

    @Test
    fun `sestupna sekvence se nedetekuje`() {
        assertTrue(PageGapDetector.detect(urls(listOf(5, 4, 3, 2, 1))).isEmpty())
    }

    @Test
    fun `duplicitni cisla se nedetekuji`() {
        assertTrue(PageGapDetector.detect(urls(listOf(1, 2, 2, 3))).isEmpty())
    }

    @Test
    fun `obri dira pres MAX_GAP_SIZE znamena jine cislovani - nedetekovat`() {
        // napr. extras cislovane vysokymi cisly
        val nums = (0..20) + (500..520)
        assertTrue(PageGapDetector.detect(urls(nums)).isEmpty())
    }

    @Test
    fun `dira na zacatku sekvence se nehlasi - prvni cislo muze byt zacatek`() {
        // zdroj zacina na 10 - nelze poznat, jestli 0..9 chybi nebo neexistuji
        assertTrue(PageGapDetector.detect(urls(10..40)).isEmpty())
    }
}

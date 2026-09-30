package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test [dropSliceBoundaryDuplicates] - reprodukuje audit RWS ch.215:
 * bublina "YOU LIKE THE MANS YOU...?" protnutá řezem webtoon sliců se OCR'd na
 * stránce 90 (spodek, t=0.94-0.98) i stránce 91 (vršek, t=0-0.067), oba fragmenty
 * dostaly překlad a vykreslily se -> "LÍBÍ SE TI MANS, TY…?" dvakrát na přešití.
 *
 * Poražený fragment se NEmaže - označí se seamCover a render přes něj položí jen
 * záplatu (zakryje kopii originálního lettering; holé smazání by originál
 * nechalo vidět vedle českého překladu - druhý audit téže kapitoly).
 */
class SliceBoundaryDedupTest {

    private fun block(
        text: String,
        left: Float, top: Float, right: Float, bottom: Float,
        isSfx: Boolean = false,
    ) = TranslatedBlock(
        originalText = text, translatedText = "překlad:$text",
        leftF = left, topF = top, rightF = right, bottomF = bottom,
        isSfx = isSfx,
    )

    // Reálná geometrie z auditu: p90 fragment menší (4 % výšky) než p91 (6,7 %).
    private val prevFragment = block("YOU LIKE THE MANS YOU...?", 0.3725f, 0.9406f, 0.765f, 0.9766f)
    private val curFragment = block("YOU LIKE THE MANS YOU...?", 0.4175f, 0f, 0.66375f, 0.0672f)

    @Test
    fun `smaller previous fragment is covered when current is taller`() {
        val result = dropSliceBoundaryDuplicates(listOf(curFragment), listOf(prevFragment))
        assertEquals(1, result.kept.size)
        assertFalse(result.kept[0].seamCover)
        // Vítěz dostane seamSpan - sjednocený dosah páru + rezerva, aby jeho krycí
        // výplň pokryla celý přeříznutý řádek (kraje písmen vyčnívají za OCR boxy).
        assertEquals(0.3725f - 0.06f, result.kept[0].seamSpanLF!!, 0.0001f)
        assertEquals(0.765f + 0.06f, result.kept[0].seamSpanRF!!, 0.0001f)
        assertEquals(0.3725f - 0.06f, result.spanOnPrevious[0]!!.first, 0.0001f)
        assertEquals(0.765f + 0.06f, result.spanOnPrevious[0]!!.second, 0.0001f)
        assertTrue(result.coveredFromCurrent.isEmpty())
        assertEquals(setOf(0), result.coverOnPrevious)
    }

    @Test
    fun `smaller current fragment is marked seamCover when previous is taller`() {
        val tallPrev = block("YOU LIKE THE MANS YOU...?", 0.37f, 0.85f, 0.77f, 0.985f)
        val result = dropSliceBoundaryDuplicates(listOf(curFragment), listOf(tallPrev))
        // Blok zůstává v seznamu (zakryje kopii originálu), jen bez textu.
        assertEquals(1, result.kept.size)
        assertTrue(result.kept[0].seamCover)
        // displayText se vyprazdni - pojistka proti render ceste, co by seamCover
        // gate minula: prazdny text se fyzicky nema cim vykreslit, takze i za
        // chyby zustane videt jen original, nikdy duplicitni preklad.
        assertEquals("", result.kept[0].displayText)
        // Poražený dostane span - jeho krycí výplň má pokrýt celý řezový řádek.
        assertEquals(0.37f - 0.06f, result.kept[0].seamSpanLF!!, 0.0001f)
        assertEquals(0.77f + 0.06f, result.kept[0].seamSpanRF!!, 0.0001f)
        assertEquals(listOf(curFragment), result.coveredFromCurrent)
        assertTrue(result.coverOnPrevious.isEmpty())
    }

    @Test
    fun `same text away from the slice edge is not a duplicate`() {
        val prevMid = block("HELLO", 0.3f, 0.5f, 0.7f, 0.6f)
        val curMid = block("HELLO", 0.3f, 0.4f, 0.7f, 0.5f)
        val result = dropSliceBoundaryDuplicates(listOf(curMid), listOf(prevMid))
        assertEquals(listOf(curMid), result.kept)
        assertTrue(result.coverOnPrevious.isEmpty())
    }

    @Test
    fun `different text at the seam is not a duplicate`() {
        val prevEdge = block("FIRST LINE", 0.3f, 0.94f, 0.7f, 0.99f)
        val curEdge = block("SECOND LINE", 0.3f, 0f, 0.7f, 0.05f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(listOf(curEdge), result.kept)
        assertTrue(result.coverOnPrevious.isEmpty())
    }

    @Test
    fun `same text at seam without horizontal overlap is not a duplicate`() {
        val prevEdge = block("REPEAT", 0.05f, 0.94f, 0.30f, 0.99f)
        val curEdge = block("REPEAT", 0.60f, 0f, 0.85f, 0.05f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(listOf(curEdge), result.kept)
        assertTrue(result.coverOnPrevious.isEmpty())
    }

    @Test
    fun `sfx blocks are never deduplicated`() {
        val prevSfx = block("DADUN", 0.3f, 0.94f, 0.7f, 0.99f, isSfx = true)
        val curSfx = block("DADUN", 0.3f, 0f, 0.7f, 0.05f, isSfx = true)
        val result = dropSliceBoundaryDuplicates(listOf(curSfx), listOf(prevSfx))
        assertEquals(listOf(curSfx), result.kept)
        assertTrue(result.coverOnPrevious.isEmpty())
    }

    @Test
    fun `whitespace differences still match after normalization`() {
        val prevEdge = block("A  B\nC", 0.3f, 0.94f, 0.7f, 0.99f)
        val curEdge = block("A B C", 0.3f, 0f, 0.7f, 0.05f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        // Jedna dvojice se sešla - jeden z fragmentů skončil jako seamCover.
        assertEquals(1, result.kept.size)
        assertEquals(1, result.coveredFromCurrent.size + result.coverOnPrevious.size)
    }

    @Test
    fun `punctuation and ellipsis variants still match at the seam`() {
        // OCR obou půlek se liší v interpunkci - přísná shoda by dvojici propustila
        // a oba fragmenty by se vykreslily (hlášená duplicita na přešití).
        val prevEdge = block("YOU LIKE THE MANS YOU...?", 0.3f, 0.94f, 0.7f, 0.99f)
        val curEdge = block("you like the mans you… ?", 0.32f, 0f, 0.72f, 0.05f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(1, result.coveredFromCurrent.size + result.coverOnPrevious.size)
    }

    @Test
    fun `truncated fragment still matches full text at the seam`() {
        // Fragment uříznutý řezem přečte jen část věty - delší strana drží prefix.
        val prevEdge = block("AND NOW, EVEN THE RESIDUAL RECOIL OF YOUR ATTACKS ARE ENOUGH TO DESTROY A PLANET.", 0.1f, 0.92f, 0.9f, 0.99f)
        val curEdge = block("AND NOW, EVEN THE RES", 0.1f, 0f, 0.6f, 0.04f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(1, result.coveredFromCurrent.size + result.coverOnPrevious.size)
    }

    @Test
    fun `short shared prefix alone is not enough at the seam`() {
        // "IT WAS" vs "IT WASN'T FAIR" - krátký společný začátek nesmí stačit, jinak
        // by se spárovaly dvě různé bubliny, co náhodou začínají stejně.
        val prevEdge = block("IT WAS", 0.3f, 0.94f, 0.6f, 0.99f)
        val curEdge = block("IT WASN'T FAIR", 0.3f, 0f, 0.6f, 0.05f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(listOf(curEdge), result.kept)
        assertTrue(result.coverOnPrevious.isEmpty())
        assertTrue(result.coveredFromCurrent.isEmpty())
    }

    @Test
    fun `empty inputs pass through`() {
        assertTrue(dropSliceBoundaryDuplicates(emptyList(), emptyList()).kept.isEmpty())
        val cur = listOf(curFragment)
        assertEquals(cur, dropSliceBoundaryDuplicates(cur, emptyList()).kept)
    }

    // --- TR-1: dedup pásmo je sjednocené s merge pásmem (5 %, dřív 3 %) ---------

    @Test
    fun `TR-1 - fragment in the 3-5 percent top band is deduplicated`() {
        // CrossPageBubbleMerger spáruje fragmenty už od topF <= 0.05 a oběma zapíše
        // stejný mergedText. Dedup pásmo 0.03 takový pár propustilo a oba fragmenty
        // se vykreslily - duplicitní text na švu.
        val prevEdge = block("SPLIT BUBBLE TEXT", 0.3f, 0.90f, 0.7f, 0.985f)
        val curEdge = block("SPLIT BUBBLE TEXT", 0.3f, 0.042f, 0.7f, 0.10f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(1, result.coveredFromCurrent.size + result.coverOnPrevious.size)
    }

    @Test
    fun `TR-1 - prev fragment ending at 0_96 (3-5 percent band) is deduplicated`() {
        // Symetricky z druhé strany: prev fragment končí v zóně 0.95-0.97, kterou
        // staré dedup pásmo nevidělo, ale merger ano.
        val prevEdge = block("SPLIT BUBBLE TEXT", 0.3f, 0.91f, 0.7f, 0.962f)
        val curEdge = block("SPLIT BUBBLE TEXT", 0.3f, 0f, 0.7f, 0.06f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(1, result.coveredFromCurrent.size + result.coverOnPrevious.size)
    }

    @Test
    fun `TR-1 - fragment beyond the 5 percent band is still untouched`() {
        // Pásmo se rozšířilo na 5 %, ne donekonečna - blok za hranicí zůstává mimo.
        val prevEdge = block("SPLIT BUBBLE TEXT", 0.3f, 0.90f, 0.7f, 0.99f)
        val curEdge = block("SPLIT BUBBLE TEXT", 0.3f, 0.06f, 0.7f, 0.12f)
        val result = dropSliceBoundaryDuplicates(listOf(curEdge), listOf(prevEdge))
        assertEquals(listOf(curEdge), result.kept)
        assertTrue(result.coverOnPrevious.isEmpty())
    }

    // --- TR-2: další continuation fragmenty téže merge skupiny ----------------

    @Test
    fun `TR-2 - second continuation fragment is covered even after prev fragment lost`() {
        // Jedna bublina přešitá dvakrát: prev fragment + DVA continuation fragmenty
        // na téhle stránce, všem merger zapsal identický mergedText. První cur je
        // vyšší než prev -> prev prohraje (coverOnPrevious) a je "spotřebovaný".
        // Dřív pak druhý continuation nenašel protějšek a zůstal neoznačený ->
        // stejný překlad se vykreslil dvakrát na jedné stránce.
        val prevSmall = block("ONE SENTENCE SPLIT ACROSS THREE SLICES", 0.3f, 0.96f, 0.7f, 0.99f)
        val curTall = block("ONE SENTENCE SPLIT ACROSS THREE SLICES", 0.3f, 0f, 0.7f, 0.08f)
        val curSecond = block("ONE SENTENCE SPLIT ACROSS THREE SLICES", 0.3f, 0.03f, 0.7f, 0.045f)
        val result = dropSliceBoundaryDuplicates(listOf(curTall, curSecond), listOf(prevSmall))

        assertEquals(setOf(0), result.coverOnPrevious)
        // První (větší) cur vyhrál a nese překlad; druhý continuation je seamCover.
        assertFalse(result.kept[0].seamCover)
        assertTrue(result.kept[1].seamCover)
        assertEquals("", result.kept[1].displayText)
        assertEquals(listOf(curSecond), result.coveredFromCurrent)
    }

    @Test
    fun `TR-2 - two continuations both covered when prev fragment is taller`() {
        // Předchozí fragment vyhrál - vítěz se nespotřebovává, takže obě continuation
        // prohrály už normální cestou (regresní kotva: fix musí zachovat i tohle).
        val prevTall = block("ONE SENTENCE SPLIT ACROSS THREE SLICES", 0.3f, 0.90f, 0.7f, 0.99f)
        val curA = block("ONE SENTENCE SPLIT ACROSS THREE SLICES", 0.3f, 0f, 0.7f, 0.04f)
        val curB = block("ONE SENTENCE SPLIT ACROSS THREE SLICES", 0.3f, 0.02f, 0.7f, 0.05f)
        val result = dropSliceBoundaryDuplicates(listOf(curA, curB), listOf(prevTall))

        assertTrue(result.coverOnPrevious.isEmpty())
        assertTrue(result.kept.all { it.seamCover })
        assertEquals(2, result.coveredFromCurrent.size)
    }

    @Test
    fun `TR-2 - same text in a different column is not covered via consumed match`() {
        // Pojistka: fallback na "spotřebovaný" prev fragment nesmí pokrýt blok, co
        // se překrývá málo - jiný sloupec = pravděpodobně legit opakování textu.
        val prevSmall = block("REPEAT PHRASE HERE", 0.3f, 0.96f, 0.7f, 0.99f)
        val curTall = block("REPEAT PHRASE HERE", 0.3f, 0f, 0.7f, 0.08f)
        val curOtherColumn = block("REPEAT PHRASE HERE", 0.75f, 0.02f, 0.95f, 0.045f)
        val result = dropSliceBoundaryDuplicates(
            listOf(curTall, curOtherColumn), listOf(prevSmall),
        )
        assertFalse(result.kept[0].seamCover)
        assertFalse(result.kept[1].seamCover)
    }

    @Test
    fun `TR-2 - prefix-only match against a consumed prev fragment is not covered`() {
        // Oříznutý text sdílející jen prefix s PORAŽENÝM fragmentem se nesmí skrýt -
        // poražený se sám nevykresluje, takže duplicita nevzniká (strict seamKey).
        val prevSmall = block("AND NOW, EVEN THE RESIDUAL RECOIL OF YOUR ATTACKS", 0.3f, 0.96f, 0.7f, 0.99f)
        val curTall = block("AND NOW, EVEN THE RESIDUAL RECOIL OF YOUR ATTACKS", 0.3f, 0f, 0.7f, 0.08f)
        val curPrefixOnly = block("AND NOW, EVEN THE RES", 0.3f, 0.03f, 0.7f, 0.045f)
        val result = dropSliceBoundaryDuplicates(
            listOf(curTall, curPrefixOnly), listOf(prevSmall),
        )
        assertFalse(result.kept[0].seamCover)
        assertFalse(result.kept[1].seamCover)
    }
}

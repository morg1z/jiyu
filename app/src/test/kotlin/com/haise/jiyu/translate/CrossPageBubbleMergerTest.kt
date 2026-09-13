package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPageBubbleMergerTest {

    private fun bubble(text: String, left: Float, top: Float, right: Float, bottom: Float, isSfx: Boolean = false) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = left, topF = top, rightF = right, bottomF = bottom),
        sizeTag = SizeTag.MEDIUM,
        bubbleType = if (isSfx) BubbleType.SFX else BubbleType.SPEECH,
        isSfx = isSfx,
        lineCount = 1,
    )

    // ── findCrossPageMerges ──

    @Test
    fun `a fragment at the bottom edge merges with an overlapping fragment at the top edge of the next page`() {
        val bubblesByPage = mapOf(
            0 to listOf(bubble("WE NEED TO HURRY THE HARVEST", 0.1f, 0.90f, 0.9f, 0.99f)),
            1 to listOf(bubble("BEFORE WINTER COMES", 0.15f, 0.01f, 0.85f, 0.10f)),
        )
        val merges = findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 1))
        assertEquals(1, merges.size)
        assertEquals(BubbleLocation(0, 0), merges[0].first)
        assertEquals(listOf(BubbleLocation(1, 0)), merges[0].continuations)
        assertEquals("WE NEED TO HURRY THE HARVEST BEFORE WINTER COMES", merges[0].mergedText)
    }

    /**
     * Nahlaseno uzivatelem: pokracujici radek na zacatku dalsi stranky se sam rozpadl na DVA
     * OCR radky ("Thev're calling this" + "e an" - zbytek "one an", spatne rozpoznane) misto
     * jednoho. Puvodni kod bral jen jeden "nejlepsi" match a to druhe osirele "e an" se
     * prelozilo samo o sobe bez kontextu (vykreslilo se jako zmateny prekryv navic).
     */
    @Test
    fun `a continuation split into two OCR lines on the next page merges both`() {
        val bubblesByPage = mapOf(
            0 to listOf(bubble("L-Rank Extermination Mission.", 0.15f, 0.90f, 0.80f, 0.99f)),
            1 to listOf(
                bubble("Thev're calling this", 0.10f, 0.01f, 0.70f, 0.06f),
                bubble("e an", 0.72f, 0.01f, 0.85f, 0.06f),
            ),
        )
        val merges = findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 1))
        assertEquals(1, merges.size)
        assertEquals(BubbleLocation(0, 0), merges[0].first)
        assertEquals(listOf(BubbleLocation(1, 0), BubbleLocation(1, 1)), merges[0].continuations)
        assertEquals("L-Rank Extermination Mission. Thev're calling this e an", merges[0].mergedText)
    }

    @Test
    fun `a fragment nowhere near the page edge never merges`() {
        val bubblesByPage = mapOf(
            0 to listOf(bubble("normal bubble in the middle", 0.1f, 0.4f, 0.9f, 0.5f)),
            1 to listOf(bubble("another normal bubble", 0.15f, 0.01f, 0.85f, 0.10f)),
        )
        assertTrue(findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 1)).isEmpty())
    }

    @Test
    fun `fragments with little horizontal overlap never merge`() {
        val bubblesByPage = mapOf(
            0 to listOf(bubble("left side text", 0.05f, 0.90f, 0.30f, 0.99f)),
            1 to listOf(bubble("right side text", 0.70f, 0.01f, 0.95f, 0.10f)),
        )
        assertTrue(findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 1)).isEmpty())
    }

    @Test
    fun `sfx bubbles are never considered for cross-page merging`() {
        val bubblesByPage = mapOf(
            0 to listOf(bubble("BOOM", 0.1f, 0.90f, 0.9f, 0.99f, isSfx = true)),
            1 to listOf(bubble("continuation", 0.15f, 0.01f, 0.85f, 0.10f)),
        )
        assertTrue(findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 1)).isEmpty())
    }

    @Test
    fun `only adjacent pages in the page order are compared`() {
        val bubblesByPage = mapOf(
            0 to listOf(bubble("fragment on page 0", 0.1f, 0.90f, 0.9f, 0.99f)),
            2 to listOf(bubble("fragment on page 2", 0.15f, 0.01f, 0.85f, 0.10f)),
        )
        // Stranka 1 chybi (napr. prazdna/vynechana) - 0 a 2 NEJSOU sousedni v pageOrder.
        assertTrue(findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 2)).isEmpty())
    }

    @Test
    fun `the best-overlapping candidate wins and each next-page bubble is used at most once`() {
        val bubblesByPage = mapOf(
            0 to listOf(
                bubble("fragment A", 0.05f, 0.90f, 0.45f, 0.99f),
                bubble("fragment B", 0.55f, 0.90f, 0.95f, 0.99f),
            ),
            1 to listOf(bubble("only one continuation on next page", 0.50f, 0.01f, 0.90f, 0.10f)),
        )
        val merges = findCrossPageMerges(bubblesByPage, pageOrder = listOf(0, 1))
        // "fragment B" (0.55-0.95) prekryva vic s (0.50-0.90) nez "fragment A" (0.05-0.45, zadny prekryv).
        assertEquals(1, merges.size)
        assertEquals(BubbleLocation(0, 1), merges[0].first)
    }

    // ── concatenateDeduplicating ──

    @Test
    fun `non-overlapping text is joined with a space`() {
        assertEquals("Hello world", concatenateDeduplicating("Hello", "world"))
    }

    @Test
    fun `a repeated overlap at the boundary is not duplicated`() {
        // "won't last" je zachyceno OCR na OBOU polovinach (kousek presahu za hranici stranky) -
        // presah je 10 znaku, nad MIN_OVERLAP_TO_DEDUPE, takze se nezdvoji.
        assertEquals(
            "the food won't last much longer",
            concatenateDeduplicating("the food won't last", "won't last much longer"),
        )
    }

    @Test
    fun `an empty first string returns the second untouched`() {
        assertEquals("second", concatenateDeduplicating("", "second"))
    }

    @Test
    fun `an empty second string returns the first untouched`() {
        assertEquals("first", concatenateDeduplicating("first", ""))
    }

    @Test
    fun `a very short accidental overlap below the minimum length is not treated as a real overlap`() {
        // "a" na konci prvniho a zacatku druheho je jen nahoda, ne skutecny presah OCR.
        assertEquals("banana apple", concatenateDeduplicating("banana", "apple"))
    }

    // ── applyCrossPageMerges ──

    @Test
    fun `both fragments of a merge get the same merged text, other bubbles are untouched`() {
        val bubblesByPage = mapOf(
            0 to listOf(
                bubble("WE NEED TO HURRY", 0.1f, 0.90f, 0.9f, 0.99f),
                bubble("unrelated bubble", 0.1f, 0.1f, 0.9f, 0.2f),
            ),
            1 to listOf(bubble("THE HARVEST", 0.15f, 0.01f, 0.85f, 0.10f)),
        )
        val merges = listOf(CrossPageMerge(BubbleLocation(0, 0), listOf(BubbleLocation(1, 0)), "WE NEED TO HURRY THE HARVEST"))

        val result = applyCrossPageMerges(bubblesByPage, merges)

        assertEquals("WE NEED TO HURRY THE HARVEST", result.getValue(0)[0].raw.text)
        assertEquals("WE NEED TO HURRY THE HARVEST", result.getValue(1)[0].raw.text)
        assertEquals("unrelated bubble", result.getValue(0)[1].raw.text)
    }

    @Test
    fun `no merges returns the exact same map instance`() {
        val bubblesByPage = mapOf(0 to listOf(bubble("A", 0f, 0f, 0.1f, 0.1f)))
        assertSame(bubblesByPage, applyCrossPageMerges(bubblesByPage, emptyList()))
    }
}

package com.haise.jiyu.ui.reader

import com.haise.jiyu.translate.TranslatedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ploché projekce nekonečného čtení ve stránkovaných režimech - překlad mezi
 * plochým indexem stránky (přes všechny napojené segmenty) a dvojicí
 * (chapterId, lokální index). Viz ReaderViewModel.onPagedFlatPageChanged a
 * ReaderContent (useInfinitePaged).
 */
class PagedInfiniteProjectionTest {

    private fun seg(id: String, pages: Int) =
        WebtoonSegment(chapterId = id, chapterName = id, pages = (1..pages).map { "p$it" })

    private val segments = listOf(seg("ch1", 3), seg("ch2", 2), seg("ch3", 4))

    // ── segmentStartFlatIndex ────────────────────────────────────────────────

    @Test
    fun `segment start offsets accumulate page counts`() {
        assertEquals(0, segmentStartFlatIndex(segments, "ch1"))
        assertEquals(3, segmentStartFlatIndex(segments, "ch2"))
        assertEquals(5, segmentStartFlatIndex(segments, "ch3"))
    }

    @Test
    fun `segment start returns -1 for unknown chapter`() {
        assertEquals(-1, segmentStartFlatIndex(segments, "chX"))
        assertEquals(-1, segmentStartFlatIndex(emptyList(), "ch1"))
    }

    // ── locatePagedLocal ─────────────────────────────────────────────────────

    @Test
    fun `locate maps flat index to chapter and local index`() {
        assertEquals("ch1" to 0, locatePagedLocal(segments, 0))
        assertEquals("ch1" to 2, locatePagedLocal(segments, 2))
        assertEquals("ch2" to 0, locatePagedLocal(segments, 3))
        assertEquals("ch3" to 0, locatePagedLocal(segments, 5))
        assertEquals("ch3" to 3, locatePagedLocal(segments, 8))
    }

    @Test
    fun `locate returns null outside the flattened range`() {
        assertNull(locatePagedLocal(segments, 9))
        assertNull(locatePagedLocal(segments, -1))
        assertNull(locatePagedLocal(emptyList(), 0))
    }

    // ── flattenTranslatedPages ───────────────────────────────────────────────

    private fun block(text: String) = TranslatedBlock(
        originalText = text, translatedText = text,
        leftF = 0f, topF = 0f, rightF = 1f, bottomF = 1f,
    )

    @Test
    fun `translations are offset per segment so keys never collide`() {
        val flat = flattenTranslatedPages(
            mapOf(
                "ch1" to mapOf(0 to listOf(block("a")), 2 to listOf(block("b"))),
                "ch3" to mapOf(1 to listOf(block("c"))),
            ),
            segments,
        )
        assertEquals(setOf(0, 2, 6), flat.keys)   // ch3@1 -> 5 + 1 = 6
        assertEquals("c", flat[6]!!.single().translatedText)
    }

    @Test
    fun `chapters without translations contribute nothing`() {
        assertEquals(emptyMap<Int, List<TranslatedBlock>>(), flattenTranslatedPages(emptyMap(), segments))
    }

    // ── flattenFlippedKeys ───────────────────────────────────────────────────

    @Test
    fun `flipped bubble keys remap to flat page indices`() {
        val flat = flattenFlippedKeys(setOf("ch1:1:0", "ch2:0:3", "ch3:3:1"), segments)
        assertEquals(setOf("1:0", "3:3", "8:1"), flat)   // ch2@0->3, ch3@3->8
    }

    @Test
    fun `keys of chapters outside the segment list are dropped`() {
        val flat = flattenFlippedKeys(setOf("chX:5:0", "ch1:0:0"), segments)
        assertEquals(setOf("0:0"), flat)
    }

    @Test
    fun `malformed keys are skipped instead of crashing`() {
        val flat = flattenFlippedKeys(setOf("ch1:notanumber:0", "ch1:2:7"), segments)
        assertEquals(setOf("2:7"), flat)
    }
}

package com.haise.jiyu.ui.reader

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

}

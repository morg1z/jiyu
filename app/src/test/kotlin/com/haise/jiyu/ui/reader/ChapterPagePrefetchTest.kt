package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterPagePrefetchTest {

    @Test
    fun `window in the middle of the list returns count indices ahead`() {
        assertEquals(
            listOf(2, 3, 4, 5),
            computePrefetchIndices(fromIndex = 2, pageCount = 20, alreadyPrefetched = emptySet(), count = 4),
        )
    }

    @Test
    fun `window near the end is truncated to page count`() {
        assertEquals(
            listOf(8, 9),
            computePrefetchIndices(fromIndex = 8, pageCount = 10, alreadyPrefetched = emptySet(), count = 4),
        )
    }

    @Test
    fun `already prefetched indices are skipped`() {
        assertEquals(
            listOf(3, 5),
            computePrefetchIndices(fromIndex = 2, pageCount = 20, alreadyPrefetched = setOf(2, 4), count = 4),
        )
    }

    @Test
    fun `negative fromIndex returns empty list`() {
        assertEquals(
            emptyList<Int>(),
            computePrefetchIndices(fromIndex = -1, pageCount = 10, alreadyPrefetched = emptySet()),
        )
    }

    @Test
    fun `fromIndex at or past the end of the list returns empty list`() {
        assertEquals(
            emptyList<Int>(),
            computePrefetchIndices(fromIndex = 10, pageCount = 10, alreadyPrefetched = emptySet()),
        )
    }

    @Test
    fun `zero page count returns empty list regardless of fromIndex`() {
        assertEquals(
            emptyList<Int>(),
            computePrefetchIndices(fromIndex = 0, pageCount = 0, alreadyPrefetched = emptySet()),
        )
    }

    @Test
    fun `default count is PREFETCH_WINDOW`() {
        assertEquals(4, PREFETCH_WINDOW)
        assertEquals(
            listOf(0, 1, 2, 3),
            computePrefetchIndices(fromIndex = 0, pageCount = 100, alreadyPrefetched = emptySet()),
        )
    }
}

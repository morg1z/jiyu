package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterPagePrefetchTest {

    @Test
    fun `prefetchOrder zacina na pozici ctenare a jede dopredu`() {
        assertEquals(
            listOf(10, 11, 12, 13, 14, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
            prefetchOrder(pageCount = 15, centerIndex = 10),
        )
    }

    @Test
    fun `prefetchOrder na zacatku kapitoly je 0 az N`() {
        assertEquals(listOf(0, 1, 2, 3), prefetchOrder(pageCount = 4, centerIndex = 0))
    }

    @Test
    fun `prefetchOrder na konci kapitoly jde jen zpetne`() {
        assertEquals(listOf(4, 3, 2, 1, 0), prefetchOrder(pageCount = 5, centerIndex = 4))
    }

    @Test
    fun `prefetchOrder oreze centerIndex mimo rozsah`() {
        assertEquals(listOf(2, 1, 0), prefetchOrder(pageCount = 3, centerIndex = 99))
    }

    @Test
    fun `prefetchOrder prazdna kapitola`() {
        assertEquals(emptyList<Int>(), prefetchOrder(pageCount = 0, centerIndex = 0))
    }
}

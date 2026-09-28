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

    @Test
    fun `nextPrefetchIndex bere nejblizsi dopredu od centra`() {
        val remaining = (0 until 15).toMutableSet()
        // Simulace workera: opakovane pickuje, musi jit 10,11,12... jako prefetchOrder.
        val order = generateSequence {
            nextPrefetchIndex(remaining, center = 10, pageCount = 15)?.also { remaining.remove(it) }
        }.toList()
        assertEquals(prefetchOrder(15, 10), order)
    }

    @Test
    fun `nextPrefetchIndex po skoku ctenare preorientuje frontu`() {
        // Kapitola se otevřela na 10 a 10-13 uz se stahly; ctenar skoci scrubberem na 50.
        val remaining = (0 until 60).toMutableSet().apply { removeAll(10..13) }
        assertEquals(50, nextPrefetchIndex(remaining, center = 50, pageCount = 60))
        assertEquals(51, nextPrefetchIndex(remaining - 50, center = 50, pageCount = 60))
    }

    @Test
    fun `nextPrefetchIndex zpetne stranky jdou az po doprednych a od nejblizsi`() {
        val remaining = setOf(2, 0, 8, 9)
        // center=5, N=10: dopredu 8,9 (8 driv), pak zpetne 2,0 (2 je bliz centru).
        assertEquals(8, nextPrefetchIndex(remaining, center = 5, pageCount = 10))
        assertEquals(9, nextPrefetchIndex(remaining - 8, center = 5, pageCount = 10))
        assertEquals(2, nextPrefetchIndex(remaining - 8 - 9, center = 5, pageCount = 10))
        assertEquals(0, nextPrefetchIndex(remaining - 8 - 9 - 2, center = 5, pageCount = 10))
    }

    @Test
    fun `nextPrefetchIndex prazdna fronta vraci null`() {
        assertEquals(null, nextPrefetchIndex(emptySet(), center = 3, pageCount = 10))
        assertEquals(null, nextPrefetchIndex(setOf(0), center = 0, pageCount = 0))
    }
}

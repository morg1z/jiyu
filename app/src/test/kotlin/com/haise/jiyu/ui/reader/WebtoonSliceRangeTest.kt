package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistá matematika viditelných řezů řezané webtoon stránky - viz [visibleSliceRange]
 * a komentář u [TiledWebtoonPage] (ruční virtualizace řezů).
 */
class WebtoonSliceRangeTest {

    private fun range(top: Float, pageH: Float, viewH: Float, slices: Int): IntRange =
        visibleSliceRange(top, pageH, viewH, slices)

    @Test
    fun `page starting at top shows first slices plus buffer`() {
        // Stránka 15 řezů, horní okraj na vrchu obrazovky, viewport 2000 z 8000 px.
        // Viditelné řezy 0..3, +1 rezerva -> 0..4.
        assertEquals(0..4, range(0f, 8000f, 2000f, 15))
    }

    @Test
    fun `scrolled mid-page gives middle slices with buffer both ways`() {
        // Horní okraj stránky 4000 px nad obrazovkou (top=-4000, půlka stránky).
        // Viditelné řezy ~7..11, s rezervou 6..12.
        assertEquals(6..12, range(-4000f, 8000f, 2000f, 15))
    }

    @Test
    fun `page fully above viewport decodes nothing`() {
        // Stránka 10 000 px nad vršpem - viewport sahá do top=10_000..12_000 px stránky,
        // ta má ale jen 8000 -> celý rozsah za koncem -> prázdný.
        assertTrue(range(-10_000f, 8000f, 2000f, 15).isEmpty())
    }

    @Test
    fun `page fully below viewport decodes first slice as prefetch`() {
        // Stránka začíná pod spodkem obrazovky - viditelný je nic, ale rezervní řez 0
        // se stále dekóduje (LazyColumn předkompozice o kus pod okraj).
        assertEquals(0..0, range(3000f, 8000f, 2000f, 15))
    }

    @Test
    fun `degenerate inputs return empty`() {
        assertTrue(range(0f, 0f, 2000f, 15).isEmpty())      // nulová výška stránky
        assertTrue(range(0f, 8000f, 2000f, 0).isEmpty())    // žádné řezy
        assertTrue(range(0f, -1f, 2000f, 15).isEmpty())     // záporná výška
    }

    @Test
    fun `single slice page is always in range when visible`() {
        assertEquals(0..0, range(0f, 4000f, 2000f, 1))
        assertEquals(0..0, range(-1000f, 4000f, 2000f, 1))
    }
}

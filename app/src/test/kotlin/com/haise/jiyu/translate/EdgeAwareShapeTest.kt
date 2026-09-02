package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAwareShapeTest {

    private class TestPixelSource(
        private val width: Int,
        private val height: Int,
        private val pixels: IntArray,
    ) : PixelSource {
        override fun colorAt(x: Int, y: Int): Int {
            if (x !in 0 until width || y !in 0 until height) return 0
            return pixels[y * width + x]
        }
    }

    @Test
    fun `edge aware shape finds bubble boundary from OCR box`() {
        val w = 200
        val h = 200
        val pixels = IntArray(w * h) { 0xFFFF0000.toInt() } // red background
        // white bubble at 50..149 x 50..119
        for (y in 50..119) {
            for (x in 50..149) {
                pixels[y * w + x] = 0xFFFFFFFF.toInt()
            }
        }
        val source = TestPixelSource(w, h, pixels)

        val shape = BubbleShapeDetector.edgeAwareShape(
            source = source,
            width = w,
            height = h,
            leftF = 0.40f,
            topF = 0.35f,
            rightF = 0.60f,
            bottomF = 0.50f,
            bgColorArgb = 0xFFFFFFFF.toInt(),
            colorDistanceThreshold = 40,
        )

        assertNotNull(shape)
        val first = shape!!.first()
        val last = shape.last()

        assertEquals(50f / h, first.yF, 0.02f)
        assertEquals(119f / h, last.yF, 0.02f)
        assertEquals(50f / w, first.leftF, 0.02f)
        assertEquals(149f / w, first.rightF, 0.02f)
        assertTrue(shape.all { it.leftF == first.leftF && it.rightF == first.rightF })
    }

    @Test
    fun `edge aware shape returns null when OCR box sits on non-uniform background`() {
        val w = 100
        val h = 100
        // noisy background, no bubble
        val pixels = IntArray(w * h) { idx ->
            if (idx % 2 == 0) 0xFF00FF00.toInt() else 0xFF0000FF.toInt()
        }
        val source = TestPixelSource(w, h, pixels)

        val shape = BubbleShapeDetector.edgeAwareShape(
            source = source,
            width = w,
            height = h,
            leftF = 0.4f,
            topF = 0.4f,
            rightF = 0.6f,
            bottomF = 0.6f,
            bgColorArgb = 0xFFFFFFFF.toInt(),
            colorDistanceThreshold = 40,
        )

        assertEquals(null, shape)
    }

    /**
     * Reprodukce nahlášeného bugu: titulková/vyprávěcí replika bez bubliny, položená přímo
     * v otevřeném bílém prostoru panelu (žádný obrys, žádná kresba blízko textu). Otevřená
     * plocha kolem textu je uzavřená až okrajem panelu daleko od textu (360x260 px), takže
     * projde i vnitřním plošným stropem [BubbleShapeDetector] (0,25 stránky) - jenže proti OCR
     * textu (20x10 px) je to poměr 468x, hluboko za [BubbleShapeDetector]'s MAX_SHAPE_TO_TEXT_AREA_RATIO
     * (45x) změřeným na skutečných uniklých výplních. Bez kontroly poměru na tomhle druhém
     * pokusu (paprskový fallback volá vnitřní detectShape s textAreaPx = 0, tedy s kontrolou
     * VYPNUTOU) se vrátí obrovský obdélník - přesně to, co uživatel nahlásil jako "zamazání
     * přes půl bílého pozadí" a zakrytí kresby daleko od původního textu.
     */
    @Test
    fun `edge aware shape rejects an open background far larger than its own text`() {
        val w = 400
        val h = 1200
        val pixels = IntArray(w * h) { 0xFF000000.toInt() } // black artwork everywhere
        // Open uniform panel background - bounded only by the page edges, far from the text.
        for (y in 500..759) {
            for (x in 20..379) {
                pixels[y * w + x] = 0xFFFFFFFF.toInt()
            }
        }
        val source = TestPixelSource(w, h, pixels)

        val shape = BubbleShapeDetector.edgeAwareShape(
            source = source,
            width = w,
            height = h,
            leftF = 190f / w,
            topF = 620f / h,
            rightF = 209f / w,
            bottomF = 629f / h,
            bgColorArgb = 0xFFFFFFFF.toInt(),
            colorDistanceThreshold = 40,
        )

        assertNull("obrys 468x vetsi nez vlastni text neni bublina, ale otevrene pozadi", shape)
    }
}

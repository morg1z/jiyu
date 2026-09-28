package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleRenderStyleTest {

    // ── snapBubbleBg ────────────────────────────────────────────────────────

    @Test
    fun `near white is snapped to pure white keeping alpha`() {
        val nearWhite = 0xFFF2F2F2.toInt()
        assertEquals(0xFFFFFFFF.toInt(), snapBubbleBg(nearWhite))
    }

    @Test
    fun `near black is snapped to pure black keeping alpha`() {
        val nearBlack = 0xFF0A0A0A.toInt()
        assertEquals(0xFF000000.toInt(), snapBubbleBg(nearBlack))
    }

    @Test
    fun `mid-tone colored bubble is not snapped`() {
        val orange = 0xFFE8A030.toInt()
        assertEquals(orange, snapBubbleBg(orange))
    }

    @Test
    fun `alpha channel is preserved when snapping`() {
        val translucentNearWhite = 0x80F5F5F5.toInt()
        assertEquals(0x80FFFFFF.toInt(), snapBubbleBg(translucentNearWhite))
    }

    // ── averageArgb ─────────────────────────────────────────────────────────

    @Test
    fun `averages each color channel independently`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertEquals(0xFF7F7F7F.toInt(), averageArgb(white, black))
    }

    @Test
    fun `alpha channel comes from the first color`() {
        val a = 0x80FF0000.toInt()
        val b = 0xFF0000FF.toInt()
        // alpha z a (0x80); r=(0xFF+0x00)/2=0x7F; g=(0x00+0x00)/2=0x00; b=(0x00+0xFF)/2=0x7F
        assertEquals(0x807F007F.toInt(), averageArgb(a, b))
    }

    @Test
    fun `averaging identical colors returns the same color`() {
        val orange = 0xFFE8A030.toInt()
        assertEquals(orange, averageArgb(orange, orange))
    }

    // ── patchMeanArgb ───────────────────────────────────────────────────────

    private fun solid(color: Int): (Int, Int) -> Int = { _, _ -> color }

    @Test
    fun `light patch returns light color so text picks black`() {
        val mean = patchMeanArgb(solid(0xFFF5F5F5.toInt()), width = 100, height = 100)!!
        // prumerna svetla barva -> luminance > 0.5 -> renderer zvoli cerne pismo
        assertEquals(0xFFF5F5F5.toInt(), mean)
    }

    @Test
    fun `dark patch returns dark color so text picks white`() {
        val mean = patchMeanArgb(solid(0xFF101018.toInt()), width = 100, height = 100)!!
        assertEquals(0xFF101018.toInt(), mean)
    }

    @Test
    fun `only the center region counts - dark corners do not pollute the mean`() {
        // Simulace nahlášené chyby: záplata je ve středu světlá (bublina), ale její okraje
        // zasahují do tmavé kresby - barva textu se má řídit středem (oblast 20-80 %),
        // kam se text sází, ne průměrem celé plochy.
        val color = { x: Int, y: Int ->
            if (x < 20 || x >= 80 || y < 20 || y >= 80) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val mean = patchMeanArgb(color, width = 100, height = 100)!!
        assertEquals(0xFFFFFFFF.toInt(), mean)
    }

    @Test
    fun `mixed center averages the channels`() {
        val color = { x: Int, _: Int -> if (x < 50) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val mean = patchMeanArgb(color, width = 100, height = 100)!!
        assertEquals(0xFF7F7F7F.toInt(), mean)
    }

    @Test
    fun `degenerate patch returns null so caller can fall back to the ring sample`() {
        assertEquals(null, patchMeanArgb(solid(0xFFFFFFFF.toInt()), width = 0, height = 0))
        assertEquals(null, patchMeanArgb(solid(0xFFFFFFFF.toInt()), width = 1, height = 1))
    }

    // ── matchOriginalCase ───────────────────────────────────────────────────

    @Test
    fun `uppercase latin original uppercases the translation`() {
        assertEquals("PO ZAPLACENÍ VODY", matchOriginalCase("Po zaplacení vody", "AFTER PAYING THE WATER BILL"))
    }

    @Test
    fun `lowercase latin original keeps translation casing`() {
        assertEquals("Ahoj kámo", matchOriginalCase("Ahoj kámo", "hey there buddy"))
    }

    @Test
    fun `cjk original defaults to uppercase (comic lettering convention)`() {
        assertEquals("TO JE JASNÉ.", matchOriginalCase("To je jasné.", "내일 어둠 탐사"))
    }

    @Test
    fun `czech diacritics uppercase correctly`() {
        assertEquals("ŘÍKÁŠ ŽE?", matchOriginalCase("Říkáš že?", "YOU SAY?"))
    }

    @Test
    fun `soft hyphens in display text survive uppercasing`() {
        val withSoftHyphen = "roz­dělit"
        assertEquals("ROZ­DĚLIT", matchOriginalCase(withSoftHyphen, "SPLIT"))
    }
}

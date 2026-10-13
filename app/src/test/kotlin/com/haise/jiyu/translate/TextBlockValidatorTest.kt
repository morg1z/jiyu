package com.haise.jiyu.translate

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isPlausibleTextBlock] - strukturální filtr "je OCR blok opravdu text".
 * Reprodukuje třídy halucinací ML Kit detekce na kresbě (audit Vagabond p17):
 * šrafování, rastrová pole, náplně kresby - versus skutečný lettering.
 */
class TextBlockValidatorTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val white = argb(255, 255, 255)
    private val black = argb(20, 20, 20)

    private fun sourceOf(px: IntArray, w: Int) = PixelSource { x, y -> px[y * w + x] }

    /** Celostránkový box = celá syntetická scéna. */
    private fun fullBox(w: Int, h: Int) =
        floatArrayOf(0f, 0f, 1f, 1f).let { it }

    private fun evaluate(px: IntArray, w: Int, h: Int, l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f) =
        isPlausibleTextBlock(sourceOf(px, w), w, h, l, t, r, b)

    /** Nápověda: řada N glyph-podobných černých obdélníků na bílém. */
    private fun glyphRowScene(w: Int, h: Int, glyphs: Int, gw: Int = 6, gh: Int = 12): IntArray {
        val px = IntArray(w * h) { white }
        var x = 4
        repeat(glyphs) {
            for (y in (h - gh) / 2 until (h - gh) / 2 + gh) {
                for (gx in x until x + gw) if (gx < w - 2) px[y * w + gx] = black
            }
            x += gw + 4
        }
        return px
    }

    @Test
    fun `real lettering - row of glyph blobs is accepted`() {
        val w = 90; val h = 40
        val px = glyphRowScene(w, h, glyphs = 8)
        assertTrue("řada glyphů má projít", evaluate(px, w, h))
    }

    @Test
    fun `short legit text like exclamation is accepted`() {
        val w = 40; val h = 40
        // "!" = tah + tečka (2 komponenty), slušné pokrytí tight boxu.
        val px = IntArray(w * h) { white }
        for (y in 8 until 26) for (x in 16 until 22) px[y * w + x] = black
        for (y in 30 until 36) for (x in 16 until 22) px[y * w + x] = black
        assertTrue("'!' má projít", evaluate(px, w, h))
    }

    @Test
    fun `parallel hatching lines are rejected`() {
        val w = 90; val h = 50
        // 7 vodorovných 1px čar přes skoro celý box = meč/šrafování (p17 scénář).
        val px = IntArray(w * h) { white }
        for (y in 6 until h step 6) {
            for (x in 2 until w - 2) px[y * w + x] = black
        }
        val reason = mutableListOf<String>()
        val ok = isPlausibleTextBlock(sourceOf(px, w), w, h, 0f, 0f, 1f, 1f) { reason += it }
        assertTrue("šrafování nemá projít (reason=$reason)", !ok)
    }

    @Test
    fun `dense screentone field is rejected`() {
        val w = 80; val h = 60
        // Šachovnice 1px = extrémní textura - maska nasaje skoro všechno.
        val px = IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) black else white }
        assertTrue("pole textury nemá projít", !evaluate(px, w, h))
    }

    @Test
    fun `single large art blob passes - edge-only mask is harmless`() {
        val w = 80; val h = 60
        // Jediný velký černý blob (náplň kresby / velký glyph): markTextPixels
        // označí jen okraje, maska je malá, patch škodí minimálně a velké SFX
        // glyphy jsou legitimní - filtr ho ZÁMĚRNĚ propustí.
        val px = IntArray(w * h) { white }
        for (y in 10 until 50) for (x in 20 until 60) px[y * w + x] = black
        assertTrue("velký blob má projít (konzervativita)", evaluate(px, w, h))
    }

    @Test
    fun `near-empty box with specks is rejected`() {
        val w = 80; val h = 60
        val px = IntArray(w * h) { white }
        for (y in 10 until 13) for (x in 10 until 13) px[y * w + x] = black
        assertTrue("pár šumových fleků nemá projít", !evaluate(px, w, h))
    }

    @Test
    fun `tiny box is not evaluated`() {
        val w = 80; val h = 60
        val px = IntArray(w * h) { white }
        // Box 10x10 px = pod MIN_BOX_DIM -> propustit bez analýzy.
        val ok = isPlausibleTextBlock(
            sourceOf(px, w), w, h,
            0.1f, 0.1f, 0.1f + 10f / w, 0.1f + 10f / h,
        )
        assertTrue("malý box má projít bez analýzy", ok)
    }

    @Test
    fun `text with art edge inside the box is accepted`() {
        val w = 100; val h = 50
        val px = glyphRowScene(w, h, glyphs = 8)
        // Okraj kresby na straně boxu - diagonální tah.
        for (y in 0 until h) {
            val x = 90 - y / 2
            if (x in 0 until w) px[y * w + x] = black
        }
        assertTrue("text + kus kresby má projít (konzervativita)", evaluate(px, w, h))
    }
}

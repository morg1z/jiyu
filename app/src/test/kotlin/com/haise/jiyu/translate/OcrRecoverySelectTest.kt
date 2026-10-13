package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [selectRecoveryCrops] - výběr YOLO boxů pro recovery přečtení (Fáze 2A):
 * nepokryté textové regiony = lettering, který ML Kit přeskočil.
 */
class OcrRecoverySelectTest {

    private fun block(l: Float, t: Float, r: Float, b: Float) =
        RawTextBlock(text = "x", leftF = l, topF = t, rightF = r, bottomF = b)

    private fun yolo(l: Float, t: Float, r: Float, b: Float, classId: Int = 0, score: Float = 0.9f) =
        DetectedBubbleBox(leftF = l, topF = t, rightF = r, bottomF = b, classId = classId, score = score)

    @Test
    fun `uncovered text_free box is selected`() {
        // OCR našel jen jednu bublinu; druhá YOLO detekce (free text) zůstala holá.
        val existing = listOf(block(0.10f, 0.10f, 0.40f, 0.20f))
        val boxes = listOf(
            yolo(0.08f, 0.08f, 0.42f, 0.22f),          // pokrytá existujícím blokem
            yolo(0.50f, 0.60f, 0.80f, 0.68f, classId = 1), // volný text bez bloku
        )
        val sel = selectRecoveryCrops(existing, boxes)
        assertEquals(1, sel.size)
        assertEquals(1, sel[0].classId)
    }

    @Test
    fun `covered boxes are skipped`() {
        val existing = listOf(block(0.10f, 0.10f, 0.40f, 0.20f))
        val boxes = listOf(yolo(0.08f, 0.08f, 0.42f, 0.22f))
        assertTrue(selectRecoveryCrops(existing, boxes).isEmpty())
    }

    @Test
    fun `partially overlapping box is still uncovered when small overlap`() {
        // Blok jen zašpiňuje roh YOLO boxu (< coveredOverlap) -> box se přečte.
        val existing = listOf(block(0.50f, 0.60f, 0.56f, 0.64f))
        val boxes = listOf(yolo(0.50f, 0.60f, 0.80f, 0.68f))
        val sel = selectRecoveryCrops(existing, boxes, coveredOverlap = 0.3f)
        // inter = 0.06*0.04 = 0.0024; menší plocha = block 0.0024 -> overlap 1.0? ->
        // containment měří menší z dvou - block leží celý v boxu -> overlap=1 -> covered.
        // Skutečně menší overlap potřebuje částečný průnik jen:
        assertTrue(sel.isEmpty()) // block uvnitř boxu = covered
    }

    @Test
    fun `giant box over max area fraction is skipped`() {
        val existing = emptyList<RawTextBlock>()
        val boxes = listOf(yolo(0.1f, 0.1f, 0.9f, 0.8f)) // 56% stránky = panel, ne lettering
        assertTrue(selectRecoveryCrops(existing, boxes).isEmpty())
    }

    @Test
    fun `empty page recovers all reasonable boxes`() {
        // ocr_no_text scénář: stránka bez bloků -> všechny YOLO detekce jdou na recovery.
        val boxes = listOf(
            yolo(0.1f, 0.1f, 0.3f, 0.2f),
            yolo(0.5f, 0.3f, 0.8f, 0.4f, classId = 1),
            yolo(0.2f, 0.6f, 0.4f, 0.65f),
        )
        val sel = selectRecoveryCrops(emptyList(), boxes)
        assertEquals(3, sel.size)
    }

    @Test
    fun `crop count is capped`() {
        val boxes = (0 until 12).map { i -> yolo(0.02f + i * 0.08f, 0.1f, 0.06f + i * 0.08f, 0.15f) }
        val sel = selectRecoveryCrops(emptyList(), boxes, maxCrops = 5)
        assertEquals(5, sel.size)
    }
}

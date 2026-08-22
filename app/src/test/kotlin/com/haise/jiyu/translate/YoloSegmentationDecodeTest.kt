package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** Čisté JVM testy dekódování YOLOv8-SEG výstupu - viz doc komentář v [YoloSegmentationDecode.kt]. */
class YoloSegmentationDecodeTest {

    private fun sigmoid(x: Float) = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    private fun buildOutput(numClasses: Int, numMaskCoeffs: Int, anchors: List<List<Float>>): FloatArray {
        val numAnchors = anchors.size
        val channels = 4 + numClasses + numMaskCoeffs
        val out = FloatArray(channels * numAnchors)
        for (c in 0 until channels) {
            for (a in 0 until numAnchors) {
                out[c * numAnchors + a] = anchors[a][c]
            }
        }
        return out
    }

    // ── decodeYoloSegOutput ──

    @Test
    fun `decodes box and mask coefficients for an anchor above threshold`() {
        // cx,cy,w,h, class0, coeff0, coeff1
        val output = buildOutput(1, 2, listOf(listOf(100f, 100f, 40f, 20f, 0.9f, 1.5f, -2.5f)))
        val detections = decodeYoloSegOutput(output, numAnchors = 1, numClasses = 1, numMaskCoeffs = 2, confThreshold = 0.25f)

        assertEquals(1, detections.size)
        val d = detections[0]
        assertEquals(0, d.box.classId)
        assertEquals(0.9f, d.box.score, 0.001f)
        assertEquals(80f, d.box.leftPx, 0.001f)
        assertEquals(listOf(1.5f, -2.5f), d.maskCoeffs.toList())
    }

    @Test
    fun `drops anchors below the confidence threshold`() {
        val output = buildOutput(1, 2, listOf(listOf(100f, 100f, 40f, 20f, 0.1f, 1f, 1f)))
        val detections = decodeYoloSegOutput(output, numAnchors = 1, numClasses = 1, numMaskCoeffs = 2, confThreshold = 0.25f)
        assertTrue(detections.isEmpty())
    }

    // ── nonMaxSuppressionSeg ──

    @Test
    fun `keeps only the highest scoring overlapping detection`() {
        val a = RawSegDetection(RawBoxDetection(0f, 0f, 100f, 100f, 0, 0.9f), floatArrayOf(1f))
        val b = RawSegDetection(RawBoxDetection(5f, 5f, 105f, 105f, 0, 0.6f), floatArrayOf(2f))
        val kept = nonMaxSuppressionSeg(listOf(a, b), iouThreshold = 0.45f)
        assertEquals(1, kept.size)
        assertEquals(0.9f, kept[0].box.score, 0.001f)
    }

    // ── reconstructMask ──

    @Test
    fun `a single positive coefficient reproduces the matching prototype plane`() {
        // 1 koeficient, jeden 2x2 prototyp s hodnotami, ktere po sigmoidu prekroci/nedosahnou prahu.
        val protoH = 2
        val protoW = 2
        // sigmoid(3) ~ 0.95 (nad prahem), sigmoid(-3) ~ 0.047 (pod prahem)
        val prototypes = floatArrayOf(3f, -3f, 3f, -3f)
        val mask = reconstructMask(maskCoeffs = floatArrayOf(1f), prototypes = prototypes, protoH = protoH, protoW = protoW)

        assertEquals(listOf(true, false, true, false), mask.toList())
    }

    @Test
    fun `combines multiple coefficients linearly before the sigmoid`() {
        // 2 koeficienty; soucet ma prekrocit 0 jen na prvnim pixelu.
        val prototypes = floatArrayOf(
            2f, -2f, // kanal 0 (2 pixely)
            2f, 1f, // kanal 1 (2 pixely)
        )
        // pixel0: 1*2 + 1*2 = 4 (sigmoid(4) > 0.5) ; pixel1: 1*(-2) + 1*1 = -1 (sigmoid(-1) < 0.5)
        val mask = reconstructMask(maskCoeffs = floatArrayOf(1f, 1f), prototypes = prototypes, protoH = 1, protoW = 2)
        assertEquals(listOf(true, false), mask.toList())
    }

    // ── maskToShapePoints ──

    private fun square(size: Int, x0: Int, y0: Int, x1: Int, y1: Int): BooleanArray {
        val mask = BooleanArray(size * size)
        for (y in y0 until y1) for (x in x0 until x1) mask[y * size + x] = true
        return mask
    }

    @Test
    fun `an empty mask returns null`() {
        val mask = BooleanArray(16) // 4x4, samé false
        val result = maskToShapePoints(
            mask, maskW = 4, maskH = 4, protoScale = 4,
            letterbox = LetterboxParams(scale = 1f, padX = 0f, padY = 0f),
            srcWidth = 16, srcHeight = 16,
        )
        assertNull(result)
    }

    @Test
    fun `a filled square maps to consistent left right bounds across sampled rows`() {
        // Ctverec 10x10 (indexy 2..7) v masce 10x10, protoScale 1, zadny letterbox - jednoduchy 1:1 preklad.
        val mask = square(size = 10, x0 = 2, y0 = 2, x1 = 8, y1 = 8)
        val result = maskToShapePoints(
            mask, maskW = 10, maskH = 10, protoScale = 1,
            letterbox = LetterboxParams(scale = 1f, padX = 0f, padY = 0f),
            srcWidth = 10, srcHeight = 10,
            sampleCount = 6,
        )
        assertTrue(result != null)
        result!!.forEach { p ->
            assertEquals(0.2f, p.leftF, 0.001f) // x=2 / 10
            assertEquals(0.8f, p.rightF, 0.001f) // (x=7+1) / 10
        }
    }

    @Test
    fun `protoScale and letterbox padding are both applied when mapping back to the page`() {
        // Maska 2x2 (protoScale 4 -> vstup 8x8), letterbox s paddingem 4px a scale 0.5 na strance 8x16.
        val mask = booleanArrayOf(true, true, true, true) // cela 2x2 maska plna
        val letterbox = LetterboxParams(scale = 0.5f, padX = 0f, padY = 4f)
        val result = maskToShapePoints(
            mask, maskW = 2, maskH = 2, protoScale = 4,
            letterbox = letterbox,
            srcWidth = 8, srcHeight = 16,
            sampleCount = 2,
        )
        assertTrue(result != null)
        // maskX=0 -> inputPx=0 -> (0-0)/0.5=0 -> pageX=0/8=0
        // maskX=2 (pravy okraj druheho sloupce, index 1 + 1) -> inputPx=8 -> (8-0)/0.5=16 -> orizne na sirku 8 -> pageX=8/8=1
        assertEquals(0f, result!!.first().leftF, 0.001f)
        assertEquals(1f, result.first().rightF, 0.001f)
    }
}

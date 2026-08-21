package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čisté JVM testy dekódování YOLOv8 výstupu - syntetické tenzory místo reálného modelu,
 * viz doc komentář v [YoloDetectionDecode.kt].
 */
class YoloDetectionDecodeTest {

    /** Sestaví "output0" tenzor (kanál-po-kanálu) ze seznamu (cx,cy,w,h,classScores...). */
    private fun buildOutput(numClasses: Int, anchors: List<List<Float>>): FloatArray {
        val numAnchors = anchors.size
        val channels = 4 + numClasses
        val out = FloatArray(channels * numAnchors)
        for (c in 0 until channels) {
            for (a in 0 until numAnchors) {
                out[c * numAnchors + a] = anchors[a][c]
            }
        }
        return out
    }

    // ── decodeYoloOutput ──

    @Test
    fun `decodes a single anchor above the confidence threshold`() {
        // cx=100,cy=100,w=40,h=20, class0=0.9, class1=0.1
        val output = buildOutput(2, listOf(listOf(100f, 100f, 40f, 20f, 0.9f, 0.1f)))
        val detections = decodeYoloOutput(output, numAnchors = 1, numClasses = 2, confThreshold = 0.25f)

        assertEquals(1, detections.size)
        val d = detections[0]
        assertEquals(0, d.classId)
        assertEquals(0.9f, d.score, 0.001f)
        assertEquals(80f, d.leftPx, 0.001f)
        assertEquals(90f, d.topPx, 0.001f)
        assertEquals(120f, d.rightPx, 0.001f)
        assertEquals(110f, d.bottomPx, 0.001f)
    }

    @Test
    fun `drops anchors below the confidence threshold`() {
        val output = buildOutput(2, listOf(listOf(100f, 100f, 40f, 20f, 0.1f, 0.05f)))
        val detections = decodeYoloOutput(output, numAnchors = 1, numClasses = 2, confThreshold = 0.25f)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun `picks the class with the highest score per anchor`() {
        val output = buildOutput(2, listOf(listOf(100f, 100f, 40f, 20f, 0.2f, 0.8f)))
        val detections = decodeYoloOutput(output, numAnchors = 1, numClasses = 2, confThreshold = 0.25f)
        assertEquals(1, detections.single().classId)
        assertEquals(0.8f, detections.single().score, 0.001f)
    }

    @Test
    fun `decodes multiple anchors independently`() {
        val output = buildOutput(
            2,
            listOf(
                listOf(50f, 50f, 20f, 20f, 0.9f, 0.0f),
                listOf(200f, 200f, 30f, 30f, 0.0f, 0.7f),
            ),
        )
        val detections = decodeYoloOutput(output, numAnchors = 2, numClasses = 2, confThreshold = 0.25f)
        assertEquals(2, detections.size)
        assertEquals(0, detections[0].classId)
        assertEquals(1, detections[1].classId)
    }

    // ── nonMaxSuppression ──

    @Test
    fun `keeps only the highest scoring box among heavily overlapping detections`() {
        val a = RawBoxDetection(0f, 0f, 100f, 100f, classId = 0, score = 0.9f)
        val b = RawBoxDetection(5f, 5f, 105f, 105f, classId = 0, score = 0.6f) // skoro identicky prekryva "a"
        val kept = nonMaxSuppression(listOf(a, b), iouThreshold = 0.45f)

        assertEquals(1, kept.size)
        assertEquals(0.9f, kept[0].score, 0.001f)
    }

    @Test
    fun `keeps separate boxes that do not overlap`() {
        val a = RawBoxDetection(0f, 0f, 50f, 50f, classId = 0, score = 0.9f)
        val b = RawBoxDetection(200f, 200f, 250f, 250f, classId = 0, score = 0.8f)
        val kept = nonMaxSuppression(listOf(a, b), iouThreshold = 0.45f)
        assertEquals(2, kept.size)
    }

    @Test
    fun `iou of identical boxes is 1`() {
        val a = RawBoxDetection(0f, 0f, 10f, 10f, classId = 0, score = 1f)
        assertEquals(1f, iou(a, a), 0.0001f)
    }

    @Test
    fun `iou of non-overlapping boxes is 0`() {
        val a = RawBoxDetection(0f, 0f, 10f, 10f, classId = 0, score = 1f)
        val b = RawBoxDetection(20f, 20f, 30f, 30f, classId = 0, score = 1f)
        assertEquals(0f, iou(a, b), 0.0001f)
    }

    // ── letterboxParams / toPageNormalized ──

    @Test
    fun `a square page scales to fill the target with no padding`() {
        val params = letterboxParams(srcWidth = 1000, srcHeight = 1000, targetSize = 640)
        assertEquals(0.64f, params.scale, 0.001f)
        assertEquals(0f, params.padX, 0.001f)
        assertEquals(0f, params.padY, 0.001f)
    }

    @Test
    fun `a tall page (typical manga) gets horizontal padding, not vertical`() {
        // 1000 sirokа, 2000 vysoka stranka - limitujici je vyska, sirka dostane padding.
        val params = letterboxParams(srcWidth = 1000, srcHeight = 2000, targetSize = 640)
        assertEquals(0.32f, params.scale, 0.001f)
        assertTrue("expected horizontal padding, got padX=${params.padX}", params.padX > 0f)
        assertEquals(0f, params.padY, 0.001f)
    }

    @Test
    fun `a detection at the letterbox origin maps back to the page origin`() {
        val params = letterboxParams(srcWidth = 1000, srcHeight = 2000, targetSize = 640)
        val det = RawBoxDetection(
            leftPx = params.padX,
            topPx = 0f,
            rightPx = params.padX + 32f, // 32px v 640-prostoru = 100px na strance (scale 0.32)
            bottomPx = 32f,
            classId = 0,
            score = 0.9f,
        )
        val mapped = det.toPageNormalized(params, srcWidth = 1000, srcHeight = 2000)

        assertEquals(0f, mapped.leftF, 0.001f)
        assertEquals(0f, mapped.topF, 0.001f)
        assertEquals(0.1f, mapped.rightF, 0.001f) // 100/1000
        assertEquals(0.05f, mapped.bottomF, 0.001f) // 100/2000
    }

    @Test
    fun `mapped coordinates are clamped to the page bounds`() {
        // Detekce sahajici az do sedeho okraje letterboxu by bez ozezu vyjela mimo stranku.
        val params = letterboxParams(srcWidth = 1000, srcHeight = 2000, targetSize = 640)
        val det = RawBoxDetection(leftPx = 0f, topPx = 0f, rightPx = 640f, bottomPx = 640f, classId = 0, score = 0.9f)
        val mapped = det.toPageNormalized(params, srcWidth = 1000, srcHeight = 2000)

        assertTrue(mapped.leftF in 0f..1f)
        assertTrue(mapped.topF in 0f..1f)
        assertTrue(mapped.rightF in 0f..1f)
        assertTrue(mapped.bottomF in 0f..1f)
        assertEquals(1f, mapped.bottomF, 0.001f) // cela vyska stranky, presne na hranici
    }
}

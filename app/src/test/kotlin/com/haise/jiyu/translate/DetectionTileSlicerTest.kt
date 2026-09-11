package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTileSlicerTest {

    private fun box(top: Float, bottom: Float, score: Float = 0.9f, left: Float = 0.1f, right: Float = 0.9f) =
        DetectedBubbleBox(leftF = left, topF = top, rightF = right, bottomF = bottom, classId = 0, score = score)

    // ── shouldSliceForDetection ──

    @Test
    fun `a normal-aspect page is not sliced`() {
        assertFalse(shouldSliceForDetection(widthPx = 800, heightPx = 1200))
    }

    @Test
    fun `an extremely tall webtoon strip is sliced`() {
        assertTrue(shouldSliceForDetection(widthPx = 800, heightPx = 4000))
    }

    @Test
    fun `exactly the threshold ratio still slices`() {
        assertTrue(shouldSliceForDetection(widthPx = 800, heightPx = 2800, aspectThreshold = 3.5f))
    }

    @Test
    fun `zero or negative dimensions never slice`() {
        assertFalse(shouldSliceForDetection(widthPx = 0, heightPx = 4000))
        assertFalse(shouldSliceForDetection(widthPx = 800, heightPx = 0))
    }

    // ── computeSliceRegions ──

    @Test
    fun `regions overlap and fully cover the image height`() {
        val regions = computeSliceRegions(imageHeightPx = 1000, sliceHeightPx = 400, overlapFraction = 0.2f)
        assertEquals(listOf(SliceRegion(0, 400), SliceRegion(320, 720), SliceRegion(640, 1000)), regions)
    }

    @Test
    fun `the last region is clipped to the image height, never overruns it`() {
        val regions = computeSliceRegions(imageHeightPx = 1000, sliceHeightPx = 400, overlapFraction = 0.2f)
        assertTrue(regions.all { it.bottomPxExcl <= 1000 })
        assertEquals(1000, regions.last().bottomPxExcl)
    }

    @Test
    fun `zero overlap still fully covers the image with no gaps`() {
        val regions = computeSliceRegions(imageHeightPx = 1000, sliceHeightPx = 500, overlapFraction = 0f)
        assertEquals(listOf(SliceRegion(0, 500), SliceRegion(500, 1000)), regions)
    }

    @Test
    fun `a slice taller than the image produces a single region`() {
        val regions = computeSliceRegions(imageHeightPx = 1000, sliceHeightPx = 2000, overlapFraction = 0.2f)
        assertEquals(listOf(SliceRegion(0, 1000)), regions)
    }

    // ── remapSliceDetections ──

    @Test
    fun `a detection is remapped from slice-local to full-page normalized coordinates`() {
        val region = SliceRegion(topPxIncl = 320, bottomPxExcl = 720) // 400px tall slice
        val detection = box(top = 0.5f, bottom = 0.75f)

        val remapped = remapSliceDetections(listOf(detection), region, fullImageHeightPx = 1000)

        assertEquals(0.52f, remapped[0].topF, 0.001f)
        assertEquals(0.62f, remapped[0].bottomF, 0.001f)
    }

    @Test
    fun `remapping never produces coordinates outside 0 to 1`() {
        val region = SliceRegion(topPxIncl = 0, bottomPxExcl = 400)
        val detection = box(top = -0.1f, bottom = 1.1f) // pathological input, should still clamp
        val remapped = remapSliceDetections(listOf(detection), region, fullImageHeightPx = 400)
        assertTrue(remapped[0].topF in 0f..1f)
        assertTrue(remapped[0].bottomF in 0f..1f)
    }

    // ── deduplicateByIou ──

    @Test
    fun `near-identical boxes from overlapping slices collapse to the higher-score one`() {
        val boxes = listOf(
            box(top = 0.50f, bottom = 0.60f, score = 0.9f),
            box(top = 0.51f, bottom = 0.61f, score = 0.6f), // same bubble, seen again in the next slice
        )
        val result = deduplicateByIou(boxes)
        assertEquals(1, result.size)
        assertEquals(0.9f, result[0].score, 0.001f)
    }

    @Test
    fun `non-overlapping boxes are all kept`() {
        val boxes = listOf(box(top = 0.1f, bottom = 0.2f), box(top = 0.5f, bottom = 0.6f), box(top = 0.8f, bottom = 0.9f))
        assertEquals(3, deduplicateByIou(boxes).size)
    }
}

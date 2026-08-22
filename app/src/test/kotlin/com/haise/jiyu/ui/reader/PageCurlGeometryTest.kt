package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageCurlGeometryTest {

    @Test
    fun `dragging the corner straight across produces a fold line near the page center`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f),
            dragPoint = Point(150f, 400f),
            pageWidth = 300f, pageHeight = 400f,
        )
        val foldMidX = (geometry.foldEdgeA.x + geometry.foldEdgeB.x) / 2f
        assertTrue("fold by mel byt kolem stredu mezi rohem a prstem", abs(foldMidX - 225f) < 5f)
    }

    @Test
    fun `dragging exactly to the opposite corner reaches nearly full progress`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f),
            dragPoint = Point(0f, 0f),
            pageWidth = 300f, pageHeight = 400f,
        )
        assertEquals(1f, geometry.progress, 0.15f)
    }

    @Test
    fun `dragging past the opposite corner is clamped, not extrapolated further`() {
        val withinBounds = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(0f, 0f),
            pageWidth = 300f, pageHeight = 400f,
        )
        val overshooting = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(-500f, -500f),
            pageWidth = 300f, pageHeight = 400f,
        )
        assertEquals(withinBounds.progress, overshooting.progress, 0.2f)
    }

    @Test
    fun `flat and curled regions together cover all four rectangle corners`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(200f, 350f),
            pageWidth = 300f, pageHeight = 400f,
        )
        val rectCorners = setOf(Point(0f, 0f), Point(300f, 0f), Point(300f, 400f), Point(0f, 400f))
        val covered = (geometry.flatRegion + geometry.curledRegion).toSet()
        rectCorners.forEach { corner ->
            assertTrue("roh $corner musi byt bud v ploche, nebo v ohybane casti", corner in covered)
        }
    }

    @Test
    fun `no drag (finger at the corner) yields zero progress`() {
        val geometry = computePageCurlGeometry(
            corner = Point(300f, 400f), dragPoint = Point(300f, 400f),
            pageWidth = 300f, pageHeight = 400f,
        )
        assertEquals(0f, geometry.progress, 0.01f)
    }

    @Test
    fun `reflecting a point across a horizontal line flips only the Y coordinate`() {
        val coeffs = computeReflectionAcross(Point(0f, 100f), Point(500f, 100f))
        val reflected = coeffs.apply(Point(50f, 150f))
        assertEquals(50f, reflected.x, 0.01f)
        assertEquals(50f, reflected.y, 0.01f)
    }

    @Test
    fun `reflecting a point across a vertical line flips only the X coordinate`() {
        val coeffs = computeReflectionAcross(Point(200f, 0f), Point(200f, 500f))
        val reflected = coeffs.apply(Point(250f, 80f))
        assertEquals(150f, reflected.x, 0.01f)
        assertEquals(80f, reflected.y, 0.01f)
    }

    @Test
    fun `reflecting twice returns the original point`() {
        val coeffs = computeReflectionAcross(Point(10f, 20f), Point(300f, 250f))
        val once = coeffs.apply(Point(70f, 45f))
        val twice = coeffs.apply(once)
        assertEquals(70f, twice.x, 0.05f)
        assertEquals(45f, twice.y, 0.05f)
    }

    @Test
    fun `a point exactly on the reflection line stays put`() {
        val coeffs = computeReflectionAcross(Point(0f, 0f), Point(100f, 100f))
        val reflected = coeffs.apply(Point(50f, 50f))
        assertEquals(50f, reflected.x, 0.01f)
        assertEquals(50f, reflected.y, 0.01f)
    }
}

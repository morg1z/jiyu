package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PageCurlGeometryTest {

    @Test
    fun `zero progress puts the fold exactly on the turning edge`() {
        val fromRight = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 0f,
        )
        assertEquals(300f, fromRight.foldX, 0.01f)
        assertEquals(0f, fromRight.curlBandWidth, 0.01f)

        val fromLeft = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = false, progress = 0f,
        )
        assertEquals(0f, fromLeft.foldX, 0.01f)
        assertEquals(0f, fromLeft.curlBandWidth, 0.01f)
    }

    @Test
    fun `full progress moves the fold to the opposite edge`() {
        val fromRight = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        assertEquals(0f, fromRight.foldX, 0.01f)

        val fromLeft = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = false, progress = 1f,
        )
        assertEquals(300f, fromLeft.foldX, 0.01f)
    }

    @Test
    fun `curl band width grows with progress but is capped at radius times half pi`() {
        val early = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 0.05f,
        )
        val late = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 0.9f,
        )
        val maxBand = late.radius * (PI.toFloat() / 2f)
        assertTrue("band by mel byt mensi nez strop kdyz je jeste malo papiru za osou", early.curlBandWidth < maxBand)
        assertEquals(maxBand, late.curlBandWidth, 0.5f)
    }

    @Test
    fun `progress is clamped to zero and one`() {
        val negative = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = -0.5f,
        )
        val overOne = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1.5f,
        )
        assertEquals(0f, negative.progress, 0.001f)
        assertEquals(1f, overOne.progress, 0.001f)
    }

    @Test
    fun `warped offset at zero distance from the fold is zero`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 0.5f,
        )
        assertEquals(0f, geometry.warpedOffset(0f), 0.01f)
    }

    @Test
    fun `warped offset compresses distance - never exceeds the original`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        val d = geometry.curlBandWidth
        assertTrue("warpovany posun musi byt <= puvodni vzdalenosti (komprese, ne roztazeni)", geometry.warpedOffset(d) <= d + 0.01f)
    }

    @Test
    fun `warped offset at the end of the visible band reaches close to the radius`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        assertEquals(geometry.radius, geometry.warpedOffset(geometry.curlBandWidth), 0.5f)
    }

    @Test
    fun `shade is full brightness right at the fold and darker at the end of the band`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        assertEquals(1f, geometry.shadeAt(0f), 0.01f)
        assertTrue("stin na konci pasu musi byt tmavsi nez u osy ohybu", geometry.shadeAt(geometry.curlBandWidth) < geometry.shadeAt(0f))
        assertEquals(0.35f, geometry.shadeAt(geometry.curlBandWidth), 0.02f)
    }

    @Test
    fun `roll style caps the band at radius times pi - twice as wide as classic`() {
        val roll = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f, style = CurlStyle.ROLL,
        )
        val maxBand = roll.radius * PI.toFloat()
        assertEquals(maxBand, roll.curlBandWidth, 0.5f)
    }

    @Test
    fun `roll style radius is tighter than classic for the same page width`() {
        val classic = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f, style = CurlStyle.CLASSIC,
        )
        val roll = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f, style = CurlStyle.ROLL,
        )
        assertTrue("svinuta trubicka musi byt uzsi nez klasicky ohyb", roll.radius < classic.radius)
    }

    @Test
    fun `roll style warped offset rises to the radius at mid-band then returns to zero at the end`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f, style = CurlStyle.ROLL,
        )
        val mid = geometry.curlBandWidth / 2f
        assertEquals(geometry.radius, geometry.warpedOffset(mid), 0.5f)
        assertEquals(0f, geometry.warpedOffset(geometry.curlBandWidth), 0.5f)
    }

    @Test
    fun `roll style shade is darkest at the end of the band - the rolled-away back of the page`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f, style = CurlStyle.ROLL,
        )
        assertEquals(1f, geometry.shadeAt(0f), 0.01f)
        assertTrue(
            "konec pasu u ROLL musi byt tmavsi nez konec pasu u CLASSIC (rubova strana svitku)",
            geometry.shadeAt(geometry.curlBandWidth) < 0.35f,
        )
    }

    @Test
    fun `vertical taper is full strength at the bottom anchor corner`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        assertEquals(1f, geometry.verticalTaper(1f), 0.001f)
    }

    @Test
    fun `vertical taper is weaker at the top - conical, not cylindrical`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        val topTaper = geometry.verticalTaper(0f)
        assertTrue("horni okraj musi mit slabsi ohyb nez dolni roh (konicky tvar)", topTaper < 1f)
        assertTrue("i na druhem konci musi zbyt viditelny ohyb, ne uplne plocha", topTaper > 0f)
    }

    @Test
    fun `vertical taper decreases monotonically away from the anchor corner`() {
        val geometry = computePageCurlGeometry(
            pageWidth = 300f, pageHeight = 400f, turningFromRight = true, progress = 1f,
        )
        val nearAnchor = geometry.verticalTaper(0.9f)
        val midway = geometry.verticalTaper(0.5f)
        val farFromAnchor = geometry.verticalTaper(0f)
        assertTrue(nearAnchor > midway)
        assertTrue(midway > farFromAnchor)
    }
}

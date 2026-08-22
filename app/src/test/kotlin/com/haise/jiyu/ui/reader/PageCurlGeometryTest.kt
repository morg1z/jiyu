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
}

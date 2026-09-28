package com.haise.jiyu.ui.reader.glcurl

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class GLPageRollMathTest {

    @Test
    fun `front roll uses the original PlayLikeCurl wavelength`() {
        val page = GLPageFront().apply {
            isActive = true
            curlCirclePosition = 12.5f
            calculateVerticesCoords()
        }

        val column = 18
        val dx = GLPage.GRID - page.curlCirclePosition
        val expectedZ = (
            GLPage.RADIUS * sin(3.14 / (GLPage.GRID * 0.60f) * (column - dx)) +
                GLPage.RADIUS * 1.1f
            ).toFloat()

        assertEquals(expectedZ, page.vertices[vertexOffset(row = 0, column = column) + 2], 0.00001f)
    }

    @Test
    fun `front roll has the same curl depth along the full page height`() {
        val page = GLPageFront().apply {
            isActive = true
            curlCirclePosition = 12.5f
            calculateVerticesCoords()
        }

        val column = 18
        val topZ = page.vertices[vertexOffset(row = 0, column = column) + 2]
        val bottomZ = page.vertices[vertexOffset(row = GLPage.GRID, column = column) + 2]

        assertEquals(topZ, bottomZ, 0.00001f)
    }

    @Test
    fun `backward roll uses the original PlayLikeCurl wavelength`() {
        val page = GLPageLeft().apply {
            isActive = true
            curlCirclePosition = 12.5f
            calculateVerticesCoords()
        }

        val column = 18
        val dx = GLPage.GRID - page.curlCirclePosition
        val expectedZ = (
            GLPage.RADIUS * sin(3.14 / (GLPage.GRID * 0.50f) * (column - dx)) +
                GLPage.RADIUS * 1.1f
            ).toFloat()

        assertEquals(expectedZ, page.vertices[vertexOffset(row = 0, column = column) + 2], 0.00001f)
    }

    @Test
    fun `forward mapping rolls the page fully off-screen at progress 1`() {
        // klid = naplocho
        assertEquals(GLPage.GRID.toFloat(), GLPageCurlRenderer.rollCurlPosition(true, 0f), 0.00001f)
        // dokonceny obrat = LEFT_REST (-1.25), trubicka cela mimo obrazovku vlevo
        assertEquals(GLPageCurlRenderer.LEFT_REST, GLPageCurlRenderer.rollCurlPosition(true, 1f), 0.00001f)
        // v polovine tahu je curlCirclePosition uprostred rozsahu
        val mid = GLPageCurlRenderer.rollCurlPosition(true, 0.5f)
        assertEquals((GLPage.GRID + GLPageCurlRenderer.LEFT_REST) / 2f, mid, 0.00001f)
    }

    @Test
    fun `backward mapping unrolls the previous page onto the screen`() {
        // klid = svinuta mimo obrazovku vlevo (zadny okamzity pop-over cele predchozi stranky)
        assertEquals(GLPageCurlRenderer.LEFT_REST, GLPageCurlRenderer.rollCurlPosition(false, 0f), 0.00001f)
        // dokonceny obrat zpatky = predchozi stranka naplocho
        assertEquals(GLPage.GRID.toFloat(), GLPageCurlRenderer.rollCurlPosition(false, 1f), 0.00001f)
        // zacatek tahu = stranka temer cela schovana (perc ~ 0.78 -> movX ~ 0.78)
        val left = GLPageLeft().apply {
            isActive = true
            curlCirclePosition = GLPageCurlRenderer.rollCurlPosition(false, 0f)
            calculateVerticesCoords()
        }
        // prostredni sloupec uz je svinuty mimo levy okraj (x < 0)
        assert(left.vertices[vertexOffset(row = 0, column = GLPage.GRID / 2)] < 0f)
    }

    @Test
    fun `mirrored front is the exact horizontal mirror of the unmirrored mesh`() {
        val plain = GLPageFront().apply {
            isActive = true
            curlCirclePosition = 12.5f
            calculateVerticesCoords()
        }
        val mirror = GLPageFront().apply {
            isActive = true
            mirrored = true
            curlCirclePosition = 12.5f
            calculateVerticesCoords()
        }

        for (col in 0..GLPage.GRID) {
            val mPos = vertexOffset(row = 0, column = col)
            val pPos = vertexOffset(row = 0, column = GLPage.GRID - col)
            // x_mirrored(c) = 1 - x_plain(GRID - c); z_mirrored(c) = z_plain(GRID - c)
            assertEquals(1f - plain.vertices[pPos], mirror.vertices[mPos], 0.00001f)
            assertEquals(plain.vertices[pPos + 2], mirror.vertices[mPos + 2], 0.00001f)
        }
    }

    @Test
    fun `mirrored mesh keeps readable texture orientation at rest`() {
        // naplocho musi byt mirrored mesh identicky plochy jako nemirrored (x = col/GRID),
        // jinak by byl obsah stranky zrcadlene prevraceny
        val mirror = GLPageFront().apply {
            isActive = true
            mirrored = true
            curlCirclePosition = GLPage.GRID.toFloat()
            calculateVerticesCoords()
        }
        for (col in 0..GLPage.GRID) {
            assertEquals(col / GLPage.GRID.toFloat(), mirror.vertices[vertexOffset(0, col)], 0.00001f)
        }
    }

    private fun vertexOffset(row: Int, column: Int): Int =
        3 * (row * (GLPage.GRID + 1) + column)
}

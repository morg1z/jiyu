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

    private fun vertexOffset(row: Int, column: Int): Int =
        3 * (row * (GLPage.GRID + 1) + column)
}

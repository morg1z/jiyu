package com.haise.jiyu.ui.reader.glcurl

import kotlin.math.sin

/**
 * Port `PageLeft.java` - aktivní ("otáčená") stránka při otáčení VZAD (na předchozí stránku).
 * Matematika ohybu 1:1 podle originálu, beze změny - jiná vlnová délka (0.50 místo 0.60) a jiné
 * `movX` než [GLPageFront], stejně jako v originále (drobné doladění vzhledu podle směru).
 */
class GLPageLeft : GLPage() {

    override fun calculateVerticesCoords() {
        super.calculateVerticesCoords()
        for (row in 0..GRID) {
            for (col in 0..GRID) {
                val pos = 3 * (row * (GRID + 1) + col)

                if (!isActive) {
                    vertices[pos + 2] = DEPTH
                }

                var perc = 1f - curlCirclePosition / GRID.toFloat()
                perc *= 0.75f
                val dx = GRID - curlCirclePosition
                var calcR = RADIUS
                if (calcR > RADIUS) calcR = RADIUS
                calcR = RADIUS
                var movX = 0f
                if (perc < 0.20f) calcR = RADIUS * perc * 5
                movX = perc

                if (isActive) {
                    vertices[pos + 2] = (calcR * sin(3.14 / (GRID * 0.50f) * (col - dx)) + calcR * 1.1f).toFloat()
                }
                val wHRatio = 1f - calcR

                vertices[pos] = (col / GRID.toFloat() * wHRatio) - movX
                vertices[pos + 1] = (row / GRID.toFloat() * hWRatio) - hWCorrection
            }
        }
    }

    companion object {
        private const val DEPTH = -0.001f
    }
}

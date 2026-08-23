package com.haise.jiyu.ui.reader.glcurl

import kotlin.math.sin

/**
 * Port `PageLeft.java` - aktivní ("otáčená") stránka při otáčení VZAD (na předchozí stránku).
 * `movX` beze změny podle originálu (drobné doladění vzhledu podle směru oproti [GLPageFront]).
 *
 * Vlnová délka sinusu upravena stejně jako v [GLPageFront] (viz tam dokumentace proč) -
 * [WAVELENGTH_MULTIPLIER] 2.0 místo originálních 0.50, aby ohyb i při podržení prstu v dotažené
 * pozici zůstal jeden hladký oblouk, ne spirála/trubička. Stejně tak [verticalTaper] (viz `GLPage`
 * a [GLPageFront] dokumentace) - ohyb sílí/slábne podle výšky, ne stejný na každém řádku.
 */
class GLPageLeft : GLPage() {

    override fun calculateVerticesCoords() {
        super.calculateVerticesCoords()
        for (row in 0..GRID) {
            val taper = verticalTaper(row)
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
                calcR *= taper

                if (isActive) {
                    vertices[pos + 2] = (calcR * sin(3.14 / (GRID * WAVELENGTH_MULTIPLIER) * (col - dx)) + calcR * 1.1f).toFloat()
                }
                val wHRatio = 1f - calcR

                vertices[pos] = (col / GRID.toFloat() * wHRatio) - movX
                vertices[pos + 1] = (row / GRID.toFloat() * hWRatio) - hWCorrection
            }
        }
    }

    companion object {
        private const val DEPTH = -0.001f
        private const val WAVELENGTH_MULTIPLIER = 2.0f
    }
}

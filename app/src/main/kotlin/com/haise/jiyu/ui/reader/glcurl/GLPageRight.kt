package com.haise.jiyu.ui.reader.glcurl

/**
 * Port `PageRight.java` - statická plochá "podkladová" stránka (ta, co se odkrývá pod ohybem,
 * bez vlastního ohybu). Beze změny podle originálu.
 */
class GLPageRight : GLPage() {

    override fun calculateVerticesCoords() {
        super.calculateVerticesCoords()
        for (row in 0..GRID) {
            for (col in 0..GRID) {
                val pos = 3 * (row * (GRID + 1) + col)

                if (!isActive) {
                    vertices[pos + 2] = DEPTH
                }

                vertices[pos] = col / GRID.toFloat()
                vertices[pos + 1] = (row / GRID.toFloat() * hWRatio) - hWCorrection
            }
        }
    }

    companion object {
        private const val DEPTH = -0.003f
    }
}

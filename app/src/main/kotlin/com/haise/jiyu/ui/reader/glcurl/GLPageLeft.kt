package com.haise.jiyu.ui.reader.glcurl

/**
 * Port `PageLeft.java` - aktivní ("otáčená") stránka při otáčení VZAD (na předchozí stránku).
 * Matematika je záměrně 1:1 s PlayLikeCurl včetně kratší vlnové délky pro svinutou trubičku.
 *
 * V klidu sedí na `curlCirclePosition = LEFT_REST` (-1.25): `perc = 0.7875` → `movX = 0.7875`
 * stránku posune svinutou mimo obrazovku vlevo (jen malý proužek trubičky vykukuje z okraje,
 * stejně jako v originále). Tah zpět ji pak rozbaluje na obrazovku - řízení viz
 * [GLPageCurlRenderer.onDrawFrame]. [mirrored] (RTL) zrcadlí deformaci jako v [GLPageFront].
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
                if (perc < 0.20f) calcR = RADIUS * perc * 5
                val movX = perc
                val wHRatio = 1f - calcR
                if (isActive) {
                    val curveCol = if (mirrored) GRID - col else col
                    vertices[pos + 2] = (calcR * Math.sin(3.14 / (GRID * 0.50f) * (curveCol - dx)) + calcR * 1.1f).toFloat()
                }

                vertices[pos] = if (mirrored) {
                    (1f - wHRatio) + col / GRID.toFloat() * wHRatio + movX
                } else {
                    col / GRID.toFloat() * wHRatio - movX
                }
                vertices[pos + 1] = (row / GRID.toFloat() * hWRatio) - hWCorrection
            }
        }
    }
    companion object { private const val DEPTH = -0.001f }
}

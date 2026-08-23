package com.haise.jiyu.ui.reader.glcurl

import kotlin.math.sin

/**
 * Port `PageFront.java` - aktivní ("otáčená") stránka při otáčení VPŘED (na další stránku).
 *
 * Jediná vědomá odchylka od originálu: vlnová délka sinusu (`3.14/(GRID*W)`, originál W=0.60).
 * Originál tuhle knihovnu používal jen jako RYCHLOU (300ms) automatickou animaci po puštění
 * prstu - při W=0.60 sinusový argument u nízkého curlCirclePosition (skoro dotažené otočení)
 * "obtočí" přes víc než čtvrt periody, takže Z souřadnice není monotónní a průřez ohybu vypadá
 * jako SPIRÁLA (svinutá trubička), ne jako jeden hladký ohyb - v rychlé animaci to oko nestihne
 * postřehnout, ale naše čtečka nechává uživatele tažení prstem PODRŽET v libovolné pozici, takže
 * je ta spirála jasně vidět a vypadá jako chyba. W=2.0 drží sinusový argument v rozsahu jedné
 * čtvrtperiody i při plně dotaženém ohybu - zaručeně jeden hladký ohyb, žádná spirála.
 *
 * Druhá odchylka: [verticalTaper] (viz `GLPage`) násobí `calcR` podle řádku - originál měl ohyb
 * úplně stejný na každém řádku (rovnoměrný "válec" přes celou výšku), uživatelská zpětná vazba
 * to popsala jako "chybí vlna" - se skutečnou stránkou v ruce se totiž typicky drží za jeden roh,
 * takže ohyb dole/nahoře přirozeně sílí/slábne, ne je všude stejný.
 */
class GLPageFront : GLPage() {

    override fun calculateVerticesCoords() {
        super.calculateVerticesCoords()
        for (row in 0..GRID) {
            val taper = verticalTaper(row)
            for (col in 0..GRID) {
                val pos = 3 * (row * (GRID + 1) + col)

                if (!isActive) {
                    vertices[pos + 2] = DEPTH
                }

                val perc = 1f - curlCirclePosition / GRID.toFloat()
                val dx = GRID - curlCirclePosition
                var calcR = RADIUS
                if (calcR > RADIUS) calcR = RADIUS
                calcR = RADIUS
                var movX = 0f
                if (perc < 0.20f) calcR = RADIUS * perc * 5
                if (perc > 0.05f) movX = perc - 0.05f
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
        private const val DEPTH = -0.002f
        private const val WAVELENGTH_MULTIPLIER = 2.0f
    }
}

package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test [recoverBubble] (žádná Android/Bitmap závislost) - syntetický PixelSource
 * kreslí bubliny do IntArray a ověřuje, že obnova obrysu z interiéru OCR boxu:
 *  - najde obrys i tam, kde selhalo vzorkování prstencem (box přetéká mimo bublinu),
 *  - vrátí skutečnou barvu interiéru (zelená bublina -> zelená, ne bílá okraje),
 *  - konzervativně vrací null tam, kde by hádání mohlo ublížit (kresba, vylití, kaskády).
 */
class BubbleRecoveryTest {

    private class FakeCanvas(val width: Int, val height: Int, fill: Int) : PixelSource {
        val pixels = IntArray(width * height) { fill }
        override fun colorAt(x: Int, y: Int): Int = pixels[y * width + x]
        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top..bottom) for (x in left..right) pixels[y * width + x] = color
        }
    }

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val GREEN = 0xFF7EC850.toInt()   // žlutozelená bublina z nahlášeného screenshotu
    private val PANEL_GRAY = 0xFF888888.toInt()

    /** Pár tlustých tahů simulujících řádek textu - markTextPixels je pozná podle lokálního kontrastu. */
    private fun FakeCanvas.drawLetters(left: Int, top: Int, color: Int) {
        fillRect(left, top, left + 30, top + 4, color)
        fillRect(left, top + 10, left + 26, top + 14, color)
        fillRect(left + 40, top, left + 44, top + 14, color)
    }

    private fun block(l: Int, t: Int, r: Int, b: Int, w: Int, h: Int) = RawTextBlock(
        text = "text",
        leftF = l / w.toFloat(),
        topF = t / h.toFloat(),
        rightF = r / w.toFloat(),
        bottomF = b / h.toFloat(),
    )

    private fun recover(canvas: FakeCanvas, own: RawTextBlock, others: List<RawTextBlock> = emptyList()) =
        recoverBubble(canvas, canvas.width, canvas.height, own.leftF, own.topF, own.rightF, own.bottomF, own, others)

    @Test
    fun `recovers green bubble when OCR box overshoots into white margin`() {
        // Přesně nahlášený scénář: prstenec kolem přetékajícího boxu vzorkuje bílý okraj,
        // takže OCR-time detekce selže - interiér boxu ale leží uvnitř bubliny a stačí.
        val canvas = FakeCanvas(400, 300, WHITE)
        canvas.fillRect(98, 58, 262, 182, BLACK)   // obrys
        canvas.fillRect(100, 60, 260, 180, GREEN)  // interiér
        canvas.drawLetters(120, 100, BLACK)

        // OCR box přetéká vpravo daleko za hranici bubliny (260 -> 340) do bílého okraje.
        val own = block(110, 70, 340, 170, 400, 300)
        val r = recover(canvas, own)

        assertNotNull(r)
        // Barva interiéru = zelená bubliny, ne bílá okraje - přesně ta chyba, kterou
        // prstencové vzorkování vyrábělo.
        assertTrue(colorDistance(r!!.interiorArgb, GREEN) < 40)
        // Obrys končí u bubliny (~260 px), ne u přesahu boxu (340 px) - do bílého okraje
        // se nesahá.
        assertTrue(r.shape.maxOf { it.rightF } <= 262f / 400f)
        assertTrue(r.shape.maxOf { it.rightF } >= 255f / 400f)
        assertTrue(r.shape.minOf { it.leftF } <= 102f / 400f)
        assertTrue(r.shape.first().yF <= 62f / 300f)
        assertTrue(r.shape.last().yF >= 178f / 300f)
    }

    @Test
    fun `recovers white bubble on gray panel`() {
        val canvas = FakeCanvas(400, 300, PANEL_GRAY)
        canvas.fillRect(98, 58, 262, 182, BLACK)
        canvas.fillRect(100, 60, 260, 180, WHITE)
        canvas.drawLetters(120, 100, BLACK)

        val own = block(110, 70, 340, 170, 400, 300)
        val r = recover(canvas, own)

        assertNotNull(r)
        assertTrue(colorDistance(r!!.interiorArgb, WHITE) < 40)
        assertTrue(r.shape.maxOf { it.rightF } <= 262f / 400f)
    }

    @Test
    fun `recovers dark bubble and reports dark interior`() {
        // Tmavá bublina s bílým písmem - interiér se musí poznat jako tmavý, jinak by
        // se z něj při výplně vyrobil světlý box s bílým textem.
        val canvas = FakeCanvas(400, 300, WHITE)
        canvas.fillRect(100, 60, 260, 180, BLACK)
        canvas.drawLetters(120, 100, WHITE)

        val own = block(110, 70, 340, 170, 400, 300)
        val r = recover(canvas, own)

        assertNotNull(r)
        assertTrue(colorDistance(r!!.interiorArgb, BLACK) < 40)
        assertTrue(r.shape.maxOf { it.rightF } <= 262f / 400f)
    }

    @Test
    fun `returns null when dominant color is the margin not the bubble`() {
        // Box leží převážně mimo bublinu - interiér je tedy převážně okraj a flood-fill
        // se rozeběhne po celé stránce (kontrola plochy ho zamítne). Konzervativní null
        // je správně - nechat dnešní heuristický fallback.
        val canvas = FakeCanvas(400, 300, WHITE)
        canvas.fillRect(58, 58, 162, 162, BLACK)
        canvas.fillRect(60, 60, 160, 160, GREEN)
        canvas.drawLetters(70, 100, BLACK)

        // Box pokrývá jen pravý kraj bubliny a hodně bílého okraje.
        val own = block(100, 80, 300, 160, 400, 300)
        assertNull(recover(canvas, own))
    }

    @Test
    fun `returns null for text on multicolored art - keeps patch path`() {
        // Text přímo na kresbě (žádná bublina): žádná barva nedominuje, takže se nemá
        // co "obnovit" - zůstane záplata jako dosud.
        val canvas = FakeCanvas(400, 300, WHITE)
        for (y in 0 until 300 step 8) {
            for (x in 0 until 400 step 8) {
                val c = when (((x / 8) + (y / 8)) % 3) {
                    0 -> 0xFF404040.toInt()
                    1 -> 0xFF909090.toInt()
                    else -> 0xFFD0B060.toInt()
                }
                canvas.fillRect(x, y, minOf(x + 7, 399), minOf(y + 7, 299), c)
            }
        }
        canvas.drawLetters(140, 100, WHITE)

        val own = block(100, 60, 300, 160, 400, 300)
        assertNull(recover(canvas, own))
    }

    @Test
    fun `returns null when flood leaks across whole page`() {
        // "Bublina" bez obrysu stejné barvy jako stránka - flood se rozeběhne všude,
        // kontrola plochy v detectShape ho zamítne.
        val canvas = FakeCanvas(400, 300, GREEN)
        canvas.drawLetters(180, 140, BLACK)

        val own = block(160, 120, 260, 170, 400, 300)
        assertNull(recover(canvas, own))
    }

    @Test
    fun `clamps recovered shape to own lobe of merged bubbles`() {
        // Dvě kaskádové bubliny splývající do jedné bílé plochy - flood najde celek,
        // clampShapeToOwnLobe ho musí zúžit jen na vlastní lalok, jinak by překlad
        // přemaloval text souseda. Plátno je schválně větší, aby společná plocha
        // (260x140 = 36 400 px) nepřekročila strop detectShape (25 % stránky).
        val canvas = FakeCanvas(500, 400, PANEL_GRAY)
        canvas.fillRect(58, 38, 322, 182, BLACK)
        canvas.fillRect(60, 40, 320, 180, WHITE)  // jedna souvislá bílá plocha
        canvas.drawLetters(80, 100, BLACK)
        canvas.drawLetters(240, 100, BLACK)

        val own = block(70, 80, 180, 140, 500, 400)
        val other = block(230, 80, 310, 140, 500, 400)
        val r = recover(canvas, own, others = listOf(other))

        assertNotNull(r)
        // Mez = půlka mezery mezi boxy: (180 + 230) / 2 = 205 px. Obrys nesmí přesáhnout
        // souseda, kterého původní flood zalil celého.
        assertTrue(r!!.shape.maxOf { it.rightF } <= 210f / 500f)
        // Levý okraj zůstane u skutečného kraje plochy.
        assertTrue(r.shape.minOf { it.leftF } <= 65f / 500f)
    }

    @Test
    fun `returns null for tiny box`() {
        val canvas = FakeCanvas(400, 300, WHITE)
        canvas.fillRect(98, 58, 262, 182, BLACK)
        canvas.fillRect(100, 60, 260, 180, GREEN)

        val own = block(120, 80, 125, 84, 400, 300) // 5x4 px < MIN_BOX_PX
        assertNull(recover(canvas, own))
    }

    @Test
    fun `interior estimate ignores ink color - dense dark text still yields light interior`() {
        // Hustý tmavý text na světlé bublině: bez oddělení tahů by dominantní barva
        // vyšla jako písmo (černá), se správnou maskou musí vyjít pozadí.
        val canvas = FakeCanvas(400, 300, WHITE)
        canvas.fillRect(98, 58, 262, 182, BLACK)
        canvas.fillRect(100, 60, 260, 180, GREEN)
        // Více řádků textu - ~třetina plochy boxu tahy.
        canvas.drawLetters(120, 80, BLACK)
        canvas.drawLetters(120, 110, BLACK)
        canvas.drawLetters(120, 140, BLACK)

        val own = block(110, 70, 340, 170, 400, 300)
        val r = recover(canvas, own)

        assertNotNull(r)
        assertTrue(colorDistance(r!!.interiorArgb, GREEN) < 40)
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return maxOf(kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
    }
}

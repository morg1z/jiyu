package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test [BubbleShapeDetector] (žádná Android/Bitmap závislost) - syntetický
 * PixelSource kreslí jednoduché tvary do IntArray a ověřuje, že flood-fill najde
 * očekávaný obrys / správně selže na moc velké nebo neplatné ploše.
 */
class BubbleShapeDetectorTest {

    private class FakeCanvas(val width: Int, val height: Int, fill: Int) : PixelSource {
        val pixels = IntArray(width * height) { fill }
        override fun colorAt(x: Int, y: Int): Int = pixels[y * width + x]
        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top..bottom) for (x in left..right) pixels[y * width + x] = color
        }
    }

    private val BG = 0xFFCCCCCC.toInt()
    private val ART = 0xFF000000.toInt()

    @Test
    fun `detects bounding box of a solid rectangle bubble`() {
        val canvas = FakeCanvas(100, 60, ART)
        canvas.fillRect(20, 10, 80, 50, BG)

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 100,
            height = 60,
            seeds = listOf(50 to 30),
            bgColorArgb = BG,
            // Testovací obdélník (61x41 = ~42 % plátna) je uměle velký vůči malému plátnu -
            // reálná bublina na skutečné stránce manga bývá zlomek celé plochy. Výchozí
            // maxAreaFraction (0.25) je testovaný zvlášť níž ("leaks past the area cap").
            maxAreaFraction = 0.5f,
        )

        assertNotNull(shape)
        val left = shape!!.minOf { it.leftF }
        val right = shape.maxOf { it.rightF }
        val top = shape.minOf { it.yF }
        val bottom = shape.maxOf { it.yF }
        assertEquals(0.20f, left, 0.02f)
        assertEquals(0.80f, right, 0.02f)
        assertEquals(10f / 60f, top, 0.02f)
        assertEquals(50f / 60f, bottom, 0.02f)
    }

    @Test
    fun `returns null when no seed matches background color`() {
        val canvas = FakeCanvas(100, 60, ART)
        canvas.fillRect(20, 10, 80, 50, BG)

        // Seed sedí uvnitř obdélníku, ale bgColorArgb neodpovídá ničemu na plátně.
        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 100,
            height = 60,
            seeds = listOf(50 to 30),
            bgColorArgb = 0xFFFF00FF.toInt(),
        )

        assertNull(shape)
    }

    @Test
    fun `returns null when flood fill leaks past the area cap`() {
        // Skoro celé plátno je "pozadí" - žádná uzavřená bublina, flood-fill by se
        // rozlil přes většinu stránky (simuluje SFX text přímo na kresbě bez bubliny).
        val canvas = FakeCanvas(100, 60, BG)

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 100,
            height = 60,
            seeds = listOf(50 to 30),
            bgColorArgb = BG,
            maxAreaFraction = 0.25f,
        )

        assertNull(shape)
    }

    @Test
    fun `sampled points are ordered from top to bottom`() {
        val canvas = FakeCanvas(100, 60, ART)
        canvas.fillRect(20, 10, 80, 50, BG)

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 100,
            height = 60,
            seeds = listOf(50 to 30),
            bgColorArgb = BG,
            maxAreaFraction = 0.5f,
        )

        assertNotNull(shape)
        for (i in 1 until shape!!.size) {
            assertTrue(shape[i].yF >= shape[i - 1].yF)
        }
    }

    @Test
    fun `ignores invalid seeds outside the canvas`() {
        val canvas = FakeCanvas(100, 60, ART)
        canvas.fillRect(20, 10, 80, 50, BG)

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 100,
            height = 60,
            seeds = listOf(-5 to -5, 50 to 30), // první seed mimo plátno, druhý platný
            bgColorArgb = BG,
            maxAreaFraction = 0.5f,
        )

        assertNotNull(shape)
    }

    // ── obrys nesmyslně velký proti textu uvnitř (viz MAX_SHAPE_TO_TEXT_AREA_RATIO) ──

    /**
     * Vodoznak na tmavém pruhu: text zabírá kousek rohu, ale tmavá plocha kolem něj je souvislá
     * přes celý panel, takže se flood-fill vylije daleko za bublinu. Plošný limit vztažený ke
     * CELÉ stránce to nezachytí - čtvrtina vysoké stránky je obrovská rezerva.
     */
    private fun watermarkOnDarkBand(): FakeCanvas {
        // Vysoka stranka schvalne - prave tam je plosny limit 0,25 STRANKY obrovska rezerva
        // (pruh 380x161 = 61 180 px je jen 17 % z 400x900), takze uniklou vypln nezachyti.
        val canvas = FakeCanvas(400, 900, 0xFFFFFFFF.toInt())
        canvas.fillRect(10, 60, 389, 220, BG) // souvisla tmava plocha pres cely panel
        return canvas
    }

    @Test
    fun `a shape that dwarfs its own text is rejected`() {
        val canvas = watermarkOnDarkBand()
        // OCR box vodoznaku: maly obdelnik v pravem dolnim rohu tmave plochy (64x10 px),
        // obalovy obdelnik vyliti je proti nemu 95x vetsi.
        val textArea = textAreaPx(0.80f, 0.2167f, 0.96f, 0.2278f, 400, 900)

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 400,
            height = 900,
            seeds = listOf(350 to 195),
            bgColorArgb = BG,
            textAreaPx = textArea,
        )

        assertNull("obrys 95x vetsi nez text neni bublina, ale unikle vyliti", shape)
    }

    @Test
    fun `without the text area the old behaviour is kept`() {
        // Zpetna kompatibilita: volani bez textAreaPx (0) kontrolu vubec nepousti - a prave
        // tenhle pripad ukazuje, ze plosny limit stranky sam o sobe unik nezachyti.
        val canvas = watermarkOnDarkBand()
        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 400,
            height = 900,
            seeds = listOf(350 to 195),
            bgColorArgb = BG,
        )

        assertNotNull(shape)
    }

    @Test
    fun `a real bubble around a single short word survives the check`() {
        // Nejtesnejsi ZMERENY skutecny pripad z nahlasene stranky: "DAMN..." v kulate bubline,
        // obalovy obdelnik obrysu 16,1x plocha OCR boxu. Musi projit, jinak by oprava vzala
        // tvar i bublinam, ktere zadnou chybu nemaji.
        val canvas = FakeCanvas(400, 300, ART)
        canvas.fillRect(120, 90, 279, 209, BG) // bublina 160x120 = 19200 px
        val textArea = 19200L / 16 // ~1200 px, tedy pomer 16x

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 400,
            height = 300,
            seeds = listOf(200 to 150),
            bgColorArgb = BG,
            textAreaPx = textArea,
        )

        assertNotNull("pomer 16x je skutecna bublina, ne unik", shape)
    }

    @Test
    fun `the ratio is measured against the shape bounds, not the filled pixels`() {
        // Kreslit se bude obalovy obdelnik po radcich (viz BubbleClipShape), takze rozhoduje
        // on - ne pocet doopravdy vylitych pixelu. Tenky kriz ma malo pixelu, ale obri obalovy
        // obdelnik, a prave ten by premaloval kresbu.
        val canvas = FakeCanvas(400, 300, ART)
        canvas.fillRect(10, 145, 389, 154, BG) // vodorovne rameno
        canvas.fillRect(195, 20, 204, 279, BG) // svisle rameno
        val textArea = 400L // maly text

        val shape = BubbleShapeDetector.detectShape(
            source = canvas,
            width = 400,
            height = 300,
            seeds = listOf(200 to 150),
            bgColorArgb = BG,
            textAreaPx = textArea,
        )

        assertNull("obalovy obdelnik krize je 380x260, tedy 247x plocha textu", shape)
    }

    // ── pozorovaci callback pro pomer tvar/text (viz MAX_SHAPE_TO_TEXT_AREA_RATIO) ──

    @Test
    fun `onRatioMeasured reports the ratio and acceptance for a real bubble`() {
        val canvas = FakeCanvas(400, 300, ART)
        canvas.fillRect(120, 90, 279, 209, BG) // bublina 160x120 = 19200 px
        val textArea = 19200L / 16 // ~1200 px, tedy pomer 16x
        var reportedRatio: Double? = null
        var reportedAccepted: Boolean? = null

        BubbleShapeDetector.detectShape(
            source = canvas,
            width = 400,
            height = 300,
            seeds = listOf(200 to 150),
            bgColorArgb = BG,
            textAreaPx = textArea,
            onRatioMeasured = { ratio, accepted -> reportedRatio = ratio; reportedAccepted = accepted },
        )

        assertNotNull("callback se musi zavolat, kdyz je textAreaPx > 0", reportedRatio)
        assertEquals(16.0, reportedRatio!!, 0.5)
        assertEquals(true, reportedAccepted)
    }

    @Test
    fun `onRatioMeasured reports the ratio and rejection for an escaped fill`() {
        val canvas = FakeCanvas(400, 300, ART)
        canvas.fillRect(10, 145, 389, 154, BG) // vodorovne rameno
        canvas.fillRect(195, 20, 204, 279, BG) // svisle rameno
        val textArea = 400L // maly text
        var reportedRatio: Double? = null
        var reportedAccepted: Boolean? = null

        BubbleShapeDetector.detectShape(
            source = canvas,
            width = 400,
            height = 300,
            seeds = listOf(200 to 150),
            bgColorArgb = BG,
            textAreaPx = textArea,
            onRatioMeasured = { ratio, accepted -> reportedRatio = ratio; reportedAccepted = accepted },
        )

        assertNotNull("callback se musi zavolat i kdyz se tvar nakonec zamitne", reportedRatio)
        assertEquals(247.0, reportedRatio!!, 1.0)
        assertEquals(false, reportedAccepted)
    }

    @Test
    fun `onRatioMeasured is not called when textAreaPx is not supplied`() {
        val canvas = FakeCanvas(100, 60, ART)
        canvas.fillRect(20, 10, 80, 50, BG)
        var called = false

        BubbleShapeDetector.detectShape(
            source = canvas,
            width = 100,
            height = 60,
            seeds = listOf(50 to 30),
            bgColorArgb = BG,
            maxAreaFraction = 0.5f,
            onRatioMeasured = { _, _ -> called = true },
        )

        assertEquals(false, called)
    }
}

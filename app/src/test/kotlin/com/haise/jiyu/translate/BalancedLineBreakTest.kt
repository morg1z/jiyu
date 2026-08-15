package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test vyváženého lámání řádků a odvození šířek z tvaru bubliny.
 *
 * Cíl: text v bublině nemá vypadat jako "4 slova / 1 slovo" (hladové zalamování), ale jako
 * rovnoměrný, u oválné bubliny kosočtvercový blok - viz komentář v [BalancedLineBreak].
 */
class BalancedLineBreakTest {

    /** Monospace model - šířka slova = počet znaků (mezera = 1). */
    private fun widths(vararg words: String) = words.map { it.length.toFloat() }

    private fun lineWidthsOf(words: List<String>, ends: List<Int>): List<Int> =
        assembleLines(words, ends).map { it.length }

    // ── breakIntoLines ──

    @Test
    fun `distributes words evenly instead of greedily filling the first line`() {
        // Hladové zalamovani do sirky 11 by dalo "AAA BBB CCC" / "DDD" (11 / 3).
        // Vyvazene ma dat 7 / 7.
        val words = listOf("AAA", "BBB", "CCC", "DDD")
        val ends = breakIntoLines(widths(*words.toTypedArray()), spaceWidth = 1f, allowedWidths = listOf(11f, 11f))

        assertNotNull(ends)
        assertEquals(listOf(7, 7), lineWidthsOf(words, ends!!))
    }

    @Test
    fun `respects a narrower allowed width on one line`() {
        val words = listOf("AAAA", "BB", "CCCC")
        // Prvni radek je uzky (jako horni cast oválu), druhy siroky.
        val ends = breakIntoLines(widths(*words.toTypedArray()), spaceWidth = 1f, allowedWidths = listOf(4f, 20f))

        assertNotNull(ends)
        val lines = assembleLines(words, ends!!)
        assertEquals("AAAA", lines[0])
        assertEquals("BB CCCC", lines[1])
    }

    @Test
    fun `returns null when a single word cannot fit its line`() {
        val words = listOf("NEJNEPRAVDEPODOBNEJSIMI", "AB")
        val ends = breakIntoLines(widths(*words.toTypedArray()), spaceWidth = 1f, allowedWidths = listOf(5f, 5f))
        assertNull(ends)
    }

    @Test
    fun `returns null when there are fewer words than requested lines`() {
        assertNull(breakIntoLines(widths("AB"), spaceWidth = 1f, allowedWidths = listOf(10f, 10f)))
    }

    @Test
    fun `every line stays within its allowed width`() {
        val words = listOf("KDYBYCH", "VEDEL", "JAKA", "TA", "CESTA", "BUDE")
        val allowed = listOf(10f, 16f, 16f, 10f)
        val ends = breakIntoLines(widths(*words.toTypedArray()), spaceWidth = 1f, allowedWidths = allowed)

        assertNotNull(ends)
        val lines = assembleLines(words, ends!!)
        assertEquals(4, lines.size)
        lines.forEachIndexed { i, line ->
            assertTrue("line '$line' (${line.length}) exceeds allowed ${allowed[i]}", line.length <= allowed[i])
        }
    }

    @Test
    fun `produces a diamond shaped block when middle lines are allowed to be wider`() {
        // Presne ten tvar, ktery dela skutecny lettering v oválné bublině.
        val words = listOf("AA", "BB", "CC", "DD", "EE", "FF", "GG", "HH", "II")
        val allowed = listOf(8f, 14f, 14f, 8f)
        val ends = breakIntoLines(widths(*words.toTypedArray()), spaceWidth = 1f, allowedWidths = allowed)

        assertNotNull(ends)
        val lens = lineWidthsOf(words, ends!!)
        assertTrue("middle lines should be wider than the first (got $lens)", lens[1] >= lens[0])
        assertTrue("middle lines should be wider than the last (got $lens)", lens[2] >= lens[3])
    }

    @Test
    fun `never leaves a line empty`() {
        val words = listOf("AAAA", "BBBB", "CCCC")
        val ends = breakIntoLines(widths(*words.toTypedArray()), spaceWidth = 1f, allowedWidths = listOf(20f, 20f, 20f))
        assertNotNull(ends)
        assembleLines(words, ends!!).forEach { assertTrue("empty line produced", it.isNotBlank()) }
    }

    // ── shapeLineWidths ──

    @Test
    fun `oval shape gives the middle lines more room than the edge lines`() {
        // Aproximace elipsy - uzka nahore i dole, nejsirsi uprostred.
        val shape = listOf(
            BubbleShapePoint(0.0f, 0.45f, 0.55f),
            BubbleShapePoint(0.25f, 0.25f, 0.75f),
            BubbleShapePoint(0.5f, 0.10f, 0.90f),
            BubbleShapePoint(0.75f, 0.25f, 0.75f),
            BubbleShapePoint(1.0f, 0.45f, 0.55f),
        )
        val widths = shapeLineWidths(
            shape = shape,
            centerF = 0.5f,
            blockTopF = 0.0f,
            blockBottomF = 1.0f,
            lineCount = 4,
            pageWidthPx = 1000f,
        )

        assertEquals(4, widths.size)
        assertTrue("middle line should be wider than first (got $widths)", widths[1] > widths[0])
        assertTrue("middle line should be wider than last (got $widths)", widths[2] > widths[3])
    }

    @Test
    fun `rectangular shape gives every line the same room`() {
        val shape = (0..8).map { BubbleShapePoint(it / 8f, 0.2f, 0.8f) }
        val widths = shapeLineWidths(shape, centerF = 0.5f, blockTopF = 0f, blockBottomF = 1f, lineCount = 4, pageWidthPx = 1000f)

        widths.forEach { assertEquals(600f, it, 1f) }
    }

    @Test
    fun `width is measured symmetrically around the centre axis so a centred line can never overflow`() {
        // Tvar posunuty doprava vuci ose - symetricke mereni musi vratit uzsi (bezpecnou) sirku,
        // ne celou sirku tvaru. Jinak by vycentrovany radek precuhoval pres levy okraj.
        val shape = listOf(
            BubbleShapePoint(0.0f, 0.40f, 0.90f), // sirka 0.50, ale stred je 0.65
            BubbleShapePoint(1.0f, 0.40f, 0.90f),
        )
        val widths = shapeLineWidths(shape, centerF = 0.5f, blockTopF = 0f, blockBottomF = 1f, lineCount = 1, pageWidthPx = 1000f)

        // Kolem osy 0.5 je bezpecne jen min(0.5-0.40, 0.90-0.5) = 0.10 na kazdou stranu.
        assertEquals(200f, widths[0], 1f)
    }

    @Test
    fun `degenerate block height yields zero widths rather than crashing`() {
        val shape = (0..4).map { BubbleShapePoint(it / 4f, 0.2f, 0.8f) }
        val widths = shapeLineWidths(shape, centerF = 0.5f, blockTopF = 0.5f, blockBottomF = 0.5f, lineCount = 3, pageWidthPx = 1000f)
        assertEquals(listOf(0f, 0f, 0f), widths)
    }

    // ── fitTextToShape (celá sazba) ──

    /** Ovál zabírající celou výšku stránky, nejširší uprostřed. */
    private fun ovalShape() = (0..16).map { i ->
        val t = i / 16f
        val half = 0.40f * kotlin.math.sin(Math.PI * t).toFloat().coerceAtLeast(0.05f)
        BubbleShapePoint(yF = t, leftF = 0.5f - half, rightF = 0.5f + half)
    }

    /** Monospace model písma: šířka znaku = 0.6 * fontSp, výška řádku = 1.25 * fontSp. */
    private fun fitOval(
        text: String,
        pageWidthPx: Float = 1000f,
        pageHeightPx: Float = 1000f,
        preferredFontSp: Float? = null,
        onCapProbe: (preferredFontSp: Float, roomToGrow: Boolean) -> Unit = { _, _ -> },
    ) =
        fitTextToShape(
            words = text.split(" ").filter { it.isNotBlank() },
            minFontSp = 6f,
            maxFontSp = 36f,
            shape = ovalShape(),
            centerF = 0.5f,
            shapeTopF = 0f,
            shapeBottomF = 1f,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            measureWord = { word, fontSp -> word.length * fontSp * 0.6f },
            spaceWidth = { fontSp -> fontSp * 0.6f },
            lineHeightPx = { fontSp -> fontSp * 1.25f },
            preferredFontSp = preferredFontSp,
            onCapProbe = onCapProbe,
        )

    @Test
    fun `fits text into an oval and never breaks a word apart`() {
        val text = "KDYBYCH VEDEL JAKA TA CESTA BUDE"
        val layout = fitOval(text)

        assertNotNull(layout)
        // Vsechna slova musi zustat cela a ve spravnem poradi.
        assertEquals(text, layout!!.lines.joinToString(" "))
    }

    @Test
    fun `middle lines end up longer than the edge lines inside an oval`() {
        val layout = fitOval("AA BB CC DD EE FF GG HH II JJ KK LL MM NN OO PP")
        assertNotNull(layout)
        val lines = layout!!.lines
        if (lines.size >= 3) {
            val middle = lines[lines.size / 2].length
            assertTrue(
                "middle line ($middle) should not be shorter than the first (${lines.first().length}): $lines",
                middle >= lines.first().length,
            )
        }
    }

    @Test
    fun `a word too long for the widest part of the bubble makes the fit shrink, not chop`() {
        val layout = fitOval("NEJNEPRAVDEPODOBNEJSIMI")
        assertNotNull(layout)
        // Jedno slovo -> jeden radek, cely.
        assertEquals(listOf("NEJNEPRAVDEPODOBNEJSIMI"), layout!!.lines)
        // A musi se vejit do nejsirsiho mista ovalu pri zvolene velikosti.
        val widest = 0.80f * 1000f
        assertTrue(
            "word width ${23 * layout.fontSp * 0.6f} must fit within $widest at ${layout.fontSp}sp",
            23 * layout.fontSp * 0.6f <= widest,
        )
    }

    @Test
    fun `returns null when the text cannot fit even at the smallest font`() {
        // Uzka a nizka bublina + hodne dlouhy text.
        val tiny = listOf(
            BubbleShapePoint(0.0f, 0.49f, 0.51f),
            BubbleShapePoint(0.02f, 0.49f, 0.51f),
        )
        val layout = fitTextToShape(
            words = List(40) { "DLOUHESLOVO" },
            minFontSp = 6f,
            maxFontSp = 36f,
            shape = tiny,
            centerF = 0.5f,
            shapeTopF = 0f,
            shapeBottomF = 0.02f,
            pageWidthPx = 1000f,
            pageHeightPx = 1000f,
            measureWord = { word, fontSp -> word.length * fontSp * 0.6f },
            spaceWidth = { fontSp -> fontSp * 0.6f },
            lineHeightPx = { fontSp -> fontSp * 1.25f },
        )
        assertNull(layout)
    }

    @Test
    fun `short text in a big bubble gets a large font`() {
        val layout = fitOval("AHOJ")
        assertNotNull(layout)
        assertTrue("expected a large font for short text in a big oval, got ${layout!!.fontSp}", layout.fontSp > 20f)
    }

    // ── fitTextToShape: preferredFontSp (nativni velikost originalu) ──

    @Test
    fun `uses the preferred size exactly when it fits, instead of maximizing`() {
        // Bez preferredFontSp by kratky text v prostornem ovalu dorostl pres 20sp (viz test
        // vys) - s preferredFontSp ma sednout presne na nativni velikost originalu.
        val layout = fitOval("AHOJ", preferredFontSp = 14f)
        assertNotNull(layout)
        assertEquals(14f, layout!!.fontSp, 0.01f)
    }

    @Test
    fun `shrinks below the preferred size when the translation does not fit at it`() {
        // Dost dlouhy text, aby se do 12 radku ovalu pri preferovanych 30sp nevesel (12 radku
        // po ~44 znacich pri 30sp a sirce ovalu 800px), a fitter tak musel zmensit.
        val longText = (1..60).joinToString(" ") { "SLOVOSLOVO$it" }
        val layout = fitOval(longText, preferredFontSp = 30f)
        assertNotNull(layout)
        assertTrue("expected a shrink below the preferred 30sp, got ${layout!!.fontSp}", layout.fontSp < 30f)
    }

    @Test
    fun `never grows past the preferred size even with room to spare`() {
        val layout = fitOval("AHOJ", preferredFontSp = 10f)
        assertNotNull(layout)
        assertTrue("must not exceed the preferred 10sp, got ${layout!!.fontSp}", layout.fontSp <= 10f + 0.01f)
    }

    @Test
    fun `the preferred size still never breaks a word apart`() {
        // I s velkou preferovanou velikosti musi zustat cele slovo na jednom radku.
        val layout = fitOval("NEJNEPRAVDEPODOBNEJSIMI", preferredFontSp = 34f)
        assertNotNull(layout)
        assertEquals(listOf("NEJNEPRAVDEPODOBNEJSIMI"), layout!!.lines)
    }

    @Test
    fun `omitting preferredFontSp keeps the old maximize behavior`() {
        val layout = fitOval("AHOJ")
        assertNotNull(layout)
        assertTrue("expected the old maximize behavior when preferredFontSp is null, got ${layout!!.fontSp}", layout.fontSp > 20f)
    }

    // ── fitTextToShape: onCapProbe (viz estimateNativeFontPx audit) ──

    @Test
    fun `onCapProbe reports room to grow when the oval has plenty of space left`() {
        var probedPreferred: Float? = null
        var probedRoomToGrow: Boolean? = null
        val layout = fitOval(
            "AHOJ",
            preferredFontSp = 10f,
            onCapProbe = { preferred, roomToGrow -> probedPreferred = preferred; probedRoomToGrow = roomToGrow },
        )
        assertNotNull(layout)
        assertEquals(10f, layout!!.fontSp, 0.01f)
        assertEquals(10f, probedPreferred)
        assertEquals(true, probedRoomToGrow)
    }

    @Test
    fun `onCapProbe reports no room to grow when the preferred size is the true limit`() {
        // Plochy obdelnik (ne oval) drzi sirku radku konstantni napric celou vyskou, takze jde
        // spocitat presnou hranici: slovo "AHOJ" (4 znaky) sirokr 2.4*fontSp - pri 10sp 24px,
        // pri 10.25sp 24.6px. Sirka tvaru 24.3px je schvalne mezi nimi.
        val rectShape = listOf(
            BubbleShapePoint(0.0f, 0.5f - 0.1215f, 0.5f + 0.1215f),
            BubbleShapePoint(1.0f, 0.5f - 0.1215f, 0.5f + 0.1215f),
        )
        var probedRoomToGrow: Boolean? = null
        val layout = fitTextToShape(
            words = listOf("AHOJ"),
            minFontSp = 6f,
            maxFontSp = 36f,
            shape = rectShape,
            centerF = 0.5f,
            shapeTopF = 0f,
            shapeBottomF = 1f,
            pageWidthPx = 100f,
            pageHeightPx = 1000f,
            measureWord = { word, fontSp -> word.length * fontSp * 0.6f },
            spaceWidth = { fontSp -> fontSp * 0.6f },
            lineHeightPx = { fontSp -> fontSp * 1.25f },
            preferredFontSp = 10f,
            onCapProbe = { _, roomToGrow -> probedRoomToGrow = roomToGrow },
        )
        assertNotNull(layout)
        assertEquals(10f, layout!!.fontSp, 0.01f)
        assertEquals(false, probedRoomToGrow)
    }

    @Test
    fun `onCapProbe is not called when the result shrinks below the preferred size`() {
        var called = false
        val longText = (1..60).joinToString(" ") { "SLOVOSLOVO$it" }
        fitOval(longText, preferredFontSp = 30f, onCapProbe = { _, _ -> called = true })
        assertEquals(false, called)
    }

    // ── maxLineWidthPx (strop podle skutečného boxu, do kterého se text kreslí) ──

    /** Široký hranatý tvar (popiskový rámeček) - obrys 900 px na 1000px stránce. */
    private fun wideBoxShape() = (0..8).map { BubbleShapePoint(it / 8f, 0.05f, 0.95f) }

    private fun fitWideBox(words: List<String>, maxLineWidthPx: Float = Float.MAX_VALUE) =
        fitTextToShape(
            words = words,
            minFontSp = 6f,
            maxFontSp = 36f,
            shape = wideBoxShape(),
            centerF = 0.5f,
            shapeTopF = 0f,
            shapeBottomF = 1f,
            pageWidthPx = 1000f,
            pageHeightPx = 1000f,
            measureWord = { word, fontSp -> word.length * fontSp * 0.6f },
            spaceWidth = { fontSp -> fontSp * 0.6f },
            lineHeightPx = { fontSp -> fontSp * 1.25f },
            maxLineWidthPx = maxLineWidthPx,
        )

    @Test
    fun `never lays out wider than the box the text is actually rendered into`() {
        // Presne pripad z uzivatelskeho screenshotu: hranaty popiskovy ramecek, jehoz OBRYS je
        // siroky pres skoro celou stranku, ale Text composable dostane jen uzky box podle OCR
        // rozsahu. Bez maxLineWidthPx sazba prosla ("slovo se do obrysu vejde") a Compose pak
        // "SPOLECNOST" rozsekl po pismenech na "SPOLECNOS" + "T".
        val renderWidth = 200f
        val layout = fitWideBox(listOf("OBCHODNI", "SPOLECNOST"), maxLineWidthPx = renderWidth)

        assertNotNull(layout)
        layout!!.lines.forEach { line ->
            val width = line.length * layout.fontSp * 0.6f
            assertTrue("line '$line' is ${width}px, over the ${renderWidth}px render box", width <= renderWidth + 0.5f)
        }
        // A slova musi zustat cela - zadne "SPOLECNOS" + "T".
        assertEquals("OBCHODNI SPOLECNOST", layout.lines.joinToString(" "))
    }

    @Test
    fun `without the render cap the shape alone would allow a much wider line`() {
        // Kontrolni protipol predchoziho testu - dokazuje, ze strop opravdu neco meni.
        val layout = fitWideBox(listOf("OBCHODNI", "SPOLECNOST"))

        assertNotNull(layout)
        val widest = layout!!.lines.maxOf { it.length * layout.fontSp * 0.6f }
        assertTrue("without a cap the layout should use the full shape width, got $widest", widest > 200f)
    }

    @Test
    fun `a render cap narrower than the longest word makes the fit shrink, not chop`() {
        val layout = fitWideBox(listOf("NEJNEPRAVDEPODOBNEJSIMI"), maxLineWidthPx = 120f)

        assertNotNull(layout)
        assertEquals(listOf("NEJNEPRAVDEPODOBNEJSIMI"), layout!!.lines)
        assertTrue(
            "word must fit the render box at the chosen size, got ${23 * layout.fontSp * 0.6f}",
            23 * layout.fontSp * 0.6f <= 120.5f,
        )
    }

    // -- Ocasek bubliny: text se nesmi centrovat na obalovy obdelnik tvaru --------

    /**
     * Bublina s ocaskem: siroky lalok nahore (yF 0.10..0.40) a uzky ocasek pod nim
     * (yF 0.42..0.70), ktery ukazuje na mluvciho.
     *
     * Flood-fill vrati obrys VCETNE ocasku, takze obalovy obdelnik tvaru sahá az k 0.70 a jeho
     * stred lezi na 0.40 - tedy presne v hrdle, kde uz je bublina uzka. Text vycentrovany na
     * tenhle stred se od skutecneho laloku odtahne a musi se navic zlomit do vic uzkych radku.
     *
     * Zmereno na nahlasene strance (1440x3120): horni lalok mel tvar y=0.488..0.645 (stred
     * 0.567), ale vepsany obdelnik y=0.559..0.645 (stred 0.602) a puvodni text y=0.572..0.627
     * (stred 0.600). Sazba tedy mirila o 0,033 vysky stranky vys, nez kde text v originale byl.
     */
    private fun tailedShape(): List<BubbleShapePoint> {
        val lobe = (0..8).map { i ->
            BubbleShapePoint(yF = 0.10f + 0.30f * i / 8f, leftF = 0.10f, rightF = 0.90f)
        }
        val tail = (0..6).map { i ->
            BubbleShapePoint(yF = 0.42f + 0.28f * i / 6f, leftF = 0.47f, rightF = 0.53f)
        }
        return lobe + tail
    }

    private fun fitTailed(text: String, centerYF: Float? = null) =
        fitTextToShape(
            words = text.split(" ").filter { it.isNotBlank() },
            minFontSp = 6f,
            maxFontSp = 36f,
            shape = tailedShape(),
            centerF = 0.5f,
            shapeTopF = 0.10f,
            shapeBottomF = 0.70f,
            pageWidthPx = 1000f,
            pageHeightPx = 1000f,
            measureWord = { word, fontSp -> word.length * fontSp * 0.6f },
            spaceWidth = { fontSp -> fontSp * 0.6f },
            lineHeightPx = { fontSp -> fontSp * 1.25f },
            centerYF = centerYF,
        )

    @Test
    fun `text centred on the lobe fits far better than text centred on the whole shape`() {
        val text = "AAAA BBBB CCCC"
        val onWholeShape = fitTailed(text)
        val onLobe = fitTailed(text, centerYF = 0.25f)

        assertNotNull(onWholeShape)
        assertNotNull(onLobe)
        assertTrue(
            "sazba do laloku (${onLobe!!.fontSp}) musi vyjit vetsim pismem nez sazba " +
                "vycentrovana na obalovy obdelnik vcetne ocasku (${onWholeShape!!.fontSp})",
            onLobe.fontSp > onWholeShape.fontSp,
        )
    }

    @Test
    fun `centring on the lobe keeps every word intact`() {
        val text = "AAAA BBBB CCCC"
        val layout = fitTailed(text, centerYF = 0.25f)
        assertNotNull(layout)
        assertEquals(text, layout!!.lines.joinToString(" "))
    }

    @Test
    fun `a centre outside the shape is pulled back inside so the text never leaves the bubble`() {
        // Vepsany obdelnik muze u degenerovaneho tvaru vyjit mimo - blok se pak musi zarazit
        // zpatky do obrysu, ne vysazet mimo bublinu.
        val layout = fitTailed("AAAA BBBB", centerYF = 0.95f)
        assertNotNull(layout)
        assertEquals("AAAA BBBB", layout!!.lines.joinToString(" "))
    }

    @Test
    fun `the layout reports the height it actually centred the block on`() {
        // Vykresleni se ridi TIMHLE cislem, ne tim, o co se zadalo - sirky radku plati prave
        // pro pas, kde blok lezi.
        val layout = fitTailed("AAAA BBBB", centerYF = 0.25f)
        assertNotNull(layout)
        assertEquals(0.25f, layout!!.centerYF, 0.001f)
    }

    @Test
    fun `a block pushed past the bottom edge is reported at the height it was pulled back to`() {
        val layout = fitTailed("AAAA BBBB", centerYF = 0.95f)
        assertNotNull(layout)
        assertTrue(
            "blok se musel zarazit zpatky do obrysu, hlasi ${layout!!.centerYF}",
            layout.centerYF < 0.95f && layout.centerYF <= 0.70f,
        )
    }

    @Test
    fun `without an explicit centre the block stays in the middle of the shape as before`() {
        val layout = fitTailed("AAAA BBBB")
        assertNotNull(layout)
        // Stred obalového obdelniku tvaru (0.10..0.70) je 0.40 - stare chovani.
        assertEquals(0.40f, layout!!.centerYF, 0.02f)
    }
}

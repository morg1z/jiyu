package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test [fitFontSizeToBox]/[largestInscribedRect]/[shapeWidthAtYF] (žádná Android/
 * Compose závislost) - [fakeMeasure] simuluje zalomení textu deterministicky (bez skutečného
 * TextMeasureru).
 *
 * Klíčový regresní případ: fitter NESMÍ přijmout velikost písma, při které se nejdelší slovo
 * nevejde do šířky - Compose takové slovo rozseká uprostřed po písmenech ("KDYBYCH" ->
 * "KDYB"/"YCH", viz uživatelská zpětná vazba) a všechny vzniklé řádky pak šířkový limit
 * splňují, takže bez explicitní kontroly vypadá zmrzačené rozvržení jako úspěch.
 */
class BubbleTextFitTest {

    /**
     * Zjednodušený, ale deterministický model zalomení - monospace odhad šířky znaku.
     * Napodobuje i chování Compose při příliš úzké šířce: slovo, které se nevejde, rozseká
     * po znacích (přesně to, co produkuje nahlášený bug).
     */
    private fun fakeMeasure(text: String, fontSp: Float, maxWidthPx: Float): TextMeasurement {
        val charWidth = fontSp * 0.6f
        val lineHeight = fontSp * 1.25f
        val maxCharsPerLine = (maxWidthPx / charWidth).toInt().coerceAtLeast(1)

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(" ").filter { it.isNotBlank() }) {
            // Slovo delší než celý řádek - Compose ho rozseká po znacích.
            if (word.length > maxCharsPerLine) {
                if (current.isNotEmpty()) { lines += current.toString(); current = StringBuilder() }
                word.chunked(maxCharsPerLine).forEach { lines += it }
                continue
            }
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length > maxCharsPerLine && current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()

        val lineMetrics = lines.mapIndexed { i, line ->
            LineMetrics(widthPx = line.length * charWidth, topPx = i * lineHeight, bottomPx = (i + 1) * lineHeight)
        }
        val longestWord = text.split(" ").filter { it.isNotBlank() }.maxOfOrNull { it.length } ?: 0
        return TextMeasurement(
            totalHeightPx = lines.size * lineHeight,
            lines = lineMetrics,
            longestWordWidthPx = longestWord * charWidth,
        )
    }

    // ── shapeWidthAtYF ──

    @Test
    fun `shapeWidthAtYF interpolates width between sample points`() {
        val shape = listOf(
            BubbleShapePoint(yF = 0.0f, leftF = 0.3f, rightF = 0.5f), // width 0.2
            BubbleShapePoint(yF = 1.0f, leftF = 0.2f, rightF = 0.8f), // width 0.6
        )
        assertEquals(0.2f, shapeWidthAtYF(shape, 0.0f), 0.001f)
        assertEquals(0.6f, shapeWidthAtYF(shape, 1.0f), 0.001f)
        assertEquals(0.4f, shapeWidthAtYF(shape, 0.5f), 0.02f)
    }

    @Test
    fun `shapeWidthAtYF clamps outside the sampled range`() {
        val shape = listOf(
            BubbleShapePoint(yF = 0.2f, leftF = 0.3f, rightF = 0.5f),
            BubbleShapePoint(yF = 0.8f, leftF = 0.2f, rightF = 0.8f),
        )
        assertEquals(0.2f, shapeWidthAtYF(shape, 0.0f), 0.001f)
        assertEquals(0.6f, shapeWidthAtYF(shape, 1.0f), 0.001f)
    }

    // ── largestInscribedRect ──

    @Test
    fun `inscribed rect of a plain rectangle shape is the shape itself`() {
        val shape = (0..10).map { i -> BubbleShapePoint(yF = i / 10f, leftF = 0.2f, rightF = 0.8f) }
        val rect = largestInscribedRect(shape)
        assertNotNull(rect)
        assertEquals(0.2f, rect!!.leftF, 0.001f)
        assertEquals(0.8f, rect.rightF, 0.001f)
        assertEquals(0.0f, rect.topF, 0.001f)
        assertEquals(1.0f, rect.bottomF, 0.001f)
    }

    @Test
    fun `inscribed rect never sticks out of a compound double-circle shape`() {
        // Horni kruh je uzsi a posunuty doleva, spodni sirsi - presne ten tvar, u ktereho
        // se drive text orizl zleva (viz uzivatelska zpetna vazba).
        val shape = listOf(
            BubbleShapePoint(0.00f, 0.30f, 0.60f),
            BubbleShapePoint(0.20f, 0.28f, 0.62f),
            BubbleShapePoint(0.40f, 0.35f, 0.65f), // "pas" mezi kruhy
            BubbleShapePoint(0.60f, 0.12f, 0.88f),
            BubbleShapePoint(0.80f, 0.10f, 0.90f),
            BubbleShapePoint(1.00f, 0.15f, 0.85f),
        )
        val rect = largestInscribedRect(shape)
        assertNotNull(rect)

        // Kazdy vzorek obrysu, ktery lezi ve svislem rozsahu obdelniku, musi obdelnik cely obsahovat.
        for (p in shape) {
            if (p.yF < rect!!.topF - 1e-4f || p.yF > rect.bottomF + 1e-4f) continue
            assertTrue(
                "inscribed rect left ${rect.leftF} sticks out past shape left ${p.leftF} at yF=${p.yF}",
                rect.leftF >= p.leftF - 1e-4f,
            )
            assertTrue(
                "inscribed rect right ${rect.rightF} sticks out past shape right ${p.rightF} at yF=${p.yF}",
                rect.rightF <= p.rightF + 1e-4f,
            )
        }
    }

    @Test
    fun `inscribed rect prefers the roomy lower circle over the cramped upper one`() {
        val shape = listOf(
            BubbleShapePoint(0.00f, 0.40f, 0.55f), // uzky horni kruh
            BubbleShapePoint(0.30f, 0.40f, 0.55f),
            BubbleShapePoint(0.50f, 0.10f, 0.90f), // siroky spodni kruh
            BubbleShapePoint(1.00f, 0.10f, 0.90f),
        )
        val rect = largestInscribedRect(shape)
        assertNotNull(rect)
        assertTrue("expected the rect to sit in the roomy lower half, got $rect", rect!!.topF >= 0.5f - 1e-4f)
        assertTrue("expected a wide rect from the lower circle, got width ${rect.widthF}", rect.widthF > 0.5f)
    }

    @Test
    fun `degenerate shape yields no inscribed rect`() {
        assertEquals(null, largestInscribedRect(emptyList()))
        assertEquals(null, largestInscribedRect(listOf(BubbleShapePoint(0.5f, 0.2f, 0.8f))))
    }

    // ── fitFontSizeToBox ──

    @Test
    fun `never picks a font size that would character-break the longest word`() {
        // Reprodukuje hlavni nahlaseny bug: "KDYBYCH" rozsekane na "KDYB"/"YCH".
        val text = "KDYBYCH VĚDĚL JAKÁ TA CESTA BUDE"
        val boxWidthPx = 120f
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = boxWidthPx,
            maxHeightPx = 400f,
            measure = { fontSp, maxW -> fakeMeasure(text, fontSp, maxW) },
        )

        // Pri zvolene velikosti se nejdelsi slovo musi vejit VCELKU do sirky.
        val finalMeasurement = fakeMeasure(text, result.fontSp, result.widthPx)
        assertTrue(
            "longest word (${finalMeasurement.longestWordWidthPx}px) must fit within box width $boxWidthPx at chosen font ${result.fontSp}",
            finalMeasurement.longestWordWidthPx <= boxWidthPx + 0.5f,
        )
    }

    @Test
    fun `grows font size well beyond the old fixed 11sp cap when the box has plenty of room`() {
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 600f,
            maxHeightPx = 600f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
        )
        assertTrue("expected font to grow well beyond the old fixed 11sp cap, got ${result.fontSp}", result.fontSp > 20f)
    }

    @Test
    fun `still shrinks long text down in a small box`() {
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 120f,
            maxHeightPx = 60f,
            measure = { fontSp, maxW -> fakeMeasure("TOHLE JE DLOUHY PREKLAD CO SE MUSI VEJIT DO MALE BUBLINY", fontSp, maxW) },
        )
        assertTrue("expected small font in a tiny box with long text, got ${result.fontSp}", result.fontSp < 20f)
    }

    @Test
    fun `returns the given box width unchanged - text area is decided by the caller`() {
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 480f,
            maxHeightPx = 300f,
            measure = { fontSp, maxW -> fakeMeasure("KRATKY TEXT", fontSp, maxW) },
        )
        assertEquals(480f, result.widthPx, 0.001f)
    }

    @Test
    fun `a single very long word forces a small font rather than being chopped up`() {
        // Jedno extremne dlouhe slovo v uzke bublinne - jedina spravna reakce je zmensit
        // pismo tak, aby se veslo vcelku, ne ho rozsekat.
        val text = "NEJNEPRAVDEPODOBNEJSIMI"
        val boxWidthPx = 100f
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = boxWidthPx,
            maxHeightPx = 400f,
            measure = { fontSp, maxW -> fakeMeasure(text, fontSp, maxW) },
        )
        val finalMeasurement = fakeMeasure(text, result.fontSp, result.widthPx)
        assertTrue(
            "single long word must fit whole (${finalMeasurement.longestWordWidthPx}px vs $boxWidthPx px at ${result.fontSp}sp)",
            finalMeasurement.longestWordWidthPx <= boxWidthPx + 0.5f,
        )
    }

    // ── fitFontSizeToBox: preferredFontSp (nativni velikost originalu) ──

    @Test
    fun `uses the preferred size exactly when it fits, instead of maximizing`() {
        // Bublina ma spoustu mista (600x600), takze bez preferredFontSp by fitter vybral
        // velikost hluboko pres 20sp (viz test vys) - s preferredFontSp ma sedet presne na
        // nativni velikosti originalu, ne se nafouknout na maximum jen proto, ze je misto.
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 600f,
            maxHeightPx = 600f,
            preferredFontSp = 14f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
        )
        assertEquals(14f, result.fontSp, 0.01f)
    }

    @Test
    fun `shrinks below the preferred size when the translation does not fit at it`() {
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 120f,
            maxHeightPx = 60f,
            preferredFontSp = 24f,
            measure = { fontSp, maxW -> fakeMeasure("TOHLE JE DLOUHY PREKLAD CO SE MUSI VEJIT DO MALE BUBLINY", fontSp, maxW) },
        )
        assertTrue("expected a shrink below the preferred 24sp, got ${result.fontSp}", result.fontSp < 24f)
    }

    @Test
    fun `never grows past the preferred size even with room to spare`() {
        // I kdyby se do bubliny vesla i vetsi velikost (viz "grows font size..." test vys,
        // kde stejny text s timhle boxem dorostl pres 20sp), preferovana velikost je STROP -
        // cilem je vizualne sednout na original, ne vyuzit kazdy volny pixel.
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 600f,
            maxHeightPx = 600f,
            preferredFontSp = 10f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
        )
        assertTrue("must not exceed the preferred 10sp, got ${result.fontSp}", result.fontSp <= 10f + 0.01f)
    }

    @Test
    fun `a preferred size above the absolute max is clamped down to it`() {
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 20f,
            boxWidthPx = 600f,
            maxHeightPx = 600f,
            preferredFontSp = 99f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
        )
        assertTrue("expected the clamp to the absolute max 20sp, got ${result.fontSp}", result.fontSp <= 20f + 0.01f)
    }

    @Test
    fun `the preferred size still respects the no-character-break rule`() {
        // Preferovana velikost nesmi obejit ochranu proti rozseknuti dlouheho slova - viz
        // hlavni regresni test tridy.
        val text = "NEJNEPRAVDEPODOBNEJSIMI"
        val boxWidthPx = 100f
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = boxWidthPx,
            maxHeightPx = 400f,
            preferredFontSp = 30f, // pri 30sp by se slovo rozseklo
            measure = { fontSp, maxW -> fakeMeasure(text, fontSp, maxW) },
        )
        val finalMeasurement = fakeMeasure(text, result.fontSp, result.widthPx)
        assertTrue(
            "longest word must still fit whole even with a large preferred size (${finalMeasurement.longestWordWidthPx}px vs $boxWidthPx at ${result.fontSp}sp)",
            finalMeasurement.longestWordWidthPx <= boxWidthPx + 0.5f,
        )
    }

    // ── fitFontSizeToBox: onCapProbe (viz estimateNativeFontPx audit) ──

    @Test
    fun `onCapProbe reports room to grow when the box has plenty of space left`() {
        var probedPreferred: Float? = null
        var probedRoomToGrow: Boolean? = null
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 600f,
            maxHeightPx = 600f,
            preferredFontSp = 10f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
            onCapProbe = { preferred, roomToGrow -> probedPreferred = preferred; probedRoomToGrow = roomToGrow },
        )
        assertEquals(10f, result.fontSp, 0.01f)
        assertEquals(10f, probedPreferred)
        assertEquals(true, probedRoomToGrow)
    }

    @Test
    fun `onCapProbe reports no room to grow when the preferred size is the true limit`() {
        // Vyska boxu (12.6px) je schvalne mezi vyskou pri 10sp (12.5px, vejde se) a 10.25sp
        // (12.8125px, nevejde se) - preferovana velikost je tedy skutecny strop, ne jen
        // nahodou dosazeny.
        var probedRoomToGrow: Boolean? = null
        fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 600f,
            maxHeightPx = 12.6f,
            preferredFontSp = 10f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
            onCapProbe = { _, roomToGrow -> probedRoomToGrow = roomToGrow },
        )
        assertEquals(false, probedRoomToGrow)
    }

    @Test
    fun `onCapProbe is not called when the result shrinks below the preferred size`() {
        var called = false
        fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 120f,
            maxHeightPx = 60f,
            preferredFontSp = 24f,
            measure = { fontSp, maxW -> fakeMeasure("TOHLE JE DLOUHY PREKLAD CO SE MUSI VEJIT DO MALE BUBLINY", fontSp, maxW) },
            onCapProbe = { _, _ -> called = true },
        )
        assertEquals(false, called)
    }

    @Test
    fun `omitting preferredFontSp keeps the old maximize behavior`() {
        val result = fitFontSizeToBox(
            minFontSp = 6f,
            maxFontSp = 36f,
            boxWidthPx = 600f,
            maxHeightPx = 600f,
            measure = { fontSp, maxW -> fakeMeasure("UZ JDOU", fontSp, maxW) },
        )
        assertTrue("expected the old maximize behavior when preferredFontSp is null, got ${result.fontSp}", result.fontSp > 20f)
    }

    // ── minTranslationFontSp: podlaha velikosti písma ──

    @Test
    fun `a bigger reader text setting never raises the font floor`() {
        // JÁDRO NÁLEZU. Podlaha se dřív počítala jako 6 * textScale, takže na maximu posuvníku
        // (1,6) vycházela na 9,6 sp. Do drobné bubliny se takové písmo nevejde ani teoreticky,
        // fitter nemá kam ustoupit, vrátí podlahu - a přebytek OŘÍZNE obrys bubliny při
        // vykreslení. Zvětšení textu v nastavení tedy text z malých bublin mazalo.
        val atDefault = minTranslationFontSp(1.0f)
        assertEquals("největší nastavení nesmí podlahu zvednout", atDefault, minTranslationFontSp(1.6f), 0.001f)
        assertEquals("ani mírné zvětšení", atDefault, minTranslationFontSp(1.2f), 0.001f)
    }

    @Test
    fun `a smaller reader text setting is allowed to lower the floor`() {
        // Opačný směr je legitimní: kdo si písmo zmenšuje, chce menší text, ne nouzové řešení.
        assertTrue(minTranslationFontSp(0.7f) < minTranslationFontSp(1.0f))
    }

    @Test
    fun `the floor stays low enough for a tiny bubble to fit`() {
        // Drobná myšlenková bublinka z nahlášené stránky: text se vejde jen pod 6 sp, což byla
        // stará podlaha. S ní fitter vracel velikost, která se NEVEJDE (a ořízla se) - test
        // proto neověřuje konkrétní číslo, ale to, co na tom uživateli doopravdy záleží:
        // vrácená velikost se musí vejít.
        val text = "V POSLEDNICH DNECH."
        val boxWidthPx = 46f
        val maxHeightPx = 60f
        val result = fitFontSizeToBox(
            minFontSp = minTranslationFontSp(1.6f),
            maxFontSp = 36f * 1.6f,
            boxWidthPx = boxWidthPx,
            maxHeightPx = maxHeightPx,
            measure = { fontSp, maxW -> fakeMeasure(text, fontSp, maxW) },
        )
        val measured = fakeMeasure(text, result.fontSp, boxWidthPx)
        assertTrue(
            "text musí zůstat uvnitř bubliny, jinak ho obrys ořízne (${measured.totalHeightPx}px vs $maxHeightPx při ${result.fontSp}sp)",
            measured.totalHeightPx <= maxHeightPx,
        )
        assertTrue(
            "nejdelší slovo se musí vejít vcelku (${measured.longestWordWidthPx}px vs $boxWidthPx)",
            measured.longestWordWidthPx <= boxWidthPx + 0.5f,
        )
    }

    @Test
    fun `the floor never goes below the absolute minimum`() {
        // Pojistka proti přestřelení druhým směrem: pod tuhle mez už je text nečitelný a menší
        // písmo by problém neřešilo, jen přesunulo.
        assertTrue(minTranslationFontSp(0.1f) >= ABSOLUTE_MIN_FONT_SP * 0.1f)
        assertTrue(minTranslationFontSp(1.6f) <= ABSOLUTE_MIN_FONT_SP)
    }
}

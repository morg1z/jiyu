package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test rozhodování "je tohle použitelný překlad?".
 *
 * Cíl 1: když model bublinu vynechá nebo vrátí prázdno, NESMÍ se do ní vysázet anglický
 * originál jako plnohodnotný překlad - viz [TranslationMerge] a uživatelské screenshoty
 * s "THE FIRST PLACE." v české bublině.
 *
 * Cíl 2: když model pod velkou dávkou (víc stránek najednou) posune číslování "id", NESMÍ
 * appka slepě aplikovat výsledek na jinou bublinu, než pro kterou byl určen - viz uživatelská
 * zpětná vazba (bublina "NOT LIKE THAT." zobrazila text patřící jiné bublině na jiné stránce).
 */
class TranslationMergeTest {

    /** Výchozí "original" echo odpovídá výchozímu textu bubliny ([classified]) - testy mismatch mají vlastní. */
    private fun bubble(id: Int, translated: String, original: String = "expected text") = GeminiBubbleTranslation(
        id = id,
        original = original,
        translated = translated,
        bubbleSizeTag = "MEDIUM",
        isSfx = false,
        syllableBreaks = "",
    )

    private fun classified(text: String = "expected text", isSfx: Boolean = false) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f),
        sizeTag = SizeTag.MEDIUM,
        bubbleType = if (isSfx) BubbleType.SFX else BubbleType.SPEECH,
        isSfx = isSfx,
        lineCount = 1,
    )

    // ── originalMatches ──

    @Test
    fun `identical text matches`() {
        assertTrue(originalMatches("Or I would have hired at least ten guards.", "Or I would have hired at least ten guards."))
    }

    @Test
    fun `minor whitespace and quote normalization still matches`() {
        assertTrue(originalMatches("Or I would have hired at least ten guards", "Or I would have hired at least ten guards."))
    }

    @Test
    fun `a completely different sentence does not match`() {
        // Presne uzivatelsky pripad: bublina "NOT LIKE THAT." dostala preklad patrici
        // bubline "RAISE YOUR ARM HIGHER FOR THAT PART." - temer zadny prekryv slov.
        assertFalse(originalMatches("Raise your arm higher for that part.", "Not like that."))
    }

    @Test
    fun `a short fragment is not vetoed for lack of comparable words`() {
        // Prilis kratky text (jen kratka spojova slova) neda spolehlivy signal - radsi
        // nezamitat nez zbytecne zahazovat platny preklad kratke repliky.
        assertTrue(originalMatches("So it is.", "Or so."))
    }

    @Test
    fun `partial overlap above the threshold still matches`() {
        // Model muze mirne prepsat/zkratit echo - pokud vetsina vyznamovych slov sedi,
        // neni duvod to brat jako spatne cislovani.
        assertTrue(originalMatches("I could break out of this trap for sure.", "I could definitely break out of this trap."))
    }

    // ── isUsableTranslation ──

    @Test
    fun `a real translation with matching original is usable`() {
        assertTrue(isUsableTranslation(bubble(0, "Ahoj"), "expected text"))
    }

    @Test
    fun `a missing entry is not usable`() {
        assertFalse(isUsableTranslation(null, "expected text"))
    }

    @Test
    fun `an empty translation is not usable`() {
        assertFalse(isUsableTranslation(bubble(0, ""), "expected text"))
    }

    @Test
    fun `a whitespace only translation is not usable`() {
        assertFalse(isUsableTranslation(bubble(0, "   \n "), "expected text"))
    }

    @Test
    fun `the untranslated marker is not usable`() {
        assertFalse(isUsableTranslation(bubble(0, GeminiUltraPrompt.UNTRANSLATED_MARKER), "expected text"))
    }

    @Test
    fun `a translation whose echoed original does not match is not usable`() {
        // Model odpovedel na "id 0", ale jeho echo "original" patri uplne jine replice -
        // cislovani se pod velkou davkou posunulo, preklad patri jine bubline.
        val mismatched = bubble(0, "Zvedni vice tu ruku.", original = "Raise your arm higher for that part.")
        assertFalse(isUsableTranslation(mismatched, "Not like that."))
    }

    // ── missingTranslationIndices ──

    @Test
    fun `nothing is missing when the model answered every bubble`() {
        val classified = listOf(classified("A"), classified("B"))
        val byId = mapOf(0 to bubble(0, "Á", original = "A"), 1 to bubble(1, "Bé", original = "B"))
        assertEquals(emptyList<Int>(), missingTranslationIndices(classified, byId))
    }

    @Test
    fun `a skipped bubble is reported as missing`() {
        val classified = listOf(classified("A"), classified("B"), classified("C"))
        val byId = mapOf(0 to bubble(0, "Á", original = "A"), 2 to bubble(2, "Cé", original = "C"))
        assertEquals(listOf(1), missingTranslationIndices(classified, byId))
    }

    @Test
    fun `a blank translation is reported as missing`() {
        val classified = listOf(classified("A"), classified("B"))
        val byId = mapOf(0 to bubble(0, "Á", original = "A"), 1 to bubble(1, "  ", original = "B"))
        assertEquals(listOf(1), missingTranslationIndices(classified, byId))
    }

    @Test
    fun `sfx bubbles are never reported as missing`() {
        // SFX se schvalne neprekladaji - chybejici odpoved u nich neni chyba.
        val classified = listOf(classified("BOOM", isSfx = true), classified("A"))
        val byId = mapOf(1 to bubble(1, "Á", original = "A"))
        assertEquals(emptyList<Int>(), missingTranslationIndices(classified, byId))
    }

    @Test
    fun `a deliberate untranslated marker is not retried`() {
        // Model uz jednou vedome rekl "tohle neprelozim" - opakovany dotaz by jen stal request.
        val classified = listOf(classified("???"))
        val byId = mapOf(0 to bubble(0, GeminiUltraPrompt.UNTRANSLATED_MARKER, original = "???"))
        assertEquals(emptyList<Int>(), missingTranslationIndices(classified, byId))
    }

    @Test
    fun `a bubble whose id drifted to a different bubble is reported as missing`() {
        // Presne uzivatelsky scenar: byId[0] odpovida, ale echovany "original" patri
        // uplne jine replice - id se pod velkou davkou posunulo.
        val classified = listOf(classified("Not like that."))
        val byId = mapOf(0 to bubble(0, "Zvedni vice tu ruku.", original = "Raise your arm higher for that part."))
        assertEquals(listOf(0), missingTranslationIndices(classified, byId))
    }

    // ── mergeRetry ──

    @Test
    fun `retry fills in the bubble the first pass skipped`() {
        val classified = listOf(classified("A"), classified("B"))
        val byId = mapOf(0 to bubble(0, "Á", original = "A"))
        // Opravny dotaz poslal jen bublinu c. 1, takze v jeho odpovedi ma id 0.
        val retry = GeminiTranslationResponse(bubbles = listOf(bubble(0, "Bé", original = "B")))

        val merged = mergeRetry(byId, retriedIndices = listOf(1), retryResponse = retry, classified = classified)

        assertEquals("Bé", merged[1]?.translated)
        assertEquals("Á", merged[0]?.translated)
    }

    @Test
    fun `retry ids are mapped back to the original positions`() {
        val classified = List(8) { classified("text$it") }
        val retry = GeminiTranslationResponse(
            bubbles = listOf(bubble(0, "prvni", original = "text3"), bubble(1, "druhy", original = "text7")),
        )

        val merged = mergeRetry(emptyMap(), retriedIndices = listOf(3, 7), retryResponse = retry, classified = classified)

        assertEquals("prvni", merged[3]?.translated)
        assertEquals("druhy", merged[7]?.translated)
        assertEquals(2, merged.size)
    }

    @Test
    fun `an unusable retry answer does not overwrite a good one`() {
        val classified = listOf(classified("A"), classified("B"), classified("dobry"))
        val byId = mapOf(2 to bubble(2, "dobry preklad", original = "dobry"))
        val retry = GeminiTranslationResponse(bubbles = listOf(bubble(0, "")))

        val merged = mergeRetry(byId, retriedIndices = listOf(2), retryResponse = retry, classified = classified)

        assertEquals("dobry preklad", merged[2]?.translated)
    }

    @Test
    fun `a retry answer with a mismatched echo does not overwrite a good one`() {
        // I opravny dotaz muze cislovani zamotat znovu - i tady se echo overuje.
        val classified = listOf(classified("Not like that."), classified("dobry"))
        val byId = mapOf(1 to bubble(1, "dobry preklad", original = "dobry"))
        val retry = GeminiTranslationResponse(
            bubbles = listOf(bubble(0, "Zvedni vice tu ruku.", original = "Raise your arm higher for that part.")),
        )

        val merged = mergeRetry(byId, retriedIndices = listOf(0), retryResponse = retry, classified = classified)

        assertEquals(null, merged[0]?.translated)
        assertEquals("dobry preklad", merged[1]?.translated)
    }

    @Test
    fun `a failed retry leaves the original map untouched`() {
        val classified = listOf(classified("A"), classified("B"))
        val byId = mapOf(0 to bubble(0, "Á", original = "A"))
        assertEquals(byId, mergeRetry(byId, retriedIndices = listOf(1), retryResponse = null, classified = classified))
    }

    @Test
    fun `an out of range retry id is ignored rather than crashing`() {
        val classified = listOf(classified("A"))
        val retry = GeminiTranslationResponse(bubbles = listOf(bubble(9, "mimo")))
        assertEquals(emptyMap<Int, GeminiBubbleTranslation>(), mergeRetry(emptyMap(), listOf(0), retry, classified))
    }

    // ── isSuspiciousVerbatimCopy (viz self-check audit) ──

    @Test
    fun `an untranslated sentence copied verbatim is suspicious`() {
        assertTrue(isSuspiciousVerbatimCopy("I would never sign that contract.", "I would never sign that contract."))
    }

    @Test
    fun `a real czech translation is not suspicious`() {
        assertFalse(isSuspiciousVerbatimCopy("I would never sign that contract.", "Tu smlouvu bych nikdy nepodepsal."))
    }

    @Test
    fun `only leading or trailing whitespace differing still counts as verbatim`() {
        assertTrue(isSuspiciousVerbatimCopy("Welcome.", "  Welcome.  "))
    }

    @Test
    fun `a short digit or symbol only bubble is not flagged`() {
        // Kratke/nepismenne bubliny (cislo stranky, "...", "!") se legitimne shoduji
        // bez ohledu na jazyk - nejde o znamku nedokonceneho prekladu.
        assertFalse(isSuspiciousVerbatimCopy("12", "12"))
        assertFalse(isSuspiciousVerbatimCopy("...", "..."))
    }

    @Test
    fun `a short name that happens to be identical in both languages is not flagged`() {
        // "Frodo" -> "Frodo" (jmena se neprekladaji, viz GeminiUltraPrompt) je legitimni
        // shoda, ne znamka zkopirovaneho textu - prah delky ji vyfiltruje.
        assertFalse(isSuspiciousVerbatimCopy("OK", "OK"))
    }

    @Test
    fun `case differences still count as verbatim`() {
        assertTrue(isSuspiciousVerbatimCopy("WATCH OUT!", "watch out!"))
    }
}

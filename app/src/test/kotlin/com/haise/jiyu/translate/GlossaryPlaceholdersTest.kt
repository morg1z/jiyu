package com.haise.jiyu.translate

import com.haise.jiyu.data.db.entity.GlossaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test substituce/obnovy "protectExact" glosářových pojmů (viz
 * [GlossaryEntity.protectExact] a [GlossaryPlaceholders]).
 */
class GlossaryPlaceholdersTest {

    private fun classified(text: String) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f),
        sizeTag = SizeTag.MEDIUM,
        bubbleType = BubbleType.SPEECH,
        isSfx = false,
        lineCount = 1,
    )

    private fun entry(sourceTerm: String, targetTerm: String, protectExact: Boolean = true) = GlossaryEntity(
        id = "manga::${sourceTerm.lowercase()}::Czech",
        mangaId = "manga",
        sourceTerm = sourceTerm,
        targetTerm = targetTerm,
        targetLanguage = "Czech",
        protectExact = protectExact,
    )

    // ── substitute ──

    @Test
    fun `no protected entries leaves classified bubbles untouched`() {
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, emptyList())
        assertSame(bubbles, substitution.classified)
    }

    @Test
    fun `a bubble without the protected term is left untouched`() {
        val bubbles = listOf(classified("Hello there."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo")))
        assertEquals("Hello there.", substitution.classified[0].raw.text)
    }

    @Test
    fun `a bubble containing the protected term is replaced with a token`() {
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo")))
        val text = substitution.classified[0].raw.text
        assertTrue("expected a placeholder token, got \"$text\"", text.startsWith("⟦JIYU_PROTECT_"))
        assertTrue(text.endsWith("went home."))
    }

    @Test
    fun `a longer term is substituted before a shorter term that is its substring`() {
        // "Frodo Baggins" musí být nahrazeno CELE, driv nez kratsi "Frodo" - jinak by
        // substituce kratsiho pojmu rozbila delsi pred tim, nez na nej prijde rada.
        val bubbles = listOf(classified("Frodo Baggins carried the ring."))
        val substitution = GlossaryPlaceholders.substitute(
            bubbles,
            listOf(entry("Frodo", "Frodo"), entry("Frodo Baggins", "Frodo Pytlík")),
        )
        val text = substitution.classified[0].raw.text
        assertTrue("expected the whole longer term replaced by exactly one token, got \"$text\"", Regex("^⟦JIYU_PROTECT_\\d+⟧ carried the ring\\.$").matches(text))
    }

    @Test
    fun `a bubble shouting the protected term in all caps is still protected`() {
        // Bez case-insensitive shody by "FRODO" (bezna stylizace kriku v manze) nikdy
        // nenaslo shodu s glosarovym "Frodo" a protectExact by ji tise nechranil.
        val bubbles = listOf(classified("FRODO WENT HOME."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo")))
        val text = substitution.classified[0].raw.text
        assertTrue("expected a placeholder token, got \"$text\"", text.startsWith("⟦JIYU_PROTECT_"))
        assertTrue(text.endsWith(" WENT HOME."))
    }

    // ── restoreResponse (echo-based Gemini path) ──

    @Test
    fun `restoreResponse restores the source term in the echoed original and the target term in the translation`() {
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo Pytlík")))
        val token = substitution.classified[0].raw.text.substringBefore(" went home.")

        val response = GeminiTranslationResponse(
            bubbles = listOf(
                GeminiBubbleTranslation(
                    id = 0,
                    original = "$token went home.",
                    translated = "$token šel domů.",
                    bubbleSizeTag = "MEDIUM",
                    isSfx = false,
                    syllableBreaks = "",
                ),
            ),
        )

        val restored = substitution.restoreResponse(response)

        assertEquals("Frodo went home.", restored.bubbles[0].original)
        assertEquals("Frodo Pytlík šel domů.", restored.bubbles[0].translated)
    }

    @Test
    fun `restoreResponse is a no-op when there were no protected entries`() {
        val bubbles = listOf(classified("Hello there."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, emptyList())
        val response = GeminiTranslationResponse(
            bubbles = listOf(
                GeminiBubbleTranslation(id = 0, original = "Hello there.", translated = "Ahoj.", bubbleSizeTag = "MEDIUM", isSfx = false, syllableBreaks = ""),
            ),
        )
        assertSame(response, substitution.restoreResponse(response))
    }

    @Test
    fun `a token returned with different letter case by the model still restores`() {
        // Levny/free-tier provider obcas vrati token s jinou velikosti pismen, nez dostal
        // (nahlaseno v auditu) - bez case-insensitive obnovy by token zustal syrovy v
        // konecnem prekladu misto skutecneho jmena.
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo Pytlík")))
        val token = substitution.classified[0].raw.text.substringBefore(" went home.")
        val mangledToken = token.lowercase()

        assertEquals("Frodo Pytlík šel domů.", substitution.restoreTranslatedOnly("$mangledToken šel domů."))
    }

    // ── restoreTranslatedOnly (position-based Groq path) ──

    @Test
    fun `restoreTranslatedOnly restores the target term in a plain translated string`() {
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo Pytlík")))
        val token = substitution.classified[0].raw.text.substringBefore(" went home.")

        assertEquals("Frodo Pytlík šel domů.", substitution.restoreTranslatedOnly("$token šel domů."))
    }

    // ── varianty tvaru tokenu, které model vrací místo ⟦⟧ ──

    @Test
    fun `a token rewritten to underscores by the model still restores`() {
        // PRODUKCNÍ SELHÁNÍ (zmereno na zarizeni): prompt dokumentoval stary tvar
        // "__JIYU_PROTECT_0__", takze model poslany "⟦JIYU_PROTECT_0⟧" "opravil" na
        // "__JIYU_PROTECT_0__" - presny restore ho nenasel, token protekl do render gate
        // a cela bublina skoncila jako nepřeložená, prestoze preklad byl spravny.
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo Pytlík")))

        assertEquals("Frodo Pytlík šel domů.", substitution.restoreTranslatedOnly("__JIYU_PROTECT_0__ šel domů."))
    }

    @Test
    fun `other token decoration variants restore too`() {
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo Pytlík")))

        for (variant in listOf("[JIYU_PROTECT_0]", "(JIYU_PROTECT_0)", "JIYU_PROTECT_0", "jiyu_protect_0", "⟦JIYU_PROTECT_0⟧", "【JIYU_PROTECT_0】")) {
            assertEquals("varianta \"$variant\" se neobnovila", "Frodo Pytlík šel domů.", substitution.restoreTranslatedOnly("$variant šel domů."))
        }
    }

    @Test
    fun `restoring a rewritten token keeps the punctuation glued after it`() {
        // Urcite nesmi utrhnout "?!" - dekorace se maji sezrat jen ze zname mnoziny
        // uzavieru, ne jako "libovolny neslovni znak".
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Voide")))

        assertEquals("NA CO ČEKÁŠ, Voide?!", substitution.restoreTranslatedOnly("NA CO ČEKÁŠ, __JIYU_PROTECT_0__?!"))
        assertEquals("NA CO ČEKÁŠ, Voide?!", substitution.restoreTranslatedOnly("NA CO ČEKÁŠ, ⟦JIYU_PROTECT_0⟧?!"))
    }

    @Test
    fun `a token with an out-of-range index is left untouched`() {
        // Konzervativni fallback: neznamy index se necha protec dal (render gate ho
        // zachyti jako leaked token) misto aby se do textu vnutila spatna nahrada.
        val bubbles = listOf(classified("Frodo went home."))
        val substitution = GlossaryPlaceholders.substitute(bubbles, listOf(entry("Frodo", "Frodo Pytlík")))

        val out = substitution.restoreTranslatedOnly("Zavolej __JIYU_PROTECT_7__ domů.")
        assertEquals("Zavolej __JIYU_PROTECT_7__ domů.", out)
        assertTrue(hasLeakedToken(out))
    }

    @Test
    fun `regression - the VOID shout bubble that stayed english on device`() {
        // Presny produkcni pripad: bublina "WHAT ARE YOU WAITING FOR, VOID?! ARE YOU JUST
        // GONNA BLOCK ALL DAY?!" s protectExact pojmem "Void" - model prelozil spravne, ale
        // vrátil "__JIYU_PROTECT_0__", restore selhal -> gate -> isUntranslated -> na
        // obrazovce zustala anglictina. Po oprave musi cely retezec projet az do pouzitelneho
        // prekladu (isUsableTranslation) bez leaked tokenu a bez glosaroveho poruseni.
        val original = "WHAT ARE YOU WAITING FOR, VOID?! ARE YOU JUST GONNA BLOCK ALL DAY?!"
        val substitution = GlossaryPlaceholders.substitute(
            listOf(classified(original)),
            listOf(entry("Void", "Void")),
        )
        // Kontrola substituce - model dostal token, ne "VOID".
        assertTrue(substitution.classified[0].raw.text.contains("⟦JIYU_PROTECT_0⟧"))
        assertTrue(!substitution.classified[0].raw.text.contains("VOID"))

        // Model (gemini, jak ho skutecne vratil na zarizeni): spravny preklad + prepsany token.
        val response = GeminiTranslationResponse(
            bubbles = listOf(
                GeminiBubbleTranslation(
                    id = 0,
                    original = "WHAT ARE YOU WAITING FOR, __JIYU_PROTECT_0__?! ARE YOU JUST GONNA BLOCK ALL DAY?!",
                    translated = "NA CO ČEKÁŠ, __JIYU_PROTECT_0__?! MÁŠ V PLÁNU JEN CELÝ DEN BLOKOVAT?!",
                    bubbleSizeTag = "LARGE",
                    isSfx = false,
                    syllableBreaks = "",
                ),
            ),
        )
        val restored = substitution.restoreResponse(response)
        val t = restored.bubbles[0]

        assertEquals("NA CO ČEKÁŠ, Void?! MÁŠ V PLÁNU JEN CELÝ DEN BLOKOVAT?!", t.translated)
        assertTrue("leaked token zůstal v překladu", !hasLeakedToken(t.translated))
        assertTrue("merge by bublinu zamítl jako nepoužitelnou", isUsableTranslation(t, original))
        assertTrue("glosářové porušení by se hlásilo", !isGlossaryViolation(original, t.translated, mapOf("Void" to "Void")))
    }
}

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
}

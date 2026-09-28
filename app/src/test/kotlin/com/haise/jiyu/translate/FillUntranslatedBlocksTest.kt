package com.haise.jiyu.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fillUntranslatedBlocks]: bubliny, které po celém řetězci providerů zůstaly nepřeložené (typicky při
 * přetíženém Gemini), se dopřeloží dalšími kroky - a jen ony, ne celá stránka znovu.
 */
class FillUntranslatedBlocksTest {

    private fun bubble(text: String, isSfx: Boolean = false) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f),
        sizeTag = SizeTag.MEDIUM,
        bubbleType = if (isSfx) BubbleType.SFX else BubbleType.SPEECH,
        isSfx = isSfx,
        lineCount = 1,
    )

    private fun translated(original: String, czech: String) = TranslatedBlock(
        originalText = original, translatedText = czech, leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f,
    )

    private fun untranslated(original: String) = TranslatedBlock(
        originalText = original, translatedText = original, leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f,
        isUntranslated = true,
    )

    @Test
    fun `only the untranslated bubbles are sent to the next step and merged back in place`() = runBlocking {
        val classified = listOf(bubble("One"), bubble("Two words"), bubble("Three"))
        val blocks = listOf(translated("One", "Jedna"), untranslated("Two words"), translated("Three", "Tři"))
        var sent: List<String> = emptyList()

        val result = fillUntranslatedBlocks(blocks, classified, listOf({ bubbles ->
            sent = bubbles.map { it.raw.text }
            listOf(translated("Two words", "Dvě slova"))
        }))

        assertEquals(listOf("Two words"), sent)
        assertEquals(listOf("Jedna", "Dvě slova", "Tři"), result.map { it.translatedText })
        assertTrue(result.none { it.isUntranslated })
    }

    @Test
    fun `steps are tried in order until nothing is left, later steps are skipped`() = runBlocking {
        val classified = listOf(bubble("Hello there"))
        val blocks = listOf(untranslated("Hello there"))
        var thirdCalled = false

        val result = fillUntranslatedBlocks(blocks, classified, listOf(
            { listOf(untranslated("Hello there")) },
            { listOf(translated("Hello there", "Ahoj")) },
            { thirdCalled = true; listOf(translated("Hello there", "Nazdar")) },
        ))

        assertEquals("Ahoj", result.single().translatedText)
        assertTrue(!thirdCalled)
    }

    @Test
    fun `a rate limited, failed or malformed step is skipped and the next one still runs`() = runBlocking {
        val classified = listOf(bubble("Hello there"))
        val blocks = listOf(untranslated("Hello there"))

        val result = fillUntranslatedBlocks(blocks, classified, listOf(
            { throw RateLimitedException() },
            { null },
            { emptyList() },
            { listOf(translated("Hello there", "Ahoj")) },
        ))

        assertEquals("Ahoj", result.single().translatedText)
    }

    @Test
    fun `when no step helps the page keeps its untranslated blocks instead of failing`() = runBlocking {
        val classified = listOf(bubble("Hello there"))
        val blocks = listOf(untranslated("Hello there"))

        val result = fillUntranslatedBlocks(blocks, classified, listOf({ null }))

        assertTrue(result.single().isUntranslated)
    }

    @Test
    fun `sfx and bubbles without letters are never sent`() = runBlocking {
        val classified = listOf(bubble("BOOM", isSfx = true), bubble("!!"))
        val blocks = listOf(untranslated("BOOM"), untranslated("!!"))
        var called = false

        fillUntranslatedBlocks(blocks, classified, listOf({ called = true; null }))

        assertTrue(!called)
    }

    @Test
    fun `lists of different length are returned untouched`() = runBlocking {
        var called = false
        val blocks = listOf(untranslated("Hello there"))

        val result = fillUntranslatedBlocks(blocks, emptyList(), listOf({ called = true; null }))

        assertEquals(blocks, result)
        assertTrue(!called)
    }

    // ── Quality gate (audit Vagabondu: "HLUPAKI" se z retry tiše přijalo) ──

    @Test
    fun `a retry result rejected by the quality gate stays untranslated`() = runBlocking {
        val classified = listOf(bubble("Hello there"))
        val blocks = listOf(untranslated("Hello there"))

        // Retry vyrobí zkomoleninu - quality gate ji odmitne a blok zustane
        // untranslated misto aby se gibberish vykreslil.
        val result = fillUntranslatedBlocks(
            blocks, classified,
            listOf({ listOf(translated("Hello there", "HLUPAKI")) }),
            qualityGate = { b, _ -> b.translatedText == "HLUPAKI" },
        )

        assertTrue(result.single().isUntranslated)
        assertEquals("Hello there", result.single().translatedText)
    }

    @Test
    fun `a rejected retry still falls through to the next provider`() = runBlocking {
        val classified = listOf(bubble("Hello there"))
        val blocks = listOf(untranslated("Hello there"))

        val result = fillUntranslatedBlocks(
            blocks, classified,
            listOf(
                { listOf(translated("Hello there", "HLUPAKI")) },
                { listOf(translated("Hello there", "Ahoj")) },
            ),
            qualityGate = { b, _ -> b.translatedText == "HLUPAKI" },
        )

        assertEquals("Ahoj", result.single().translatedText)
    }

    @Test
    fun `a retry result with a leaked placeholder is never accepted`() = runBlocking {
        val classified = listOf(bubble("Hello there"))
        val blocks = listOf(untranslated("Hello there"))

        // "__g8__" uvnitr vysledku retry = placeholder leak - blok zustane
        // untranslated i bez qualityGate (hard safety, ne volitelna kontrola).
        val result = fillUntranslatedBlocks(
            blocks, classified,
            listOf({ listOf(translated("Hello there", "AHOJ __g8__")) }),
        )

        assertTrue(result.single().isUntranslated)
        assertEquals("Hello there", result.single().translatedText)
    }
}

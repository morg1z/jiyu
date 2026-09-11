package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByokTranslateClientTest {

    // ── buildByokPrompt ──

    @Test
    fun `the prompt asks for exactly as many lines as texts, in order`() {
        val prompt = buildByokPrompt(listOf("Hello", "Goodbye"), "Czech", "English", emptyMap())
        assertEquals(true, prompt.contains("EXACTLY 2 lines"))
        assertEquals(true, prompt.contains("1. Hello"))
        assertEquals(true, prompt.contains("2. Goodbye"))
    }

    @Test
    fun `an auto source language is phrased without naming a specific language`() {
        val prompt = buildByokPrompt(listOf("Hello"), "Czech", "Auto", emptyMap())
        assertEquals(true, prompt.contains("its source language"))
    }

    @Test
    fun `a non-empty glossary is included as instructions`() {
        val prompt = buildByokPrompt(listOf("Frodo went home."), "Czech", "English", mapOf("Frodo" to "Frodo"))
        assertEquals(true, prompt.contains("\"Frodo\" -> \"Frodo\""))
    }

    @Test
    fun `an empty glossary adds no glossary section`() {
        val prompt = buildByokPrompt(listOf("Hello"), "Czech", "English", emptyMap())
        assertEquals(false, prompt.contains("glossary"))
    }

    // ── parseByokResponse ──

    @Test
    fun `a response with the exact expected line count parses cleanly`() {
        val result = parseByokResponse("Ahoj\nSbohem", expectedCount = 2)
        assertEquals(listOf("Ahoj", "Sbohem"), result)
    }

    @Test
    fun `numbering prefixes are stripped from each line`() {
        val result = parseByokResponse("1. Ahoj\n2) Sbohem\n3: Zdar", expectedCount = 3)
        assertEquals(listOf("Ahoj", "Sbohem", "Zdar"), result)
    }

    @Test
    fun `blank lines in the response are ignored, not counted`() {
        val result = parseByokResponse("Ahoj\n\nSbohem\n", expectedCount = 2)
        assertEquals(listOf("Ahoj", "Sbohem"), result)
    }

    @Test
    fun `a mismatched line count returns null rather than guessing`() {
        assertNull(parseByokResponse("Ahoj\nSbohem\nExtra", expectedCount = 2))
        assertNull(parseByokResponse("Jen jeden radek", expectedCount = 2))
    }
}

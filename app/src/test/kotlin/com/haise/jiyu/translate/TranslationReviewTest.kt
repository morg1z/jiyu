package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test self-review vrstvy ([TranslationReview]) - parser odpovědí
 * korektora (OK/FIX/BAD) musí být robustní: neparsovatelný výstup je fail-open,
 * chybějící id se přeskočí, neznámý verdict se bere jako OK.
 */
class TranslationReviewTest {

    @Test
    fun `a well formed response parses all verdicts`() {
        val raw = """{"reviews":[{"id":0,"verdict":"OK","fixed":""},{"id":1,"verdict":"FIX","fixed":"ZABIJ TĚ."},{"id":2,"verdict":"BAD","fixed":""}]}"""
        val out = TranslationReview.parse(raw)!!
        assertEquals(TranslationReview.Verdict.OK, out[0]!!.verdict)
        assertEquals(TranslationReview.Verdict.FIX, out[1]!!.verdict)
        assertEquals("ZABIJ TĚ.", out[1]!!.fixed)
        assertEquals(TranslationReview.Verdict.BAD, out[2]!!.verdict)
    }

    @Test
    fun `json wrapped in prose still parses`() {
        // Model obalí JSON větou - extractJsonObject vystřihne vnitřek.
        val raw = "Tady jsou výsledky:\n{\"reviews\":[{\"id\":0,\"verdict\":\"BAD\"}]}\nHotovo."
        val out = TranslationReview.parse(raw)!!
        assertEquals(TranslationReview.Verdict.BAD, out[0]!!.verdict)
    }

    @Test
    fun `garbage returns null so the caller fails open`() {
        assertNull(TranslationReview.parse("not json at all"))
        assertNull(TranslationReview.parse(""))
        assertNull(TranslationReview.parse("{\"something\":[]}"))
    }

    @Test
    fun `entries without id or with unknown verdict degrade gracefully`() {
        val raw = """{"reviews":[{"verdict":"FIX"},{"id":5,"verdict":"WEIRD"},{"id":2,"verdict":"ok"}]}"""
        val out = TranslationReview.parse(raw)!!
        // Záznam bez id se přeskočí; "WEIRD"/"ok" se berou jako OK.
        assertNull(out[0])
        assertEquals(TranslationReview.Verdict.OK, out[5]!!.verdict)
        assertEquals(TranslationReview.Verdict.OK, out[2]!!.verdict)
        assertEquals(2, out.size)
    }

    @Test
    fun `user prompt numbers every pair deterministically`() {
        val prompt = TranslationReview.buildUserPrompt(listOf("A." to "AA.", "B?" to "BB?"))
        assertTrue(prompt.contains("0. SOURCE: \"A.\""))
        assertTrue(prompt.contains("1. SOURCE: \"B?\""))
        assertTrue(prompt.contains("TRANSLATION: \"BB?\""))
    }

    @Test
    fun `system prompt asks for the review json shape`() {
        val prompt = TranslationReview.buildSystemPrompt("Czech")
        assertTrue(prompt.contains("\"reviews\""))
        assertTrue(prompt.contains("OK"))
        assertTrue(prompt.contains("FIX"))
        assertTrue(prompt.contains("BAD"))
    }
}

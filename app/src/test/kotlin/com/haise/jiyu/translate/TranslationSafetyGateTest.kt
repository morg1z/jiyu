package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test render safety gate ([applyRenderSafetyGate] / [hasLeakedToken] /
 * [findShiftedEchoIndices]) - poslední obrana před tím, aby se k čtenáři dostal
 * placeholder, překlad cizí bubliny nebo rozbitá výpustka (audit Vagabondu:
 * "__g8__" na TOC stránce, "BYLI JSME ZLODĚJI" vykreslené ve špatné bublině,
 * "PŘEŽIL.." se dvěma tečkami).
 */
class TranslationSafetyGateTest {

    private fun block(
        original: String,
        translated: String,
        isUntranslated: Boolean = false,
        isSfx: Boolean = false,
        isArtText: Boolean = false,
    ) = TranslatedBlock(
        originalText = original,
        translatedText = translated,
        displayText = if (isUntranslated) original else translated,
        leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f,
        isSfx = isSfx,
        isArtText = isArtText,
        isUntranslated = isUntranslated,
    )

    // ── hasLeakedToken ──

    @Test
    fun `all placeholder shapes are detected`() {
        assertTrue(hasLeakedToken("__g8__"))
        assertTrue(hasLeakedToken("KAPITOLA __g10__ PĚT"))
        assertTrue(hasLeakedToken("⟦JIYU_PROTECT_0⟧"))
        assertTrue(hasLeakedToken("__JIYU_PROTECT_0__"))
        assertTrue(hasLeakedToken("[UNTRANSLATED]"))
        assertTrue(hasLeakedToken("něco [untranslated] ještě"))
    }

    @Test
    fun `legitimate text is not a leaked token`() {
        assertFalse(hasLeakedToken("PODEPIS SEM ____"))   // vyplňovací čára, žádný alnum mezi __ __
        assertFalse(hasLeakedToken("JSEM ZVĚDAV."))
        assertFalse(hasLeakedToken("PODTRŽENÍ _je_ jemné"))
    }

    // ── applyRenderSafetyGate ──

    @Test
    fun `leaked placeholder falls back to the original lettering`() {
        val out = listOf(block("CHAPTER FIVE", "KAPITOLA __g8__ PĚT")).applyRenderSafetyGate()
        assertTrue(out.single().isUntranslated)
        assertEquals("CHAPTER FIVE", out.single().translatedText)
        assertEquals("CHAPTER FIVE", out.single().displayText)
    }

    @Test
    fun `a translation echoing another bubble's source is rejected`() {
        // Poziční posun odpovědí: blok [0] dostal doslovný ZDROJ bubliny [1]
        // (zlodějská sekvence z auditu - překlad se vykreslil do cizí bubliny).
        val blocks = listOf(
            block("WE WERE THIEVES.", "COLLECT SWORDS FROM EVERY CORPSE."),
            block("COLLECT SWORDS FROM EVERY CORPSE.", "SBÍREJ MEČE Z KAŽDÉ MRTVOLY."),
        )
        val out = blocks.applyRenderSafetyGate()
        assertTrue(out[0].isUntranslated)
        assertEquals("WE WERE THIEVES.", out[0].displayText)
        assertFalse(out[1].isUntranslated)
        assertEquals("SBÍREJ MEČE Z KAŽDÉ MRTVOLY.", out[1].translatedText)
    }

    @Test
    fun `a real translation never equals a foreign source key`() {
        // Normální překlady se na žádný zdroj nenormalizují - nic se nemá měnit.
        val blocks = listOf(
            block("WE WERE THIEVES.", "BYLI JSME ZLODĚJI."),
            block("COLLECT SWORDS FROM EVERY CORPSE.", "SBÍREJ MEČE Z KAŽDÉ MRTVOLY."),
        )
        val out = blocks.applyRenderSafetyGate()
        assertTrue(out.none { it.isUntranslated })
        assertEquals(blocks.map { it.translatedText }, out.map { it.translatedText })
    }

    @Test
    fun `double dots normalize to a single ellipsis`() {
        val out = listOf(block("HMM..", "HM..")).applyRenderSafetyGate()
        assertEquals("HM…", out.single().translatedText)
        assertEquals("HM…", out.single().displayText)
        assertFalse(out.single().isUntranslated)
    }

    @Test
    fun `four dots also collapse to one ellipsis`() {
        val out = listOf(block("WAIT....", "POČKEJ....")).applyRenderSafetyGate()
        assertEquals("POČKEJ…", out.single().translatedText)
    }

    @Test
    fun `sfx art and already untranslated blocks pass through untouched`() {
        val blocks = listOf(
            block("BOOM", "BOOM", isSfx = true),
            block("UNREADABLE", "UNREADABLE", isUntranslated = true),
            block("VAGAKOND", "VAGABOND", isArtText = true),
        )
        assertEquals(blocks, blocks.applyRenderSafetyGate())
    }
}

package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleOverlayDiagnosticsTest {

    @Test
    fun `box below the minimum dp threshold on width is flagged tiny`() {
        assertTrue(isSuspiciouslyTinyBubbleBox(widthDp = 6f, maxHeightDp = 40f))
    }

    @Test
    fun `box below the minimum dp threshold on height is flagged tiny`() {
        assertTrue(isSuspiciouslyTinyBubbleBox(widthDp = 40f, maxHeightDp = 6f))
    }

    @Test
    fun `normal sized box is not flagged tiny`() {
        assertFalse(isSuspiciouslyTinyBubbleBox(widthDp = 60f, maxHeightDp = 40f))
    }

    @Test
    fun `box exactly at the threshold is not flagged tiny`() {
        assertFalse(isSuspiciouslyTinyBubbleBox(widthDp = MIN_REASONABLE_BUBBLE_DP, maxHeightDp = MIN_REASONABLE_BUBBLE_DP))
    }

    @Test
    fun `skip reason prioritizes sfx over other reasons`() {
        assertEquals("sfx", bubbleSkipReason(isSfx = true, isUntranslated = true, hasTranslatableLetters = false))
    }

    @Test
    fun `skip reason reports untranslated when not sfx`() {
        assertEquals("untranslated", bubbleSkipReason(isSfx = false, isUntranslated = true, hasTranslatableLetters = true))
    }

    @Test
    fun `skip reason reports no_letters when neither sfx nor untranslated`() {
        assertEquals("no_letters", bubbleSkipReason(isSfx = false, isUntranslated = false, hasTranslatableLetters = false))
    }

    @Test
    fun `skip reason reports art_lettering for text painted onto artwork`() {
        assertEquals(
            "art_lettering",
            bubbleSkipReason(isSfx = false, isUntranslated = false, hasTranslatableLetters = true, artLettering = true),
        )
    }

    @Test
    fun `art lettering beats untranslated - the block could not be drawn either way`() {
        // Blok je nepřeložený A ZÁROVEŇ lettering v kresbě (žádný obrys, pestré pozadí) -
        // pro důvod "proč se nic nekreslí" je strukturální vysvětlení srozumitelnější,
        // příznak untranslated je v diagnostice zaznamenaný zvlášť.
        assertEquals(
            "art_lettering",
            bubbleSkipReason(isSfx = false, isUntranslated = true, hasTranslatableLetters = true, artLettering = true),
        )
    }

    @Test
    fun `art text label still wins over art lettering`() {
        // Echo originálu (logo/titul) je specifičtější příčina než samotné "na kresbě".
        assertEquals(
            "art_text",
            bubbleSkipReason(isSfx = false, isUntranslated = false, hasTranslatableLetters = true, isArtText = true, artLettering = true),
        )
    }

    @Test
    fun `skip reason is null when the block should render`() {
        assertEquals(null, bubbleSkipReason(isSfx = false, isUntranslated = false, hasTranslatableLetters = true))
    }

    // ── bubbleRenderMode ──

    @Test
    fun `render mode - plain shaped bubble without patch`() {
        assertEquals(
            "shaped_fill",
            bubbleRenderMode(hasShape = true, bgUniform = true, hasPatch = false, hasRecoveredShape = false),
        )
    }

    @Test
    fun `render mode - shaped bubble on varied bg gets shaped_patch`() {
        assertEquals(
            "shaped_patch",
            bubbleRenderMode(hasShape = true, bgUniform = false, hasPatch = true, hasRecoveredShape = false),
        )
    }

    @Test
    fun `render mode - shapeless lettering with patch is patched_art`() {
        assertEquals(
            "patched_art",
            bubbleRenderMode(hasShape = false, bgUniform = false, hasPatch = true, hasRecoveredShape = false),
        )
    }

    @Test
    fun `render mode - recovered bubble with patch`() {
        assertEquals(
            "recovered_patch",
            bubbleRenderMode(
                hasShape = false,
                bgUniform = false,
                hasPatch = true,
                hasRecoveredShape = true,
                recoveredPatch = true,
            ),
        )
    }

    @Test
    fun `render mode - recovered bubble without patch`() {
        assertEquals(
            "recovered_fill",
            bubbleRenderMode(hasShape = false, bgUniform = false, hasPatch = false, hasRecoveredShape = true),
        )
    }

    @Test
    fun `render mode - recovered shape wins over original shape`() {
        // Obnovený obrys mění i box - má přednost i když OCR obrys existoval.
        assertEquals(
            "recovered_fill",
            bubbleRenderMode(hasShape = true, bgUniform = true, hasPatch = false, hasRecoveredShape = true),
        )
    }

    @Test
    fun `render mode - shapeless uniform block falls back to uniform_fill`() {
        assertEquals(
            "uniform_fill",
            bubbleRenderMode(hasShape = false, bgUniform = true, hasPatch = false, hasRecoveredShape = false),
        )
    }
}

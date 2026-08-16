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
    fun `skip reason is null when the block should render`() {
        assertEquals(null, bubbleSkipReason(isSfx = false, isUntranslated = false, hasTranslatableLetters = true))
    }
}

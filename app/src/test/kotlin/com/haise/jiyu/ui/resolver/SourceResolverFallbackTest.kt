package com.haise.jiyu.ui.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverFallbackTest {

    @Test
    fun `page count below the floor is suspiciously short`() {
        assertTrue(isSuspiciouslyShort(5))
    }

    @Test
    fun `page count exactly at the floor is not suspiciously short`() {
        assertFalse(isSuspiciouslyShort(6))
    }

    @Test
    fun `page count above the floor is not suspiciously short`() {
        assertFalse(isSuspiciouslyShort(13))
    }

    @Test
    fun `zero pages is suspiciously short`() {
        assertTrue(isSuspiciouslyShort(0))
    }

    @Test
    fun `no alternatives means no better candidate`() {
        assertNull(pickBetterAlternative(originalPageCount = 5, alternatives = emptyList()))
    }

    @Test
    fun `alternative below the floor is rejected even if better than original`() {
        // Sam o sobe porad podezrele kratky - neni duvod si myslet, ze je "kompletni".
        assertNull(pickBetterAlternative(originalPageCount = 3, alternatives = listOf("alt" to 5)))
    }

    @Test
    fun `alternative above the floor but not better than original is rejected`() {
        assertNull(pickBetterAlternative(originalPageCount = 13, alternatives = listOf("alt" to 8)))
    }

    @Test
    fun `single alternative above the floor and better than original wins`() {
        assertEquals("alt", pickBetterAlternative(originalPageCount = 5, alternatives = listOf("alt" to 13)))
    }

    @Test
    fun `multiple alternatives above the floor - the one with the most pages wins`() {
        val result = pickBetterAlternative(
            originalPageCount = 5,
            alternatives = listOf("alt-a" to 8, "alt-b" to 19, "alt-c" to 11),
        )
        assertEquals("alt-b", result)
    }

    @Test
    fun `alternative with the same page count as original is rejected`() {
        assertNull(pickBetterAlternative(originalPageCount = 13, alternatives = listOf("alt" to 13)))
    }
}

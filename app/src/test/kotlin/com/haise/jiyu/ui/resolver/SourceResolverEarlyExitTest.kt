package com.haise.jiyu.ui.resolver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverEarlyExitTest {

    @Test
    fun `unknown total (0 or less) never blocks early-exit`() {
        assertTrue(isCompleteEnoughForEarlyExit(matchedChapterCount = 5, totalComicKChapters = 0))
    }

    @Test
    fun `exact match is always complete enough`() {
        assertTrue(isCompleteEnoughForEarlyExit(matchedChapterCount = 36, totalComicKChapters = 36))
    }

    @Test
    fun `candidate just above the 90 percent threshold passes`() {
        // 0.9 * 36 = 32.4 -> 33 splnuje
        assertTrue(isCompleteEnoughForEarlyExit(matchedChapterCount = 33, totalComicKChapters = 36))
    }

    @Test
    fun `candidate just below the 90 percent threshold is rejected`() {
        // 32 / 36 = 0.888... - pod hranici
        assertFalse(isCompleteEnoughForEarlyExit(matchedChapterCount = 32, totalComicKChapters = 36))
    }

    @Test
    fun `candidate with real gaps (reported bug scenario) is rejected`() {
        // Uzivatelem nahlaseny scenar: zdroj mel jmenem sedici skupinu a pozadovanou
        // kapitolu, ale celkove pokryti bylo daleko od kompletniho.
        assertFalse(isCompleteEnoughForEarlyExit(matchedChapterCount = 20, totalComicKChapters = 36))
    }
}

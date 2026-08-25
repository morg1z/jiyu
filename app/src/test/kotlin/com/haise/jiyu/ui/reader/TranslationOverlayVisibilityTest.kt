package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOverlayVisibilityTest {

    @Test
    fun `overlay shows when the page image loaded and blocks exist`() {
        assertEquals(true, shouldShowTranslationOverlay(hasBlocks = true, imageLoaded = true))
    }

    @Test
    fun `overlay stays hidden while the page image has not finished loading`() {
        // I kdyz uz jsou preklady hotove (napr. z cache), obrazek stranky se jeste
        // dokresluje/nacita - text by plaval nad prazdnym/rozbitym placeholderem.
        assertEquals(false, shouldShowTranslationOverlay(hasBlocks = true, imageLoaded = false))
    }

    @Test
    fun `overlay stays hidden when the page image failed to load`() {
        // AsyncImagePainter.State.Error - stranka se nikdy nenacetla (timeout/network),
        // preklad ale zustal v pameti z drivejsiho pokusu - bublina by plavala na bilem.
        assertEquals(false, shouldShowTranslationOverlay(hasBlocks = true, imageLoaded = false))
    }

    @Test
    fun `overlay stays hidden when there are no translated blocks, even if the image loaded`() {
        assertEquals(false, shouldShowTranslationOverlay(hasBlocks = false, imageLoaded = true))
    }
}

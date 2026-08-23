package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrPostProcessTest {

    @Test
    fun `removes whitespace between characters`() {
        assertEquals("こんにちは", MangaOcrPostProcess.postProcess("こん にちは"))
    }

    @Test
    fun `replaces ellipsis character with three fullwidth dots`() {
        assertEquals("．．．", MangaOcrPostProcess.postProcess("…"))
    }

    @Test
    fun `collapses four dot run to four fullwidth dots`() {
        assertEquals("．．．．", MangaOcrPostProcess.postProcess("...."))
    }

    @Test
    fun `single dot is not collapsed but still converted to fullwidth`() {
        assertEquals("か．", MangaOcrPostProcess.postProcess("か."))
    }

    @Test
    fun `mixed dot and nakaguro run collapses to fullwidth dots`() {
        assertEquals("．．．", MangaOcrPostProcess.postProcess("・.・"))
    }

    @Test
    fun `ascii letters and digits convert to fullwidth`() {
        assertEquals("ＡＢＣ１２３", MangaOcrPostProcess.postProcess("ABC123"))
    }

    @Test
    fun `japanese text without ascii is unchanged`() {
        assertEquals("こんにちは", MangaOcrPostProcess.postProcess("こんにちは"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", MangaOcrPostProcess.postProcess(""))
    }
}

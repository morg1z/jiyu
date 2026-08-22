package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelPaginatorTest {

    /** Deterministická fake - každý řádek má pevnou výšku a zalamuje po pevném počtu znaků,
     * bez závislosti na reálném font-shapingu Androidu. */
    private class FixedWidthFakeLayoutProvider(
        private val charsPerLine: Int,
        private val lineHeightPx: Float,
    ) : TextLayoutProvider {
        override fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo> {
            if (text.isEmpty()) return emptyList()
            return text.indices.step(charsPerLine).map { start ->
                val end = (start + charsPerLine).coerceAtMost(text.length)
                LineInfo(endIndex = end, heightPx = lineHeightPx)
            }
        }
    }

    @Test
    fun `empty text produces a single empty page`() {
        val pages = paginateNovelText(
            text = "",
            textLayoutProvider = FixedWidthFakeLayoutProvider(10, 20f),
            availableWidthPx = 500f, availableHeightPx = 1000f, fontSizeSp = 16f,
        )
        assertEquals(listOf(NovelPage(0, 0)), pages)
    }

    @Test
    fun `text shorter than one page stays on a single page`() {
        val text = "a".repeat(25)
        val pages = paginateNovelText(
            text = text,
            textLayoutProvider = FixedWidthFakeLayoutProvider(charsPerLine = 10, lineHeightPx = 20f),
            availableWidthPx = 500f, availableHeightPx = 1000f, fontSizeSp = 16f,
        )
        assertEquals(listOf(NovelPage(0, 25)), pages)
    }

    @Test
    fun `text is split across multiple pages when it exceeds the height budget`() {
        val text = "a".repeat(250)
        val pages = paginateNovelText(
            text = text,
            textLayoutProvider = FixedWidthFakeLayoutProvider(charsPerLine = 10, lineHeightPx = 20f),
            availableWidthPx = 500f, availableHeightPx = 200f, fontSizeSp = 16f,
        )
        assertEquals(3, pages.size)
        assertEquals(NovelPage(0, 100), pages[0])
        assertEquals(NovelPage(100, 200), pages[1])
        assertEquals(NovelPage(200, 250), pages[2])
        assertEquals(text.length, pages.last().endIndex)
    }

    @Test
    fun `a single line taller than the page still gets its own page instead of looping forever`() {
        val hugeLineProvider = TextLayoutProvider { text, _, _ ->
            listOf(LineInfo(endIndex = text.length, heightPx = 5000f))
        }
        val pages = paginateNovelText(
            text = "krátký text s obřím fontem",
            textLayoutProvider = hugeLineProvider,
            availableWidthPx = 500f, availableHeightPx = 1000f, fontSizeSp = 200f,
        )
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startIndex)
        assertEquals("krátký text s obřím fontem".length, pages[0].endIndex)
    }

    @Test
    fun `findPageIndexForOffset locates the page containing a character offset`() {
        val pages = listOf(NovelPage(0, 100), NovelPage(100, 200), NovelPage(200, 250))
        assertEquals(0, findPageIndexForOffset(pages, 0))
        assertEquals(0, findPageIndexForOffset(pages, 99))
        assertEquals(1, findPageIndexForOffset(pages, 100))
        assertEquals(2, findPageIndexForOffset(pages, 249))
        assertEquals(2, findPageIndexForOffset(pages, 250))
    }

    @Test
    fun `findPageIndexForOffset on empty pages list returns 0`() {
        assertEquals(0, findPageIndexForOffset(emptyList(), 0))
    }
}

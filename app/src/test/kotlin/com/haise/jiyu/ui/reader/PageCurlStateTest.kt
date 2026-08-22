package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlStateTest {

    @Test
    fun `dragging past the threshold and releasing advances to the next page`() {
        val state = PageCurlState(currentPageIndex = 2, pageCount = 5)
        val dragged = state.withDrag(0.6f)
        val result = dragged.onDragEnd(completionThreshold = 0.4f)
        assertTrue(result is PageTurnResult.WithinChapter)
        assertEquals(3, (result as PageTurnResult.WithinChapter).newState.currentPageIndex)
        assertEquals(0f, result.newState.dragProgress)
    }

    @Test
    fun `dragging below the threshold and releasing cancels back to flat`() {
        val state = PageCurlState(currentPageIndex = 2, pageCount = 5)
        val dragged = state.withDrag(0.2f)
        val result = dragged.onDragEnd(completionThreshold = 0.4f)
        assertTrue(result is PageTurnResult.Cancelled)
        assertEquals(0f, (result as PageTurnResult.Cancelled).newState.dragProgress)
        assertEquals(2, result.newState.currentPageIndex)
    }

    @Test
    fun `completing a turn on the last page of the chapter reports a chapter boundary, not a page change`() {
        val state = PageCurlState(currentPageIndex = 4, pageCount = 5)
        val result = state.withDrag(0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), result)
    }

    @Test
    fun `completing a turn on the first page toward prev reports a chapter boundary`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 5)
        val result = state.withDrag(-0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), result)
    }

    @Test
    fun `dragging past the chapter boundary produces no curl progress`() {
        val lastPage = PageCurlState(currentPageIndex = 4, pageCount = 5)
        assertEquals(0f, lastPage.withDrag(0.7f).dragProgress)

        val firstPage = PageCurlState(currentPageIndex = 0, pageCount = 5)
        assertEquals(0f, firstPage.withDrag(-0.7f).dragProgress)
    }

    @Test
    fun `a single-page chapter reports a chapter boundary immediately on edge tap without any drag`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 1)
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), state.onEdgeTap(TurnDirection.NEXT))
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), state.onEdgeTap(TurnDirection.PREV))
    }

    @Test
    fun `edge tap works the same as a completed drag without needing prior drag state`() {
        val state = PageCurlState(currentPageIndex = 1, pageCount = 5)
        val result = state.onEdgeTap(TurnDirection.NEXT)
        assertEquals(PageTurnResult.WithinChapter(PageCurlState(2, 5, 0f)), result)
    }

    // Critical 1 fix: a vertical/near-zero drag on a boundary page must NOT report a
    // ChapterBoundary just because the page happens to be first/last - `dragProgress` is
    // clamped to 0f at the boundary regardless of drag direction, so a genuinely
    // insignificant drag has to be told apart from "a real drag attempt that hit the
    // boundary" using `rawDragProgress`, not `dragProgress`.
    @Test
    fun `a vertical drag with no horizontal component on the last page does nothing`() {
        val state = PageCurlState(currentPageIndex = 4, pageCount = 5)
        val result = state.withDrag(0f).onDragEnd()
        assertTrue(result is PageTurnResult.Cancelled)
        assertEquals(4, (result as PageTurnResult.Cancelled).newState.currentPageIndex)
        assertEquals(0f, result.newState.dragProgress)
    }

    @Test
    fun `a vertical drag with no horizontal component on the first page does nothing`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 5)
        val result = state.withDrag(0f).onDragEnd()
        assertTrue(result is PageTurnResult.Cancelled)
        assertEquals(0, (result as PageTurnResult.Cancelled).newState.currentPageIndex)
    }

    // Critical 1 fix: a single-page-group chapter clamps `dragProgress` to 0f in BOTH
    // directions (it's simultaneously the first and last page), so the direction must be
    // read from `rawDragProgress`, not inferred from the clamped (always-0) `dragProgress`.
    @Test
    fun `a single-page chapter dragged toward NEXT reports a chapter boundary toward NEXT, not PREV`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 1)
        val result = state.withDrag(0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.NEXT), result)
    }

    @Test
    fun `a single-page chapter dragged toward PREV reports a chapter boundary toward PREV`() {
        val state = PageCurlState(currentPageIndex = 0, pageCount = 1)
        val result = state.withDrag(-0.9f).onDragEnd()
        assertEquals(PageTurnResult.ChapterBoundary(TurnDirection.PREV), result)
    }
}

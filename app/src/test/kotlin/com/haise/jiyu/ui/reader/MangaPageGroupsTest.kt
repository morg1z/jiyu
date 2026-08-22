package com.haise.jiyu.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaPageGroupsTest {

    @Test
    fun `without spread every page is its own group`() {
        val groups = computePageGroups(pageCount = 4, useSpread = false, spreadPageIndices = emptySet())
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), groups)
    }

    @Test
    fun `with spread pages pair up two at a time`() {
        val groups = computePageGroups(pageCount = 4, useSpread = true, spreadPageIndices = emptySet())
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), groups)
    }

    @Test
    fun `an odd page count leaves the last group as a single page`() {
        val groups = computePageGroups(pageCount = 5, useSpread = true, spreadPageIndices = emptySet())
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), groups)
    }

    @Test
    fun `a page forced solo by spreadPageIndices breaks the pairing around it`() {
        // Stránka 1 je sirsi-nez-vyssi (napr. rozlozeny obrazek) - nesmi se parovat.
        val groups = computePageGroups(pageCount = 5, useSpread = true, spreadPageIndices = setOf(1))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), groups)
    }

    @Test
    fun `empty page list produces no groups`() {
        assertEquals(emptyList<List<Int>>(), computePageGroups(pageCount = 0, useSpread = true, spreadPageIndices = emptySet()))
        assertEquals(emptyList<List<Int>>(), computePageGroups(pageCount = 0, useSpread = false, spreadPageIndices = emptySet()))
    }
}

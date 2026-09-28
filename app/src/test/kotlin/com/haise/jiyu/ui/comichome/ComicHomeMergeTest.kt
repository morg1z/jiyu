package com.haise.jiyu.ui.comichome

import com.haise.jiyu.source.SManga
import org.junit.Assert.assertEquals
import org.junit.Test

private fun sm(sourceId: String, title: String, cover: String? = null): SManga =
    SManga(sourceId = sourceId, url = "$sourceId/$title", title = title, coverUrl = cover)

class ComicHomeMergeTest {

    @Test
    fun `same normalized title on two sources merges into one entry`() {
        val listings = listOf(
            listOf(sm("a", "Absolute Batman") to 0),
            listOf(sm("b", "Absolute Batman!") to 0), // interpunkce se normalizuje
        )
        val merged = mergeComicEntries(listings)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].sourceCount)
        assertEquals(listOf("a", "b"), merged[0].sourceNames)
    }

    @Test
    fun `representative is the copy with the best rank`() {
        val listings = listOf(
            listOf(sm("a", "Absolute Batman") to 5),
            listOf(sm("b", "Absolute Batman") to 1),
        )
        val merged = mergeComicEntries(listings)
        assertEquals("b", merged[0].representative.sourceId)
    }

    @Test
    fun `at rank tie the copy with a cover wins`() {
        val listings = listOf(
            listOf(sm("a", "Absolute Batman", cover = null) to 0),
            listOf(sm("b", "Absolute Batman", cover = "https://x/c.jpg") to 0),
        )
        val merged = mergeComicEntries(listings)
        assertEquals("b", merged[0].representative.sourceId)
    }

    @Test
    fun `copy with a cover beats a better-ranked copy without one`() {
        val listings = listOf(
            listOf(sm("a", "Absolute Batman", cover = null) to 0),
            listOf(sm("b", "Absolute Batman", cover = "https://x/c.jpg") to 7),
        )
        val merged = mergeComicEntries(listings)
        assertEquals("b", merged[0].representative.sourceId)
    }

    @Test
    fun `entries sort by best rank then source count`() {
        val listings = listOf(
            listOf(
                sm("a", "Only Here") to 0,
                sm("a", "Absolute Batman") to 4,
            ),
            listOf(sm("b", "Absolute Batman") to 2),
        )
        val merged = mergeComicEntries(listings)
        assertEquals("Only Here", merged[0].representative.title)
        assertEquals("Absolute Batman", merged[1].representative.title)
    }

    @Test
    fun `different titles stay separate`() {
        val listings = listOf(
            listOf(sm("a", "Absolute Batman") to 0, sm("a", "Saga") to 1),
        )
        assertEquals(2, mergeComicEntries(listings).size)
    }
}

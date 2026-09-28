package com.haise.jiyu.ui.novelhome

import com.haise.jiyu.source.SManga
import org.junit.Assert.assertEquals
import org.junit.Test

private fun sm(sourceId: String, title: String, cover: String? = null): SManga =
    SManga(sourceId = sourceId, url = "$sourceId/$title", title = title, coverUrl = cover)

class NovelHomeMergeTest {

    @Test
    fun `same normalized title on two sources merges into one entry`() {
        val listings = listOf(
            listOf(sm("a", "Solo Leveling") to 0),
            listOf(sm("b", "Solo Leveling!") to 0), // interpunkce se normalizuje
        )
        val merged = mergeNovelEntries(listings)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].sourceCount)
        assertEquals(listOf("a", "b"), merged[0].sourceNames)
    }

    @Test
    fun `representative is the copy with the best rank`() {
        val listings = listOf(
            listOf(sm("a", "Solo Leveling") to 5),
            listOf(sm("b", "Solo Leveling") to 1),
        )
        val merged = mergeNovelEntries(listings)
        assertEquals("b", merged[0].representative.sourceId)
    }

    @Test
    fun `at rank tie the copy with a cover wins`() {
        val listings = listOf(
            listOf(sm("a", "Solo Leveling", cover = null) to 0),
            listOf(sm("b", "Solo Leveling", cover = "https://x/c.jpg") to 0),
        )
        val merged = mergeNovelEntries(listings)
        assertEquals("b", merged[0].representative.sourceId)
    }

    @Test
    fun `copy with a cover beats a better-ranked copy without one`() {
        // Karta s prazdnym coverUrl nikdy nenacte obrazek - reprezentantem ma byt
        // kopie, ktera cover skutecne poskytuje, i kdyz je v listingu nize.
        val listings = listOf(
            listOf(sm("a", "Solo Leveling", cover = null) to 0),
            listOf(sm("b", "Solo Leveling", cover = "https://x/c.jpg") to 7),
        )
        val merged = mergeNovelEntries(listings)
        assertEquals("b", merged[0].representative.sourceId)
    }

    @Test
    fun `entries sort by best rank then source count`() {
        val listings = listOf(
            listOf(
                sm("a", "Only Here") to 0,           // rank 0, 1 zdroj
                sm("a", "Solo Leveling") to 4,       // rank 4, ale na 2 zdrojích
            ),
            listOf(sm("b", "Solo Leveling") to 2),   // nejlepší rank titulu = 2
        )
        val merged = mergeNovelEntries(listings)
        // "Only Here" (rank 0) pred "Solo Leveling" (rank 2) i kdyz ma min zdroju.
        assertEquals("Only Here", merged[0].representative.title)
        assertEquals("Solo Leveling", merged[1].representative.title)
    }

    @Test
    fun `different titles stay separate`() {
        val listings = listOf(
            listOf(sm("a", "Solo Leveling") to 0, sm("a", "Omniscient Reader") to 1),
        )
        assertEquals(2, mergeNovelEntries(listings).size)
    }
}

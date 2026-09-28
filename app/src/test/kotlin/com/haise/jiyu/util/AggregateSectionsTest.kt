package com.haise.jiyu.util

import com.haise.jiyu.source.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odvozené sekce agregovaných Domů obrazovek (Novela/Komiks) - staví se jen
 * z listing metadat (status, žánry), žádné vymyšlené metriky.
 */
class AggregateSectionsTest {

    private fun m(
        title: String,
        status: String? = null,
        genres: List<String> = emptyList(),
        lastChapter: Float? = null,
    ) = SManga(
        sourceId = "s", url = "/$title", title = title, coverUrl = null,
        status = status, genres = genres, lastChapter = lastChapter,
    )

    // ── isCompletedStatus ────────────────────────────────────────────────────

    @Test
    fun `isCompletedStatus matches the forms sources actually emit`() {
        listOf("Completed", "Complete", "completed", "Dokončeno", "finished", "Ended", "end")
            .forEach { assertTrue("'$it' should be completed", isCompletedStatus(it)) }
    }

    @Test
    fun `isCompletedStatus rejects ongoing and paused states`() {
        listOf("Ongoing", "Hiatus", "Publishing", "Probíhá", "on hiatus", "Cancelled")
            .forEach { assertFalse("'$it' should not be completed", isCompletedStatus(it)) }
    }

    // ── deriveAggregateSections ──────────────────────────────────────────────

    @Test
    fun `completed section appears once enough entries report a completed status`() {
        val entries = (1..5).map { m("Done $it", status = "Completed") } +
            (1..6).map { m("Going $it", status = "Ongoing") }
        val sections = deriveAggregateSections(entries) { it }
        val completed = sections.filter { it.kind == AggregateSection.Kind.COMPLETED }
        assertEquals(1, completed.size)
        assertEquals(5, completed[0].items.size)
        assertTrue(completed[0].items.all { it.status == "Completed" })
    }

    @Test
    fun `genre sections rank by frequency and skip generic container genres`() {
        val entries = mutableListOf<SManga>()
        repeat(6) { entries += m("a$it", genres = listOf("Action", "Comic")) }
        repeat(5) { entries += m("f$it", genres = listOf("Fantasy", "Fiction")) }
        repeat(4) { entries += m("h$it", genres = listOf("Horror")) }
        val sections = deriveAggregateSections(entries) { it }
        val genreSections = sections.filter { it.kind == AggregateSection.Kind.GENRE }
        // "Comic"/"Fiction" jsou obalové žánry - nesmí se stát sekcí
        assertTrue(genreSections.none { it.genre.equals("Comic", true) || it.genre.equals("Fiction", true) })
        // Seřazené podle podpory: Action (6) > Fantasy (5) > Horror (4)
        assertEquals(listOf("Action", "Fantasy", "Horror"), genreSections.map { it.genre })
    }

    @Test
    fun `sparse catalog produces no sections instead of half-empty rows`() {
        val entries = listOf(
            m("One", status = "Completed", genres = listOf("Action")),
            m("Two", status = "Ongoing", genres = listOf("Drama")),
        )
        assertTrue(deriveAggregateSections(entries) { it }.isEmpty())
    }

    @Test
    fun `sections cap items at maxItemsPerSection`() {
        val entries = (1..30).map { m("Done $it", status = "Completed") }
        val sections = deriveAggregateSections(entries, maxItemsPerSection = 12) { it }
        assertEquals(12, sections.first { it.kind == AggregateSection.Kind.COMPLETED }.items.size)
    }

    @Test
    fun `entries without status still produce genre sections`() {
        val entries = (1..6).map { m("n$it", genres = listOf("Sci-Fi")) }
        val sections = deriveAggregateSections(entries) { it }
        assertEquals(1, sections.size)
        assertEquals(AggregateSection.Kind.GENRE, sections[0].kind)
        assertEquals("Sci-Fi", sections[0].genre)
    }

    @Test
    fun `longest section sorts by reported chapter count and skips missing data`() {
        val entries = (1..6).map { m("long$it", lastChapter = (100 - it).toFloat()) } +
            (1..4).map { m("short$it") } // bez lastChapter - nezapocitaji se
        val sections = deriveAggregateSections(entries) { it }
        val longest = sections.single { it.kind == AggregateSection.Kind.LONGEST }
        assertEquals(6, longest.items.size)
        // Serazeno sestupne podle poctu kapitol
        assertEquals(listOf(99f, 98f, 97f, 96f, 95f, 94f), longest.items.map { it.lastChapter })
    }

    @Test
    fun `longest section stays hidden when too few entries report counts`() {
        val entries = listOf(m("a", lastChapter = 50f), m("b", lastChapter = 30f))
        assertTrue(deriveAggregateSections(entries) { it }.none { it.kind == AggregateSection.Kind.LONGEST })
    }

    @Test
    fun `genre matching is case-insensitive and keeps the display label`() {
        val entries = listOf(
            m("a", genres = listOf("Action")),
            m("b", genres = listOf("ACTION")),
            m("c", genres = listOf("action")),
            m("d", genres = listOf("Action")),
        )
        val sections = deriveAggregateSections(entries) { it }
        val genre = sections.single { it.kind == AggregateSection.Kind.GENRE }
        assertEquals("Action", genre.genre)
        assertEquals(4, genre.items.size)
    }

    // ── Deduplikace napříč sekcí (hlášený bug: stejné tituly v každé řadě) ─────

    @Test
    fun `a title claimed by one genre section never repeats in another`() {
        // Populární tituly sdílejí víc žánrů - bez deduplikace "Action" i
        // "Adventure" řada začínala stejnými čtyřmi obálkami.
        val shared = (1..4).map { m("shared$it", genres = listOf("Action", "Adventure")) }
        val actionOnly = (1..5).map { m("act$it", genres = listOf("Action")) }
        val adventOnly = (1..4).map { m("adv$it", genres = listOf("Adventure")) }
        val sections = deriveAggregateSections(shared + actionOnly + adventOnly) { it }
        val genreRows = sections.filter { it.kind == AggregateSection.Kind.GENRE }

        val action = genreRows.first { it.genre == "Action" }
        val adventure = genreRows.first { it.genre == "Adventure" }
        assertTrue(action.items.none { it.title.startsWith("adv") })
        assertTrue(adventure.items.none { it.title.startsWith("shared") || it.title.startsWith("act") })
    }

    @Test
    fun `genre section is skipped when claimed titles leave too few fresh items`() {
        // "Horror" má podporu 4, ale všechny jeho tituly si přivlastnila
        // silnější "Action" řada - řada ze samých duplicit se nevykreslí.
        val entries = (1..4).map { m("s$it", genres = listOf("Action", "Horror")) } +
            (1..5).map { m("a$it", genres = listOf("Action")) }
        val sections = deriveAggregateSections(entries) { it }
        val genreRows = sections.filter { it.kind == AggregateSection.Kind.GENRE }
        assertEquals(listOf("Action"), genreRows.map { it.genre })
    }

    @Test
    fun `dedup tries deeper genre candidates after a depleted row`() {
        // Horror se vyprazdíní pod Action - místo něj se má zkusit další žánr
        // (Fantasy má taky podporu 4, takže se do prázdného slotu vejde).
        val entries = (1..4).map { m("s$it", genres = listOf("Action", "Horror")) } +
            (1..5).map { m("a$it", genres = listOf("Action")) } +
            (1..4).map { m("f$it", genres = listOf("Fantasy")) }
        val sections = deriveAggregateSections(entries) { it }
        assertEquals(
            listOf("Action", "Fantasy"),
            sections.filter { it.kind == AggregateSection.Kind.GENRE }.map { it.genre },
        )
    }

    @Test
    fun `excludeKeys hide titles already visible in the hero row`() {
        val entries = (1..8).map { m("t$it", genres = listOf("Action")) }
        val sections = deriveAggregateSections(
            entries,
            excludeKeys = setOf("t1", "t2", "t3", "t4"),
            key = { it.title },
        ) { it }
        val row = sections.single { it.kind == AggregateSection.Kind.GENRE }
        assertEquals(listOf("t5", "t6", "t7", "t8"), row.items.map { it.title })
    }

    @Test
    fun `completed section claims its titles before genre rows`() {
        val entries = (1..4).map { m("done$it", status = "Completed", genres = listOf("Action")) } +
            (1..5).map { m("run$it", status = "Ongoing", genres = listOf("Action")) }
        val sections = deriveAggregateSections(entries) { it }
        val action = sections.single { it.kind == AggregateSection.Kind.GENRE }
        assertTrue(action.items.none { it.title.startsWith("done") })
    }
}

package com.haise.jiyu.ui.resolver

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.comick.ResolvedCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Čistý JVM test [rankCandidates] - viz jeho doc komentář. Nahlášený bug: appka vybrala
 * oblíbený zdroj se 4 kapitolami ze 163, protože "oblíbený" byl nejvyšší priorita bez
 * ohledu na úplnost.
 */
class SourceResolverViewModelTest {

    private class FakeSource(override val id: String, override val name: String) : MangaSource {
        override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = emptyList()
        override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = emptyList()
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    private fun candidate(
        sourceId: String,
        matchedChapterCount: Int,
        hasRequestedChapter: Boolean = true,
        isFavorite: Boolean = false,
        minChapter: Float? = null,
        maxChapter: Float? = null,
    ): ResolvedCandidate {
        val source = FakeSource(sourceId, sourceId)
        val manga = SManga(sourceId = sourceId, url = "https://example/$sourceId", title = "Test", coverUrl = null)
        return ResolvedCandidate(
            source = source,
            manga = manga,
            matchedChapterCount = matchedChapterCount,
            hasRequestedChapter = hasRequestedChapter,
            isFavorite = isFavorite,
            minChapterNumber = minChapter,
            maxChapterNumber = maxChapter,
        )
    }

    private fun noPreferredGroup(candidate: ResolvedCandidate) = false

    private fun chapter(number: Float, group: String?) = com.haise.jiyu.data.db.entity.ChapterEntity(
        id = "c$number-$group", mangaId = "m", sourceId = "comick", url = "/c/$number", name = "Ch $number",
        chapterNumber = number, dateUpload = 0L, scanlationGroup = group,
    )

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ── rozsah kapitol ────────────────────────────────────────────────────────

    @Test
    fun `a source that only has the tail of the series does not cover the range`() {
        val range = ChapterRange(first = 1f, last = 150f)
        assertEquals(false, coversChapterRange(candidateFirst = 110f, candidateLast = 150f, range = range))
        assertEquals(false, coversChapterRange(candidateFirst = 1f, candidateLast = 3f, range = range))
        assertEquals(true, coversChapterRange(candidateFirst = 1f, candidateLast = 150f, range = range))
    }

    @Test
    fun `small differences at the ends are tolerated - the source can be a few chapters behind or start from 1 instead of 0`() {
        val range = ChapterRange(first = 0f, last = 150f)
        assertEquals(true, coversChapterRange(candidateFirst = 1f, candidateLast = 147f, range = range))
        assertEquals(false, coversChapterRange(candidateFirst = 1f, candidateLast = 140f, range = range))
    }

    @Test
    fun `an unknown range never disqualifies a source`() {
        assertEquals(true, coversChapterRange(null, null, ChapterRange(1f, 100f)))
        assertEquals(true, coversChapterRange(1f, 100f, null))
    }

    @Test
    fun `the range decides even for a favorite - a favorite with only the last 40 chapters loses to a source with all of them`() {
        val range = ChapterRange(1f, 150f)
        val partialFavorite = candidate("partial-favorite", matchedChapterCount = 40, isFavorite = true, minChapter = 111f, maxChapter = 150f)
        val complete = candidate("complete", matchedChapterCount = 148, minChapter = 1f, maxChapter = 150f)

        val ranked = rankCandidates(listOf(partialFavorite, complete), totalComicKChapters = 150, isPreferredGroup = ::noPreferredGroup, comicKRange = range)

        assertEquals("complete", ranked.first().source.id)
    }

    @Test
    fun `a source with a large count but only the start of the series does not win the group bonus`() {
        val range = ChapterRange(1f, 150f)
        val startOnly = candidate("start-only", matchedChapterCount = 140, minChapter = 1f, maxChapter = 140f)
        val full = candidate("full", matchedChapterCount = 149, minChapter = 1f, maxChapter = 150f)

        val ranked = rankCandidates(
            candidates = listOf(startOnly, full),
            totalComicKChapters = 150,
            isPreferredGroup = { it.source.id == "start-only" },
            comicKRange = range,
        )

        assertEquals("full", ranked.first().source.id)
    }

    // ── skupiny prvních a posledních kapitol ─────────────────────────────────

    @Test
    fun `only the groups of the last two chapters are preferred, a group that only started the series is not`() {
        val chapters = listOf(
            chapter(1f, "Asura"), chapter(1f, "TeamShadowi"),
            chapter(2f, "Asura"), chapter(2f, "TeamShadowi"),
            chapter(50f, "Someone Else"),
            chapter(122f, "Asura"),
            chapter(123f, "Asura"),
        )

        val signals = deriveGroupSignals(chapters, ::normalize, ::isGenericGroupToken)

        assertEquals(listOf("asura"), signals.activeTokens)
        assertEquals(listOf("asura", "teamshadowi"), signals.originTokens)
        assertEquals(listOf("asura"), signals.preferredTokens)
    }

    @Test
    fun `without groups on the last chapters the original groups are used as a fallback`() {
        val chapters = listOf(chapter(1f, "Asura"), chapter(2f, "Asura"), chapter(99f, null), chapter(100f, null))

        val signals = deriveGroupSignals(chapters, ::normalize, ::isGenericGroupToken)

        assertEquals(emptyList<String>(), signals.activeTokens)
        assertEquals(listOf("asura"), signals.preferredTokens)
    }

    @Test
    fun `generic words and very short names are never a group token`() {
        val chapters = listOf(chapter(1f, "Scans"), chapter(2f, "AB"), chapter(3f, "Comics, Reaper Scans"))

        val signals = deriveGroupSignals(chapters, ::normalize, ::isGenericGroupToken)

        assertEquals(listOf("reaperscans"), signals.activeTokens)
    }

    @Test
    fun `a complete non-favorite source outranks a badly incomplete favorite source`() {
        val incompleteFavorite = candidate("weak-favorite", matchedChapterCount = 4, isFavorite = true)
        val completeOther = candidate("complete-source", matchedChapterCount = 163, isFavorite = false)

        val ranked = rankCandidates(
            candidates = listOf(incompleteFavorite, completeOther),
            totalComicKChapters = 163,
            isPreferredGroup = ::noPreferredGroup,
        )

        assertEquals("complete-source", ranked.first().source.id)
    }

    @Test
    fun `a complete favorite source still wins over a complete non-favorite source`() {
        val completeFavorite = candidate("favorite", matchedChapterCount = 160, isFavorite = true)
        val completeOther = candidate("other", matchedChapterCount = 163, isFavorite = false)

        val ranked = rankCandidates(
            candidates = listOf(completeOther, completeFavorite),
            totalComicKChapters = 163,
            isPreferredGroup = ::noPreferredGroup,
        )

        assertEquals("favorite - oblibenost porad rozhoduje, kdyz je zdroj dost kompletni", "favorite", ranked.first().source.id)
    }

    @Test
    fun `when nothing is complete enough the most complete candidate still wins`() {
        // Zadny kandidat neni "dost kompletni" (prah 90 % ze 163) - oba oblibeny bonusy
        // vyjdou false, razeni tak spadne na matchedChapterCount a porad vybere lepsi z dvou spatnych.
        val favoriteButThin = candidate("favorite-thin", matchedChapterCount = 4, isFavorite = true)
        val slightlyMore = candidate("more-but-still-thin", matchedChapterCount = 10, isFavorite = false)

        val ranked = rankCandidates(
            candidates = listOf(favoriteButThin, slightlyMore),
            totalComicKChapters = 163,
            isPreferredGroup = ::noPreferredGroup,
        )

        assertEquals("more-but-still-thin", ranked.first().source.id)
    }

    @Test
    fun `preferred group follows the same completeness gate as favorite`() {
        val incompleteGroupMatch = candidate("group-match-thin", matchedChapterCount = 4)
        val completeOther = candidate("complete", matchedChapterCount = 163)

        val ranked = rankCandidates(
            candidates = listOf(incompleteGroupMatch, completeOther),
            totalComicKChapters = 163,
            isPreferredGroup = { it.source.id == "group-match-thin" },
        )

        assertEquals("complete", ranked.first().source.id)
    }
}

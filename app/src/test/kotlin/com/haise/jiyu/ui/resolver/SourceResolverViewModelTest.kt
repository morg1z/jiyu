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
    ): ResolvedCandidate {
        val source = FakeSource(sourceId, sourceId)
        val manga = SManga(sourceId = sourceId, url = "https://example/$sourceId", title = "Test", coverUrl = null)
        return ResolvedCandidate(
            source = source,
            manga = manga,
            matchedChapterCount = matchedChapterCount,
            hasRequestedChapter = hasRequestedChapter,
            isFavorite = isFavorite,
        )
    }

    private fun noPreferredGroup(candidate: ResolvedCandidate) = false

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

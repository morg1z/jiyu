package com.haise.jiyu.ui.novelresolver

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.comick.ResolvedCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

private class StubSource(
    override val id: String,
    override val language: String = "en",
) : MangaSource {
    override val name: String get() = id
    override val contentType: String get() = "NOVEL"
    override suspend fun search(query: String, page: Int, filter: MangaFilter) = emptyList<SManga>()
    override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<com.haise.jiyu.source.Page>()
}

private fun candidate(
    sourceId: String,
    language: String = "en",
    chapters: Int,
    favorite: Boolean = false,
): ResolvedCandidate {
    val manga = SManga(sourceId = sourceId, url = "u", title = "T", coverUrl = null)
    return ResolvedCandidate(
        source = StubSource(sourceId, language),
        manga = manga,
        matchedChapterCount = chapters,
        hasRequestedChapter = true,
        isFavorite = favorite,
        nearestChapterDistance = null,
        minChapterNumber = 1f,
        maxChapterNumber = chapters.toFloat(),
    )
}

class NovelResolverRankingTest {

    @Test
    fun `favorite source with chapters wins over bigger english source`() {
        val fav = candidate("fav", chapters = 5, favorite = true)
        val big = candidate("big", chapters = 500)
        assertEquals("fav", rankNovelCandidates(listOf(big, fav)).first().source.id)
    }

    @Test
    fun `english source beats non-english with more chapters`() {
        val en = candidate("en", language = "en", chapters = 10)
        val ru = candidate("ru", language = "ru", chapters = 900)
        assertEquals("en", rankNovelCandidates(listOf(ru, en)).first().source.id)
    }

    @Test
    fun `same language falls back to chapter count`() {
        val small = candidate("small", chapters = 10)
        val big = candidate("big", chapters = 300)
        assertEquals("big", rankNovelCandidates(listOf(small, big)).first().source.id)
    }

    @Test
    fun `favorite without chapters does not win`() {
        val fav = candidate("fav", chapters = 0, favorite = true)
        val en = candidate("en", chapters = 20)
        assertEquals("en", rankNovelCandidates(listOf(fav, en)).first().source.id)
    }

    @Test
    fun `english without chapters does not beat bigger non-english`() {
        val en = candidate("en", chapters = 0)
        val ru = candidate("ru", language = "ru", chapters = 300)
        assertEquals("ru", rankNovelCandidates(listOf(en, ru)).first().source.id)
    }
}

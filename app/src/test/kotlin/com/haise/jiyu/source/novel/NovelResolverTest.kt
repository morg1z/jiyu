package com.haise.jiyu.source.novel

import com.haise.jiyu.settings.FakeDataStore
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.comick.ResolvedCandidate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private suspend fun NovelResolver.findCandidates(
    title: String,
    requestedChapterNumber: Float? = null,
): List<ResolvedCandidate> = findCandidatesFlow(title, requestedChapterNumber).toList()

private class FakeNovelSource(
    override val id: String,
    override val name: String,
    override val contentType: String = "NOVEL",
    private val searchResults: List<SManga> = emptyList(),
    private val chapters: List<SChapter> = emptyList(),
    private val failSearch: Boolean = false,
    override val isAdult: Boolean = false,
    override val language: String = "en",
) : MangaSource {
    var searchCalls = 0
        private set

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> {
        searchCalls++
        if (failSearch) throw RuntimeException("boom")
        return searchResults
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = chapters
    override suspend fun getPageList(chapter: SChapter) = emptyList<com.haise.jiyu.source.Page>()
}

private fun novelChapter(number: Float): SChapter =
    SChapter(sourceId = "s", mangaUrl = "m", url = "c$number", name = "Ch. $number", chapterNumber = number, dateUpload = 0L)

class NovelResolverTest {

    private lateinit var sourceManager: SourceManager
    private lateinit var settings: SettingsRepository
    private lateinit var resolver: NovelResolver

    @Before
    fun setUp() {
        sourceManager = mockk()
        settings = SettingsRepository(FakeDataStore())
        resolver = NovelResolver(sourceManager, settings)
    }

    @Test
    fun `searches only NOVEL sources`() = runTest {
        val match = SManga(sourceId = "novel-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val novelSource = FakeNovelSource("novel-a", "Novel A", searchResults = listOf(match), chapters = listOf(novelChapter(1f)))
        val mangaSource = FakeNovelSource(
            "manga-a", "Manga A", contentType = "MANGA",
            searchResults = listOf(match.copy(sourceId = "manga-a")),
        )
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(novelSource, mangaSource)

        val result = resolver.findCandidates("Solo Leveling")

        assertEquals(1, result.size)
        assertEquals("novel-a", result[0].source.id)
        assertEquals(0, mangaSource.searchCalls)
    }

    @Test
    fun `non-English novel sources are excluded`() = runTest {
        // Agregovany Novela rezim je jen pro EN zdroje - ru/ar/tr/id/es kopie uzivatel
        // stejne necete a sweep pres ne jen zdrzuje rozliseni titulu.
        val match = SManga(sourceId = "novel-ru", url = "u1", title = "Solo Leveling", coverUrl = null)
        val ruSource = FakeNovelSource("novel-ru", "Ranobes", language = "ru",
            searchResults = listOf(match), chapters = listOf(novelChapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(ruSource)

        assertTrue(resolver.findCandidates("Solo Leveling").isEmpty())
        assertEquals(0, ruSource.searchCalls)
    }

    @Test
    fun `adult novel sources are excluded`() = runTest {
        val adultSource = FakeNovelSource("novel-adult", "Adult", isAdult = true,
            searchResults = listOf(SManga(sourceId = "novel-adult", url = "u1", title = "Solo Leveling", coverUrl = null)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adultSource)

        assertTrue(resolver.findCandidates("Solo Leveling").isEmpty())
        assertEquals(0, adultSource.searchCalls)
    }

    @Test
    fun `only keeps candidates whose normalized title matches`() = runTest {
        val wrong = SManga(sourceId = "novel-a", url = "u1", title = "Different Novel", coverUrl = null)
        val source = FakeNovelSource("novel-a", "A", searchResults = listOf(wrong))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        assertTrue(resolver.findCandidates("Solo Leveling").isEmpty())
    }

    @Test
    fun `a source whose search throws is skipped`() = runTest {
        val bad = FakeNovelSource("bad", "Bad", failSearch = true)
        val good = FakeNovelSource("good", "Good",
            searchResults = listOf(SManga(sourceId = "good", url = "u1", title = "Solo Leveling", coverUrl = null)),
            chapters = listOf(novelChapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(bad, good)

        val result = resolver.findCandidates("Solo Leveling")

        assertEquals(1, result.size)
        assertEquals("good", result[0].source.id)
    }

    @Test
    fun `matchedChapterCount floors split chapters`() = runTest {
        val source = FakeNovelSource("a", "A",
            searchResults = listOf(SManga(sourceId = "a", url = "u1", title = "Solo Leveling", coverUrl = null)),
            chapters = listOf(novelChapter(1f), novelChapter(1.1f), novelChapter(1.2f), novelChapter(2f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("Solo Leveling")

        assertEquals(2, result[0].matchedChapterCount)
    }

    @Test
    fun `hasRequestedChapter reflects requested number`() = runTest {
        val source = FakeNovelSource("a", "A",
            searchResults = listOf(SManga(sourceId = "a", url = "u1", title = "Solo Leveling", coverUrl = null)),
            chapters = listOf(novelChapter(1f), novelChapter(5f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        assertTrue(resolver.findCandidates("Solo Leveling", requestedChapterNumber = 5f)[0].hasRequestedChapter)
        // Novy resolver objekt - cache z minuleho dotazu by jinak vratila drivejsi flag.
        val fresh = NovelResolver(sourceManager, settings)
        assertTrue(!fresh.findCandidates("Solo Leveling", requestedChapterNumber = 7f)[0].hasRequestedChapter)
    }

    @Test
    fun `second lookup for the same title is served from cache`() = runTest {
        val source = FakeNovelSource("a", "A",
            searchResults = listOf(SManga(sourceId = "a", url = "u1", title = "Solo Leveling", coverUrl = null)),
            chapters = listOf(novelChapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        resolver.findCandidates("Solo Leveling")
        val callsAfterFirst = source.searchCalls
        val cached = resolver.findCandidates("Solo Leveling")

        assertEquals(callsAfterFirst, source.searchCalls)
        assertEquals(1, cached.size)
    }

    @Test
    fun `duplicate emission per source is suppressed`() = runTest {
        // Stejny zdroj se do vysledku dostane jen jednou i kdyby flow emitoval dvakrat.
        val source = FakeNovelSource("a", "A",
            searchResults = listOf(SManga(sourceId = "a", url = "u1", title = "Solo Leveling", coverUrl = null)),
            chapters = listOf(novelChapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("Solo Leveling")

        assertEquals(1, result.count { it.source.id == "a" })
    }

    @Test
    fun `an empty result is re-searched after the negative cache TTL expires`() = runTest {
        // SRC-4: "zadny zdroj to nema" nesmi zustat vazene na cely beh procesu -
        // po TTL se sweep zkusi znovu (transientni vypadek muze pominout).
        var now = 1_000_000L
        resolver.nowMs = { now }
        val source = FakeNovelSource("a", "A") // search vraci prazdno
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        resolver.findCandidates("Missing Novel")
        assertEquals(1, source.searchCalls)

        // V ramci TTL se negativni vysledek servuje z cache.
        resolver.findCandidates("Missing Novel")
        assertEquals(1, source.searchCalls)

        // Po TTL se hleda znovu.
        now += 10 * 60 * 1000L + 1
        resolver.findCandidates("Missing Novel")
        assertEquals(2, source.searchCalls)
    }
}

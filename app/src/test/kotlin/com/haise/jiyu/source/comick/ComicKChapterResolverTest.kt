package com.haise.jiyu.source.comick

import com.haise.jiyu.settings.FakeDataStore
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Testy asertuji na hotovem seznamu - produkcni kod uz kandidaty streamuje jeden po
 * druhem (viz ComicKChapterResolver.findCandidatesFlow), tenhle helper je jen sesbira. */
private suspend fun ComicKChapterResolver.findCandidates(
    comicKMangaId: String,
    comicKMangaUrl: String,
    comicKTitle: String,
    comicKContentType: String,
    requestedChapterNumber: Float?,
): List<ResolvedCandidate> = findCandidatesFlow(
    comicKMangaId, comicKMangaUrl, comicKTitle, comicKContentType, requestedChapterNumber,
).toList()

private class FakeSource(
    override val id: String,
    override val name: String,
    override val contentType: String,
    private val searchResults: List<SManga> = emptyList(),
    private val chapters: List<SChapter> = emptyList(),
    private val failSearch: Boolean = false,
    override val isAdult: Boolean = false,
) : MangaSource {
    override suspend fun search(query: String, page: Int, filter: MangaFilter) =
        if (failSearch) throw RuntimeException("boom") else searchResults
    override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = chapters
    override suspend fun getPageList(chapter: SChapter) = emptyList<com.haise.jiyu.source.Page>()
}

class ComicKChapterResolverTest {

    private lateinit var sourceManager: SourceManager
    private lateinit var settings: SettingsRepository
    private lateinit var comicKSource: ComicKSource
    private lateinit var resolver: ComicKChapterResolver

    @Before
    fun setUp() {
        sourceManager = mockk()
        settings = SettingsRepository(FakeDataStore())
        comicKSource = mockk()
        // Výchozí: žádné alternativní názvy ani content_rating - findCandidates spadne
        // zpátky na comicKTitle samotný a titul se bere jako ne-adult, což zachovává
        // chování testů psaných před zavedením alt. názvů/adult filtru.
        coEvery { comicKSource.getTitleInfo(any()) } returns ComicKTitleInfo(emptyList(), null)
        // relaxed = true - test se zajima jen o vyhledavaci logiku, ne o skutecne potlaceni
        // Cloudflare vyzev (suppressInteractiveChallenge je jen var property nastavovana kolem
        // hledani, viz ComicKChapterResolver.searchAndFetchStreaming).
        val cloudflareInterceptor = mockk<CloudflareInterceptor>(relaxed = true)
        resolver = ComicKChapterResolver(sourceManager, settings, comicKSource, cloudflareInterceptor)
    }

    @Test
    fun `only searches sources in the same content-type group as the ComicK title`() = runTest {
        val manhwaMatch = SManga(sourceId = "src-manhwa", url = "u1", title = "Solo Leveling", coverUrl = null)
        val manhwaSource = FakeSource("src-manhwa", "Manhwa Site", "MANHWA", searchResults = listOf(manhwaMatch), chapters = listOf(chapter(1f)))
        val novelSource = FakeSource("src-novel", "Novel Site", "NOVEL", searchResults = listOf(manhwaMatch.copy(sourceId = "src-novel")))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(manhwaSource, novelSource)

        val result = resolver.findCandidates("comick-id-1", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(1, result.size)
        assertEquals("src-manhwa", result[0].source.id)
    }

    @Test
    fun `manga, manhwa and manhua sources are all treated as the same group`() = runTest {
        val match = SManga(sourceId = "src-manga", url = "u1", title = "Solo Leveling", coverUrl = null)
        val mangaSource = FakeSource("src-manga", "Manga Site", "MANGA", searchResults = listOf(match), chapters = listOf(chapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(mangaSource)

        // ComicK title is MANHWA, candidate source is generically tagged MANGA - must still match.
        val result = resolver.findCandidates("comick-id-2", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(1, result.size)
    }

    @Test
    fun `only keeps candidates whose normalized title matches`() = runTest {
        val wrongMatch = SManga(sourceId = "src-a", url = "u1", title = "A Completely Different Title", coverUrl = null)
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(wrongMatch))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-3", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a source whose search throws is skipped, not propagated`() = runTest {
        val failing = FakeSource("src-fail", "Broken Site", "MANHWA", failSearch = true)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(failing)

        val result = resolver.findCandidates("comick-id-4", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `hasRequestedChapter is true when a candidate's chapter list contains a matching chapter number`() = runTest {
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f), chapter(5f), chapter(5.5f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-5", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = 5f)

        assertEquals(1, result.size)
        assertTrue(result[0].hasRequestedChapter)
        // floor(5f) a floor(5.5f) jsou obe 5 - matchedChapterCount pocita cele kapitoly (viz
        // test "floors fractional chapter numbers" nize), takze tady je to 2, ne 3.
        assertEquals(2, result[0].matchedChapterCount)
    }

    @Test
    fun `matchedChapterCount counts distinct chapter numbers, not one row per scanlation group`() = runTest {
        // ComicK (a i jiné zdroje) uklada kazdy preklad kapitoly zvlast - stejne cislo
        // kapitoly muze mit vic radku, kdyz ji prelozilo vic skupin. Pomer v UI ma
        // ukazovat "kolik ruznych kapitol zdroj ma", ne "kolik radku ma v databazi".
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val duplicated = listOf(chapter(1f), chapter(1f), chapter(2f), chapter(2f), chapter(2f), chapter(3f))
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(match), chapters = duplicated)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-5b", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(3, result[0].matchedChapterCount)
    }

    @Test
    fun `matchedChapterCount floors fractional chapter numbers so split-raw sources aren't counted above 100 percent`() = runTest {
        // Overeno zive na MangaPark API pro Solo Leveling: 242 radku s cisly 0, 0.1, 1, 1.1,
        // 2, 2.1 ... (kazdy preklad rozdeleny na vic casti), zatimco ComicK ma pro stejny
        // titul jen 209 unikatnich cisel. Bez floor() by pomer v UI ukazoval "242/209 kapitol".
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val splitRaws = listOf(chapter(1f), chapter(1.1f), chapter(2f), chapter(2.1f), chapter(2.2f), chapter(3f))
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(match), chapters = splitRaws)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-5c", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(3, result[0].matchedChapterCount)
    }

    @Test
    fun `hasRequestedChapter is false when no candidate chapter is close enough`() = runTest {
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f), chapter(2f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-6", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = 99f)

        assertEquals(1, result.size)
        assertTrue(!result[0].hasRequestedChapter)
    }

    @Test
    fun `requestedChapterNumber null means hasRequestedChapter is always true`() = runTest {
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-7", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertTrue(result[0].hasRequestedChapter)
    }

    @Test
    fun `favorite sources are marked as favorite`() = runTest {
        // Razeni "oblibene prvni" je od zavedeni streamovani (viz findCandidatesFlow)
        // starost SourceResolverViewModelu, ne resolveru - ten uz kandidaty vraci v
        // poradi, v jakem prisly, ne serazene. Tenhle test proto overuje jen samotne
        // oznaceni isFavorite, ne pozici ve vysledku.
        settings.toggleFavoriteSource("src-b")
        val matchA = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val matchB = SManga(sourceId = "src-b", url = "u2", title = "Solo Leveling", coverUrl = null)
        val sourceA = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(matchA), chapters = listOf(chapter(1f)))
        val sourceB = FakeSource("src-b", "Site B", "MANHWA", searchResults = listOf(matchB), chapters = listOf(chapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(sourceA, sourceB)

        val result = resolver.findCandidates("comick-id-8", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertTrue(result.first { it.source.id == "src-b" }.isFavorite)
        assertTrue(!result.first { it.source.id == "src-a" }.isFavorite)
    }

    @Test
    fun `a second call for the same comicKMangaId does not re-search or re-fetch chapters`() = runTest {
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        var searchCalls = 0
        val source = object : MangaSource {
            override val id = "src-a"
            override val name = "Site A"
            override val contentType = "MANHWA"
            override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> {
                searchCalls++
                return listOf(match)
            }
            override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
            override suspend fun getMangaDetails(manga: SManga) = manga
            override suspend fun getChapterList(manga: SManga) = listOf(chapter(1f))
            override suspend fun getPageList(chapter: SChapter) = emptyList<com.haise.jiyu.source.Page>()
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        resolver.findCandidates("comick-id-9", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = 1f)
        resolver.findCandidates("comick-id-9", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = 2f)

        assertEquals(1, searchCalls)
    }

    @Test
    fun `searches and matches using the ComicK default alt title when it differs from the stored title`() = runTest {
        // Presne situace, ktera zpusobovala "zadny zdroj to nema" i kdyz zdroj existoval:
        // ComicK titul je ulozeny pod "I am the only the one who levels up", ale zdroj
        // (napr. Asura) ho eviduje pod "Solo Leveling" - to je zrovna alt. nazev s
        // is_default=true, ktery getTitleInfo().alternateTitles vraci jako prvni.
        coEvery { comicKSource.getTitleInfo("u1") } returns ComicKTitleInfo(listOf("Solo Leveling", "I Alone Level-Up"), null)
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        var searchedWith: String? = null
        val source = object : MangaSource {
            override val id = "src-a"
            override val name = "Site A"
            override val contentType = "MANHWA"
            override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> {
                searchedWith = query
                return listOf(match)
            }
            override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
            override suspend fun getMangaDetails(manga: SManga) = manga
            override suspend fun getChapterList(manga: SManga) = listOf(chapter(1f))
            override suspend fun getPageList(chapter: SChapter) = emptyList<com.haise.jiyu.source.Page>()
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates(
            "comick-id-10", "u1", "I am the only the one who levels up", "MANHWA", requestedChapterNumber = null,
        )

        assertEquals("Solo Leveling", searchedWith)
        assertEquals(1, result.size)
        assertEquals("src-a", result[0].source.id)
    }

    @Test
    fun `falls back to comicKTitle when fetching alternate titles fails`() = runTest {
        coEvery { comicKSource.getTitleInfo("u1") } throws RuntimeException("network down")
        val match = SManga(sourceId = "src-a", url = "u1", title = "Solo Leveling", coverUrl = null)
        val source = FakeSource("src-a", "Site A", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(source)

        val result = resolver.findCandidates("comick-id-11", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(1, result.size)
    }

    @Test
    fun `when fetching title info fails, adult sources are still searched (unknown rating treated as possibly adult)`() = runTest {
        coEvery { comicKSource.getTitleInfo("u1") } throws RuntimeException("network down")
        val match = SManga(sourceId = "src-adult", url = "u1", title = "Solo Leveling", coverUrl = null)
        val adultSource = FakeSource("src-adult", "Adult Site", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f)), isAdult = true)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adultSource)

        val result = resolver.findCandidates("comick-id-14", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(1, result.size)
        assertEquals("src-adult", result[0].source.id)
    }

    @Test
    fun `a non-adult ComicK title never searches isAdult sources, even when the source would match`() = runTest {
        coEvery { comicKSource.getTitleInfo("u1") } returns ComicKTitleInfo(emptyList(), "safe")
        val match = SManga(sourceId = "src-adult", url = "u1", title = "Solo Leveling", coverUrl = null)
        val adultSource = FakeSource("src-adult", "Adult Site", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f)), isAdult = true)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adultSource)

        val result = resolver.findCandidates("comick-id-12", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an adult ComicK title (erotica or pornographic) searches isAdult sources too`() = runTest {
        coEvery { comicKSource.getTitleInfo("u1") } returns ComicKTitleInfo(emptyList(), "erotica")
        val match = SManga(sourceId = "src-adult", url = "u1", title = "Solo Leveling", coverUrl = null)
        val adultSource = FakeSource("src-adult", "Adult Site", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f)), isAdult = true)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adultSource)

        val result = resolver.findCandidates("comick-id-13", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(1, result.size)
        assertEquals("src-adult", result[0].source.id)
    }

    @Test
    fun `a suggestive-rated ComicK title is still treated as non-adult`() = runTest {
        coEvery { comicKSource.getTitleInfo("u1") } returns ComicKTitleInfo(emptyList(), "suggestive")
        val match = SManga(sourceId = "src-adult", url = "u1", title = "Solo Leveling", coverUrl = null)
        val adultSource = FakeSource("src-adult", "Adult Site", "MANHWA", searchResults = listOf(match), chapters = listOf(chapter(1f)), isAdult = true)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adultSource)

        val result = resolver.findCandidates("comick-id-14", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a pornographic ComicK title still includes non-adult sources alongside isAdult ones`() = runTest {
        coEvery { comicKSource.getTitleInfo("u1") } returns ComicKTitleInfo(emptyList(), "pornographic")
        val matchA = SManga(sourceId = "src-normal", url = "u1", title = "Solo Leveling", coverUrl = null)
        val matchB = SManga(sourceId = "src-adult", url = "u2", title = "Solo Leveling", coverUrl = null)
        val normalSource = FakeSource("src-normal", "Normal Site", "MANHWA", searchResults = listOf(matchA), chapters = listOf(chapter(1f)))
        val adultSource = FakeSource("src-adult", "Adult Site", "MANHWA", searchResults = listOf(matchB), chapters = listOf(chapter(1f)), isAdult = true)
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(normalSource, adultSource)

        val result = resolver.findCandidates("comick-id-15", "u1", "Solo Leveling", "MANHWA", requestedChapterNumber = null)

        assertEquals(2, result.size)
    }

    private fun chapter(number: Float) = SChapter(
        sourceId = "x", mangaUrl = "u", url = "c/$number", name = "Ch.$number",
        chapterNumber = number, dateUpload = 0L,
    )
}

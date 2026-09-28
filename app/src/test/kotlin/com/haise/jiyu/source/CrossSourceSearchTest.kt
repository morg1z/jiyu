package com.haise.jiyu.source

import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtrační pravidla [CrossSourceSearch] - co se do sweepu vůbec pouští. Stejný vzor
 * jako [com.haise.jiyu.source.comick.ComicKChapterResolverTest] (FakeSource + mockovaný
 * SourceManager); asertuje se na sesbíraném seznamu streamu.
 */
private class FakeSource(
    override val id: String,
    override val name: String,
    override val contentType: String,
    private val searchResults: List<SManga> = emptyList(),
    private val chapters: List<SChapter> = emptyList(),
    private val failSearch: Boolean = false,
    override val isAdult: Boolean = false,
    override val isBroken: Boolean = false,
    override val includeInGlobalSearch: Boolean = true,
    override val language: String = "en",
) : MangaSource {
    override suspend fun search(query: String, page: Int, filter: MangaFilter) =
        if (failSearch) throw RuntimeException("boom") else searchResults
    override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = chapters
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
}

class CrossSourceSearchTest {

    private val sourceManager: SourceManager = mockk()
    private val cloudflareInterceptor = mockk<CloudflareInterceptor>(relaxed = true)
    private val search = CrossSourceSearch(sourceManager, cloudflareInterceptor)

    private fun chapter(number: Float, sourceId: String = "src-a") = SChapter(
        sourceId = sourceId, mangaUrl = "u1", url = "c$number", name = "Ch $number",
        chapterNumber = number, dateUpload = 0L,
    )

    private fun mangaEntity(sourceId: String = "origin", title: String = "Solo Leveling") = MangaEntity(
        id = "$sourceId::u0", sourceId = sourceId, url = "u0", title = title, coverUrl = null,
        description = null, status = null,
    )

    private val origin = FakeSource("origin", "Dead Site", "MANHWA")

    private fun matching(sourceId: String, contentType: String = "MANHWA", chapters: List<SChapter> = listOf(chapter(1f))) =
        FakeSource(sourceId, "Site $sourceId", contentType,
            searchResults = listOf(SManga(sourceId = sourceId, url = "u1", title = "Solo Leveling", coverUrl = null)),
            chapters = chapters)

    @Test
    fun `matching source emits a seed with its chapters`() = runTest {
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, matching("src-a"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `the original source itself is never re-searched`() = runTest {
        // i kdyby puvodni zdroj nasel titul, nema smysl ho nabidnout - relink na sebe sama
        // je no-op, ktery by uzivatele jen mastl.
        val selfMatch = matching("origin")
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(selfMatch, matching("src-a"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `comick is excluded - it is a metadata catalog without own pages`() = runTest {
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, matching("comick"), matching("src-a"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `broken and non-global-search sources are skipped`() = runTest {
        val broken = matching("src-broken").let { s ->
            FakeSource(s.id, s.name, s.contentType, searchResults = listOf(SManga(s.id, "u1", "Solo Leveling", null)), chapters = listOf(chapter(1f)), isBroken = true)
        }
        val noGlobal = matching("src-noglobal").let { s ->
            FakeSource(s.id, s.name, s.contentType, searchResults = listOf(SManga(s.id, "u1", "Solo Leveling", null)), chapters = listOf(chapter(1f)), includeInGlobalSearch = false)
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, broken, noGlobal, matching("src-a"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `only same content-group sources are searched (novel title never hits manga sites)`() = runTest {
        val novelOrigin = FakeSource("origin-novel", "Dead Novel Site", "NOVEL")
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(
            novelOrigin,
            matching("src-manga", "MANGA"),
            matching("src-novel", "NOVEL"),
        )
        val entity = mangaEntity(sourceId = "origin-novel").copy(contentType = "NOVEL")

        val seeds = search.seeds(entity, novelOrigin).toList()

        assertEquals(listOf("src-novel"), seeds.map { it.source.id })
    }

    @Test
    fun `manga, manhwa and manhua are one cross-search group`() = runTest {
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(
            origin,
            matching("src-manga", "MANGA"),
            matching("src-manhua", "MANHUA"),
        )

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(setOf("src-manga", "src-manhua"), seeds.map { it.source.id }.toSet())
    }

    @Test
    fun `only same-language sources are searched`() = runTest {
        val ruSource = matching("src-ru").let { s ->
            FakeSource(s.id, s.name, s.contentType, searchResults = listOf(SManga(s.id, "u1", "Solo Leveling", null)), chapters = listOf(chapter(1f)), language = "ru")
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, ruSource, matching("src-en"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-en"), seeds.map { it.source.id })
    }

    @Test
    fun `a title from a non-adult source never searches isAdult sources`() = runTest {
        val adult = matching("src-adult").let { s ->
            FakeSource(s.id, s.name, s.contentType, searchResults = listOf(SManga(s.id, "u1", "Solo Leveling", null)), chapters = listOf(chapter(1f)), isAdult = true)
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, adult, matching("src-a"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `a title from an adult source may search isAdult sources`() = runTest {
        val adultOrigin = FakeSource("origin-adult", "Dead Adult Site", "MANHWA", isAdult = true)
        val adult = matching("src-adult").let { s ->
            FakeSource(s.id, s.name, s.contentType, searchResults = listOf(SManga(s.id, "u1", "Solo Leveling", null)), chapters = listOf(chapter(1f)), isAdult = true)
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adultOrigin, adult)

        val seeds = search.seeds(mangaEntity(sourceId = "origin-adult"), adultOrigin).toList()

        assertEquals(listOf("src-adult"), seeds.map { it.source.id })
    }

    @Test
    fun `normalized title match also honors stored alternate titles`() = runTest {
        // Zdroj eviduje titul pod alternativnim nazvem - ulozene alternateTitles
        // (JSON, viz serializeAltTitles) musi stacit ke shode.
        val entity = mangaEntity(title = "I Level Up Alone").copy(alternateTitles = """["Solo Leveling"]""")
        val foreignNamed = FakeSource("src-a", "Site A", "MANHWA",
            searchResults = listOf(SManga("src-a", "u1", "Solo Leveling", null)),
            chapters = listOf(chapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, foreignNamed)

        val seeds = search.seeds(entity, origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `different normalized titles are not emitted`() = runTest {
        val other = FakeSource("src-a", "Site A", "MANHWA",
            searchResults = listOf(SManga("src-a", "u1", "Totally Different Work", null)),
            chapters = listOf(chapter(1f)))
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, other)

        assertTrue(search.seeds(mangaEntity(), origin).toList().isEmpty())
    }

    @Test
    fun `a candidate with an empty chapter list is not emitted`() = runTest {
        // Presun na zdroj bez kapitol by dal prazdny seznam - lepsi zadny kandidat.
        val noChapters = FakeSource("src-a", "Site A", "MANHWA",
            searchResults = listOf(SManga("src-a", "u1", "Solo Leveling", null)),
            chapters = emptyList())
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, noChapters)

        assertTrue(search.seeds(mangaEntity(), origin).toList().isEmpty())
    }

    @Test
    fun `a missing original source skips the language filter but still excludes adult sources`() = runTest {
        // Zdroj zmizel z appky uplne (SourceManager ho uz nezna) - bez jazykoveho filtru
        // se hleda vsude, adult zdroje se konzervativne NEprohledavaji (titul mohl byt
        // adult, ale bezpecejsi je vysledek nenajit nez omylem presunout na 18+ web).
        val adult = matching("src-adult").let { s ->
            FakeSource(s.id, s.name, s.contentType, searchResults = listOf(SManga(s.id, "u1", "Solo Leveling", null)), chapters = listOf(chapter(1f)), isAdult = true)
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(adult, matching("src-a"))

        val seeds = search.seeds(mangaEntity(sourceId = "gone"), originalSource = null).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }

    @Test
    fun `a source whose search throws is skipped, others still report`() = runTest {
        val failing = matching("src-fail").let { s ->
            FakeSource(s.id, s.name, s.contentType, failSearch = true)
        }
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns listOf(origin, failing, matching("src-a"))

        val seeds = search.seeds(mangaEntity(), origin).toList()

        assertEquals(listOf("src-a"), seeds.map { it.source.id })
    }
}

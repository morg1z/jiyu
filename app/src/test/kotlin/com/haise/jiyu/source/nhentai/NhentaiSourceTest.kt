package com.haise.jiyu.source.nhentai

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * nhentai v1 API (/api/galleries/..., /api/gallery/{id}) je vyrazena, pouziva
 * se v2 (/api/v2/...) - viz komentar v NhentaiSource.kt. Listing endpointy
 * vraci jen ploche pole (english_title/thumbnail, zadne tag objekty), detail
 * endpoint vraci bohatou strukturu (title objekt, plne tagy, "pages" pole
 * s hotovou cestou k souboru vcetne pripony).
 */
class NhentaiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NhentaiSource

    private val listItemJson = """
        {"id": 999, "media_id": "12345", "english_title": "Test Gallery", "japanese_title": "テスト", "thumbnail": "galleries/12345/thumb.webp", "num_pages": 2}
    """.trimIndent()

    // "/galleries" (obecny vypis pouzity pro getPopular) vraci na rozdil od
    // "/galleries/popular" objekt s "result" polem, ne holé pole - overeno zivym
    // volanim API (viz komentar v NhentaiSource.kt).
    private val galleriesListJson = """{ "result": [ $listItemJson ] }"""
    private val searchJson = """{ "result": [ $listItemJson ] }"""
    private val categoryTagsJson = """{ "result": [ {"id":1,"type":"category","name":"manga","slug":"manga","count":100} ] }"""
    private val languageTagsJson = """{ "result": [ {"id":2,"type":"language","name":"english","slug":"english","count":100} ] }"""
    private val tagTagsJson = """{ "result": [ {"id":3,"type":"tag","name":"big breasts","slug":"big-breasts","count":235000} ] }"""

    private val galleryDetailJson = """
        {
          "id": 999,
          "media_id": "12345",
          "num_pages": 2,
          "title": {"english": "Test Gallery", "pretty": "Test", "japanese": "テスト"},
          "cover": {"path": "galleries/12345/cover.webp.webp"},
          "pages": [
            {"number": 1, "path": "galleries/12345/1.webp"},
            {"number": 2, "path": "galleries/12345/2.webp"}
          ],
          "tags": [
            {"type": "artist", "name": "Some Artist"},
            {"type": "tag", "name": "comedy"}
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v2/tags/category") -> MockResponse().setBody(categoryTagsJson)
                    path.startsWith("/api/v2/tags/language") -> MockResponse().setBody(languageTagsJson)
                    path.startsWith("/api/v2/tags/tag") -> MockResponse().setBody(tagTagsJson)
                    path.startsWith("/api/v2/search") -> MockResponse().setBody(searchJson)
                    path.startsWith("/api/v2/galleries/999") -> MockResponse().setBody(galleryDetailJson)
                    path.startsWith("/api/v2/galleries?") -> MockResponse().setBody(galleriesListJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NhentaiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover from flat list item`() = runTest {
        val result = source.getPopular(1)

        assertEquals(1, result.size)
        assertEquals("Test Gallery", result[0].title)
        assertEquals("/gallery/999", result[0].url)
        assertTrue(result[0].coverUrl!!.endsWith("12345/thumb.webp"))
    }

    @Test
    fun `getPopular uses the paginated general listing, not the fixed today's-top-5 endpoint`() = runTest {
        // "/galleries/popular" nema podle OpenAPI schematu appky vubec parametr "page" -
        // je to "Get today's popular galleries", vzdy stejnych ~5 polozek bez ohledu na
        // stranku (overeno zivym volanim: page=1/2/3 vraci identicky vysledek). Obecny
        // "/galleries?page=N" ma realne strankovani (25 ruznych polozek na stranku) -
        // presne to appka teď musí volat, jinak Prochazet zobrazi porad jen tu samou
        // hrstku titulu bez ohledu na scroll.
        source.getPopular(1)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/v2/galleries?"))
        assertTrue("nesmi volat fixni endpoint bez strankovani", !request.path!!.contains("/galleries/popular"))
    }

    @Test
    fun `getMangaDetails resolves artist, genres and description from full tag objects`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)

        assertEquals("Some Artist", details.author)
        assertEquals(listOf("comedy"), details.genres)
        assertTrue(details.description!!.contains("Pages: 2"))
    }

    @Test
    fun `getPageList builds one URL per page directly from path field`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(2, pages.size)
        assertTrue(pages[0].url.endsWith("/galleries/12345/1.webp"))
        assertTrue(pages[1].url.endsWith("/galleries/12345/2.webp"))
    }

    @Test
    fun `getChapterList always returns a single synthetic chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getAvailableTags exposes the real tag taxonomy next to category and language`() = runTest {
        val tags = source.getAvailableTags()

        assertTrue(tags.any { it.id == "tag:big-breasts" && it.label == "big breasts" })
        assertTrue(tags.any { it.id == "category:manga" && it.label == "manga (category)" })
        assertTrue(tags.any { it.id == "language:english" && it.label == "english (language)" })
    }

    @Test
    fun `genre filter combines multiple selected tags into an AND query`() = runTest {
        // Driv se aplikoval jen prvni vybrany tag - nhentai query syntax ale umi
        // "tag:a tag:b" (AND), coz je presne to, co uzivatel v pickeru oznaci.
        source.getPopular(1, com.haise.jiyu.source.MangaFilter(genres = listOf("tag:big-breasts", "language:english")))

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/v2/search"))
        assertEquals("tag:big-breasts language:english", request.requestUrl!!.queryParameter("query"))
    }

    @Test
    fun `server error returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        server.start()
        val failingSource = NhentaiSource(redirectingClient(server))

        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

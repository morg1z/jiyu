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

    private val popularJson = "[ $listItemJson ]"
    private val searchJson = """{ "result": [ $listItemJson ] }"""

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
                    path.startsWith("/api/v2/galleries/popular") -> MockResponse().setBody(popularJson)
                    path.startsWith("/api/v2/search") -> MockResponse().setBody(searchJson)
                    path.startsWith("/api/v2/galleries/999") -> MockResponse().setBody(galleryDetailJson)
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

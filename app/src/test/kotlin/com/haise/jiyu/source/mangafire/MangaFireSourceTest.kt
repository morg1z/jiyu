package com.haise.jiyu.source.mangafire

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
 * mangafire.to je cisty JS-rendered SPA shell bez obsahu v HTML - misto
 * scrapovani se pouziva verejne JSON API objevene pres network tab v
 * realnem prohlizeci (viz komentar v MangaFireSource.kt).
 */
class MangaFireSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaFireSource

    private val listJson = """
        { "items": [ {"title": "Black Clover", "url": "/title/abc-black-clover", "poster": {"large": "https://cdn.example.com/bc.jpg"}} ] }
    """.trimIndent()

    private val detailJson = """
        { "data": {
            "title": "Black Clover", "poster": {"large": "https://cdn.example.com/bc.jpg"},
            "synopsisHtml": "A <b>farm boy</b> dreams big.",
            "genres": [{"id": 1, "title": "Action"}],
            "authors": [{"id": 2, "title": "Tabata Yuki"}]
        } }
    """.trimIndent()

    private val chaptersJson = """
        { "items": [ {"id": 999, "number": 1, "name": "", "language": "en", "createdAt": 1750000000} ] }
    """.trimIndent()

    private val pagesJson = """
        { "data": { "pages": [ {"url": "https://cdn.example.com/bc/1/01.jpg", "width": 800, "height": 1200} ] } }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/titles/abc/chapters") -> MockResponse().setBody(chaptersJson)
                    path.startsWith("/api/titles/abc") -> MockResponse().setBody(detailJson)
                    path.startsWith("/api/titles") -> MockResponse().setBody(listJson)
                    path.startsWith("/api/chapters/999") -> MockResponse().setBody(pagesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaFireSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Black Clover", result[0].title)
        assertEquals("https://cdn.example.com/bc.jpg", result[0].coverUrl)
        assertEquals("/title/abc-black-clover", result[0].url)
    }

    @Test
    fun `getMangaDetails parses genres, author and plain-text synopsis`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals(listOf("Action"), details.genres)
        assertEquals("Tabata Yuki", details.author)
        assertEquals("A farm boy dreams big.", details.description)
    }

    @Test
    fun `getChapterList parses chapter number from the number field`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("999", chapters[0].url)
    }

    @Test
    fun `getPageList parses page urls from chapters API`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/bc/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaFireSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

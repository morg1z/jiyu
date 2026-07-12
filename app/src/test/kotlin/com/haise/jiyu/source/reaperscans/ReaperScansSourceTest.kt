package com.haise.jiyu.source.reaperscans

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

class ReaperScansSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ReaperScansSource

    private val seriesListJson = """
        {"data": [{"series_slug": "test-series", "title": "Test Series", "thumbnail": "https://cdn.example.com/test.jpg"}]}
    """.trimIndent()

    private val seriesDetailJson = """
        {"title": "Test Series", "thumbnail": "https://cdn.example.com/test.jpg", "description": "A summary.", "author": "Jane"}
    """.trimIndent()

    private val chaptersJson = """
        {"data": [{"chapter_slug": "chapter-1", "chapter_name": "Chapter 1"}]}
    """.trimIndent()

    private val pagesJson = """
        {"content": ["https://cdn.example.com/test/1/01.jpg"]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/series?page=") -> MockResponse().setBody(seriesListJson)
                    path == "/series/test-series" -> MockResponse().setBody(seriesDetailJson)
                    path.startsWith("/chapter/query") -> MockResponse().setBody(chaptersJson)
                    path == "/chapter/test-series/chapter-1" -> MockResponse().setBody(pagesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ReaperScansSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses series list from data wrapper`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails, getChapterList and getPageList parse REST JSON`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Jane", details.author)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].name)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/test/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed JSON returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val failingSource = ReaperScansSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

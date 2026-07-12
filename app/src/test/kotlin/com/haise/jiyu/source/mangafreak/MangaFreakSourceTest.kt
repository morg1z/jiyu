package com.haise.jiyu.source.mangafreak

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

class MangaFreakSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaFreakSource

    private val listHtml = """
        <html><body>
        <div class="manga_poster"><a href="https://mangafreak.net/manga/test-series"><p>Test Series</p><img src="https://cdn.example.com/test.jpg" /></a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="manga_right"><h1>Test Series</h1><img src="https://cdn.example.com/test.jpg" /></div>
        <div class="manga_summary">A summary.</div>
        <div class="manga_genres"><a>Action</a></div>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body>
        <div class="chapter_list"><li><a href="https://mangafreak.net/manga/test-series/chapter-1">Chapter 1</a></li></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div class="chapter_container"><img src="https://cdn.example.com/test/1/01.jpg" /></div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/popular-manga") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml.plus(chaptersHtml))
                    path == "/manga/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaFreakSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and absolute cover URL`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals(listOf("Action"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaFreakSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

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

class MangaFireSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaFireSource

    private val listHtml = """
        <html><body>
        <div class="unit"><div class="inner">
          <a href="/manga/black-clover.abc"><div class="info"><span class="name">Black Clover</span></div><img data-src="https://cdn.example.com/bc.jpg" /></a>
        </div></div>
        </body></html>
    """.trimIndent()

    private val chapterListHtml = """
        <html><body>
        <div id="chapter-list">
          <li data-number="1"><a href="/read/black-clover.abc/en/chapter-1"><span class="name">Chapter 1</span></a></li>
        </div>
        </body></html>
    """.trimIndent()

    private val imagesJson = """
        {"result": {"images": [["https://cdn.example.com/bc/1/01.jpg", 800, 1200]]}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/filter") -> MockResponse().setBody(listHtml)
                    path == "/manga/black-clover.abc" -> MockResponse().setBody(chapterListHtml)
                    path.startsWith("/ajax/read/") -> MockResponse().setBody(imagesJson)
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
    }

    @Test
    fun `getChapterList uses data-number attribute for chapter number`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getPageList uses ajax images API when available`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/bc/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
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

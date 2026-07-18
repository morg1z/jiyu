package com.haise.jiyu.source.mangahome

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

class MangaHomeSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaHomeSource

    private val listHtml = """
        <html><body>
        <a class="post-cover" title="Test Series" href="/manga/test-series"><img src="//cdn.example.com/test.jpg"/></a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Test Series</h1>
        <p><span>Status:</span> Ongoing<span class="mobile-none">latest</span></p>
        <p>Author(s):</span><a href="/author/x">Some Author</a></p>
        <p>Genre(s):</span><a href="/action">Action</a></p>
        <p class="hide">A summary.</p>
        <ul class="detail-chlist">
        <li><a href="/manga/test-series/c2" name="">Chapter 2</a><span class="time">Jul 18,2026</span></li>
        <li><a href="/manga/test-series/c1" name="">Chapter 1</a><span class="time">Jan 10,2026</span></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img class="image" src="//cdn.example.com/p/1.jpg"/>
        <img class="image" src="//cdn.example.com/p/2.jpg"/>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/directory/") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/c2" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaHomeSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `full flow parses details, chapters and single-page reader`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p/1.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaHomeSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

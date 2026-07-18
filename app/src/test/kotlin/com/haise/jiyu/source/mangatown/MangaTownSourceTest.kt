package com.haise.jiyu.source.mangatown

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

class MangaTownSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaTownSource

    private val listHtml = """
        <html><body>
        <a class="manga_cover" href="/manga/test-series/" title="Test Series"><img src="//cdn.example.com/test.jpg" alt="Test Series"/></a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="title-top">Test Series</h1>
        <p id="hide">A summary.</p>
        <ul>
        <li><b>Author(s):</b><a href="/author/x">Some Author</a></li>
        <li><b>Genre(s):</b><a href="/g/action">Action</a>,<a href="/g/adventure">Adventure</a></li>
        <li><b>Status(s):</b>Ongoing &nbsp;<a href="/manga/test-series/c2" title="latest">Test Series Ch.2</a></li>
        </ul>
        <ul class="chapter_list">
        <li><a href="/manga/test-series/c2/" name="">Test Series 2</a><span class="time">Jul 18</span></li>
        <li><a href="/manga/test-series/c1/" name="">Test Series 1</a><span class="time">Jan 10</span></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val pageOneHtml = """
        <html><body><img src="//cdn.example.com/p/1.jpg" id="image" alt="p1"> <script>total_pages = 2;</script></body></html>
    """.trimIndent()

    private val pageTwoHtml = """
        <html><body><img src="//cdn.example.com/p/2.jpg" id="image" alt="p2"></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/directory/") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series/" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/c2/" -> MockResponse().setBody(pageOneHtml)
                    path == "/manga/test-series/c2/2.html" -> MockResponse().setBody(pageTwoHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaTownSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, normalizes protocol-relative url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("//cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `full flow parses details, chapters and multi-page reader`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Some Author", details.author)
        assertEquals(listOf("Action", "Adventure"), details.genres)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p/1.jpg", pages[0].url)
        assertEquals("https://cdn.example.com/p/2.jpg", pages[1].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaTownSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

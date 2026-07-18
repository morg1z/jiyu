package com.haise.jiyu.source.mangakatana

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

class MangaKatanaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaKatanaSource

    private val listHtml = """
        <html><body>
        <div class="item"><h3 class="title"><a href="/manga/test-series.123">Test Series</a></h3>
        <img data-src="https://cdn.example.com/test.jpg" /></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="heading">Test Series</h1>
        <div class="summary"><p>A summary.</p></div>
        <div class="genres"><a>Action</a></div>
        <div class="d-cell-small value authors"><a class="author">Some Author</a></div>
        <div class="d-cell-small value status ongoing">Ongoing</div>
        <div class="chapters"><table><tbody>
        <tr><td><div class="chapter"><a href="/manga/test-series.123/c2">Chapter 2</a></div></td><td><div class="update_time">Jul-18-2026</div></td></tr>
        <tr><td><div class="chapter"><a href="/manga/test-series.123/c1">Chapter 1</a></div></td><td><div class="update_time">Jan-10-2026</div></td></tr>
        </tbody></table></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><script>var thzq=['https://i1.mangakatana.com/token/abc/0.jpg','https://i1.mangakatana.com/token/def/1.jpg',];</script></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/latest/") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series.123" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series.123/c2" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaKatanaSource(redirectingClient(server))
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
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `full flow parses details, chapters ordered newest-first and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Some Author", details.author)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://i1.mangakatana.com/token/abc/0.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaKatanaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

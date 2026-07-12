package com.haise.jiyu.source.rawkuma

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

class RawKumaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: RawKumaSource

    private val listHtml = """
        <html><body>
        <div class="bsx"><a href="/manga/test-series"><div class="tt">Test Series</div><img src="https://cdn.example.com/test.jpg" /></a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Test Series</h1>
        <div class="summary__content"><p>A summary.</p></div>
        <div class="genres-content"><a>Action</a></div>
        <div class="chbox"><a href="/manga/test-series/chapter-1">Chapter 1</a></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div id="readerarea"><img src="https://cdn.example.com/test/1/01.jpg" /></div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga/?m_orderby=") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = RawKumaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, language is japanese`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("ja", source.language)
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)

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
        val emptySource = RawKumaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

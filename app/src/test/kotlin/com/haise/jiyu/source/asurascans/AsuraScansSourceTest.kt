package com.haise.jiyu.source.asurascans

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

class AsuraScansSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: AsuraScansSource

    private val listHtml = """
        <html><body>
        <div class="series-card"><a href="/series/test-series"><span class="block">Test Series</span><img src="https://cdn.example.com/test.jpg" /></a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <span class="text-xl">Test Series</span>
        <img class="rounded" src="https://cdn.example.com/test.jpg" />
        <span class="font-medium text-sm">A description.</span>
        <div class="flex"><a href="/genre/action">Action</a></div>
        <div class="scrollbar-thumb-themecolor"><a href="/series/test-series/chapter-1">Chapter 1</a></div>
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
                    path.startsWith("/series?page=") -> MockResponse().setBody(listHtml)
                    path == "/series/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/series/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = AsuraScansSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses series-card title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A description.", details.description)
        assertEquals(listOf("Action"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/test/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = AsuraScansSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

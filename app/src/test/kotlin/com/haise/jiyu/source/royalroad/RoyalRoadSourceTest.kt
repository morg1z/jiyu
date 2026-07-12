package com.haise.jiyu.source.royalroad

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

class RoyalRoadSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: RoyalRoadSource

    private val listHtml = """
        <html><body>
        <div class="fiction-list-item"><div class="fiction-title"><a href="/fiction/12345/test-novel">Test Novel</a></div><img src="https://cdn.example.com/test.jpg" /></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="font-white">Test Novel</h1>
        <div class="fiction-image"><img src="https://cdn.example.com/test.jpg" /></div>
        <div class="description"><div class="hidden-content">A summary.</div></div>
        <div class="tags"><a>Fantasy</a></div>
        <table id="chapters"><tbody>
          <tr><td><a href="/fiction/12345/test-novel/chapter/1">Chapter 1</a></td><td data-content="01/01/2026"></td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div class="chapter-inner"><div class="chapter-content">This is the chapter text.</div></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/fictions/best-rated") -> MockResponse().setBody(listHtml)
                    path == "/fiction/12345/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/fiction/12345/test-novel/chapter/1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = RoyalRoadSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, contentType is NOVEL`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
        assertEquals("/fiction/12345/test-novel", result[0].url)
    }

    @Test
    fun `full flow parses details, chapters with date, and chapter text`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("NOVEL", details.contentType)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertTrue(chapters[0].dateUpload > 0L)

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
        val emptySource = RoyalRoadSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

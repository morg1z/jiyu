package com.haise.jiyu.source.nihonkuni

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

class NihonKuniSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NihonKuniSource

    private val listHtml = """
        <html><body>
        <div class="manga-card">
            <a class="manga-cover" href="read-test-series-chapter-2.html" style="background-image: url('https://cdn.example.com/test.jpg'), url('lazy.gif');"></a>
            <div class="manga-info">
                <a class="manga-title" href="manga-test-series-raw.html">Test Series</a>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
        <meta property="og:description" content="A summary of the series."/>
        </head><body>
        <div class="info-field-label">Author(s)</div>
        <div class="info-field-value"><a href="x">Some Author</a></div>
        <div class="info-field-label">Genre(s)</div>
        <div class="info-field-value"><a href="g1">Action</a> <a href="g2">Adventure</a></div>
        <div class="info-field-label">Status</div>
        <div class="info-field-value"><a href="s">On going</a></div>
        <div class="chapters-list-wrapper">
        <a href="read-test-series-chapter-2.html"><span class="chapter-name">Chapter 2</span></a>
        <a href="read-test-series-chapter-1.html"><span class="chapter-name">Chapter 1</span></a>
        </div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img id="page1" class="chapter-img" src="https://cdn.example.com/p/1.jpg" referrerpolicy="no-referrer">
        <img id="page2" class="chapter-img" src="https://cdn.example.com/p/2.jpg" referrerpolicy="no-referrer">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga-list.html") -> MockResponse().setBody(listHtml)
                    path == "/manga-test-series-raw.html" -> MockResponse().setBody(detailHtml)
                    path == "/read-test-series-chapter-2.html" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NihonKuniSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover from background-image style`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
        assertEquals("ja", source.language)
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary of the series.", details.description)
        assertEquals("Some Author", details.author)
        assertEquals(listOf("Action", "Adventure"), details.genres)
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
        val emptySource = NihonKuniSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

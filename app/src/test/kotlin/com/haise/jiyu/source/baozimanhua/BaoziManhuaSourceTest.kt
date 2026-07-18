package com.haise.jiyu.source.baozimanhua

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

class BaoziManhuaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: BaoziManhuaSource

    private val listHtml = """
        <html><body>
        <div class="comics-card"><a href="/comic/test-series" title="Test Series" class="comics-card__poster">
        <amp-img src="https://cdn.example.com/test.jpg"></amp-img></a>
        <a class="comics-card__info"><div class="comics-card__title"><h3>Test Series</h3></div></a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
        <meta name="og:novel:book_name" content="Test Series">
        <meta name="og:image" content="https://cdn.example.com/cover.jpg">
        <meta name="og:description" content="A summary.">
        <meta name="og:novel:author" content="Some Author">
        <meta name="og:novel:category" content="Action,Adventure">
        <meta name="og:novel:status" content="連載中">
        </head><body>
        <a class="comics-chapters__item" href="/user/page_direct?comic_id=test-series&section_slot=0&chapter_slot=2"><span>Chapter 2</span></a>
        <a class="comics-chapters__item" href="/user/page_direct?comic_id=test-series&section_slot=0&chapter_slot=1"><span>Chapter 1</span></a>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <amp-img id="chapter-img-0-0" src="https://cdn.example.com/p/1.jpg" data-src="https://cdn.example.com/p/1.jpg"></amp-img>
        <amp-img id="chapter-img-0-1" src="https://cdn.example.com/p/2.jpg" data-src="https://cdn.example.com/p/2.jpg"></amp-img>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/classify") -> MockResponse().setBody(listHtml)
                    path == "/comic/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/comic/chapter/test-series/0_2.html" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = BaoziManhuaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, content type is manhua`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("MANHUA", result[0].contentType)
    }

    @Test
    fun `full flow parses meta details, builds direct chapter url and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Some Author", details.author)
        assertEquals(listOf("Action", "Adventure"), details.genres)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals("/comic/chapter/test-series/0_2.html", chapters[0].url)

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
        val emptySource = BaoziManhuaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

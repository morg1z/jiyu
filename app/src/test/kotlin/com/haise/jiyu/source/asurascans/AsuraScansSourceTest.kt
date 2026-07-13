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

/**
 * asuracomic.net presmerovava na asurascans.com (novy Astro frontend) -
 * /series -> /browse, /series/{slug} -> /comics/{slug}, karty maji
 * div.series-card a stranky ctecky maji img[data-page-index].
 */
class AsuraScansSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: AsuraScansSource

    private val listHtml = """
        <html><body>
        <div class="series-card"><a href="/comics/test-series-abc123"><img src="https://cdn.example.com/test.jpg" alt="Test Series" /></a><div class="p-3"><h3>Test Series</h3></div></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
        <meta property="og:image" content="https://cdn.example.com/test.jpg" />
        <meta property="og:description" content="A description." />
        </head><body>
        <h1>Test Series</h1>
        <a href="/genre/action">Action</a>
        <a href="/comics/test-series-abc123/chapter/1"><span class="font-medium">Chapter 1</span></a>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="https://cdn.example.com/test/1/01.jpg" data-page-index="0" />
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/browse?page=") -> MockResponse().setBody(listHtml)
                    path == "/comics/test-series-abc123" -> MockResponse().setBody(detailHtml)
                    path == "/comics/test-series-abc123/chapter/1" -> MockResponse().setBody(pagesHtml)
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
        assertEquals(1f, chapters[0].chapterNumber)

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

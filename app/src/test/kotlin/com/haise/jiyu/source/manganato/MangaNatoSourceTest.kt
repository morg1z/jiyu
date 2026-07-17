package com.haise.jiyu.source.manganato

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

class MangaNatoSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaNatoSource

    private val listHtml = """
        <html><body>
        <a class="list-story-item bookmark_check cover" href="/manga/test-series" title="Test Series">
          <img class="lazy" src="https://cdn.example.com/test.jpg" data-src="https://cdn.example.com/test.jpg" />
        </a>
        <a class="list-story-item bookmark_check cover js-banner-ai-list-link" href="https://bit.ly/ad" title="">
          <img class="lazy" src="https://cdn.example.com/ad.jpg" />
        </a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="manga-info-pic"><img src="https://cdn.example.com/test.jpg" /></div>
        <ul class="manga-info-text">
          <li><h1>Test Series</h1></li>
          <li>Author(s) : Jane Doe</li>
          <li>Status : Ongoing</li>
          <li class="genres"><a href="#">Action</a></li>
        </ul>
        <div id="contentBox"><h2>Test Series summary:</h2>A test summary.</div>
        <div class="chapter-list"><div class="row"><span><a href="/manga/test-series/chapter-1">Chapter 1</a></span></div></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div class="container-chapter-reader"><img src="https://cdn.example.com/test/1/01.jpg" /></div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga-list/hot-manga") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaNatoSource(redirectingClient(server))
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
    fun `getMangaDetails strips Description prefix and parses table rows`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test summary.", details.description)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action"), details.genres)
        assertEquals("Ongoing", details.status)
    }

    @Test
    fun `getChapterList and getPageList parse chapter row and reader images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

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
        val emptySource = MangaNatoSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

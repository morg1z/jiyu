package com.haise.jiyu.source.comic

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
 * Overuje sdileny ComicSiteSource engine (viz comic/ComicSources.kt) - vsechny
 * konkretni comic zdroje sdileji tuhle logiku, lisi se jen v selektorech/base URL.
 */
class ComicSiteSourceTest {

    private lateinit var server: MockWebServer

    private class TestComicSource(base: String, client: okhttp3.OkHttpClient) : ComicSiteSource(
        id = "test-comic", name = "Test Comic", base = base, client = client,
    ) {
        override val popularPath = "/ComicList/MostPopular"
        override val comicItemSelector = "ul.list-comic li"
        override val comicLinkSelector = "a"
        override val comicCoverSelector = "img"
        override val chapterItemSelector = "ul.list-chapter li a"
        override val pageImgSelector = "div#divImage img, #divImage img"
        override val searchPath = "/Search/Comics?keyword="
        override val searchResultSelector = "ul.list-comic li"
    }

    private lateinit var source: TestComicSource

    private val listHtml = """
        <html><body>
        <ul class="list-comic">
          <li><a href="/Batman-Vol-1">Batman</a><img src="https://cdn.example.com/batman.jpg" /></li>
          <li><a href="/Superman-Vol-1">Superman</a><img data-src="https://cdn.example.com/superman.jpg" /></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="description">A dark knight fights crime.</div>
        <span class="status">Ongoing</span>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body>
        <ul class="list-chapter">
          <li><a href="/Batman-Vol-1/Issue-1">Issue #1</a></li>
          <li><a href="/Batman-Vol-1/Issue-2">Issue #2</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div id="divImage">
          <img src="https://cdn.example.com/batman/1/01.jpg" />
          <img src="https://cdn.example.com/batman/1/02.jpg" />
        </div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/ComicList/MostPopular") -> MockResponse().setBody(listHtml)
                    path.startsWith("/Search/Comics") -> MockResponse().setBody(listHtml)
                    path == "/Batman-Vol-1" -> MockResponse().setBody(detailHtml.plus(chaptersHtml))
                    path == "/Batman-Vol-1/Issue-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = TestComicSource(base = server.url("").toString().trimEnd('/'), client = redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, url and cover, both data-src and src variants`() = runTest {
        val result = source.getPopular(1)

        assertEquals(2, result.size)
        assertEquals("Batman", result[0].title)
        assertEquals("https://cdn.example.com/batman.jpg", result[0].coverUrl)
        assertEquals("COMIC", result[0].contentType)
        assertEquals("Superman", result[1].title)
        assertEquals("https://cdn.example.com/superman.jpg", result[1].coverUrl)
    }

    @Test
    fun `search uses searchPath and searchResultSelector`() = runTest {
        val result = source.search("batman", 1)
        assertEquals(2, result.size)
    }

    @Test
    fun `getMangaDetails extracts description and status`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)

        assertEquals("A dark knight fights crime.", details.description)
        assertEquals("Ongoing", details.status)
    }

    @Test
    fun `getChapterList parses chapter number from text`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)

        assertEquals(2, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("Issue #1", chapters[0].name)
    }

    @Test
    fun `getPageList extracts image URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/batman/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed empty body returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html><body></body></html>")
        }
        server.start()
        val emptySource = TestComicSource(base = server.url("").toString().trimEnd('/'), client = redirectingClient(server))

        val result = emptySource.getPopular(1)
        assertTrue(result.isEmpty())
    }
}

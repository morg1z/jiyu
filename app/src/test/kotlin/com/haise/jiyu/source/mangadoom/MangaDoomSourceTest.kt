package com.haise.jiyu.source.mangadoom

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

class MangaDoomSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaDoomSource

    private val popularHtml = """
        <html><body>
        <div class="manga-cover">
            <a href="https://manga-doom.com/test-series" title="Test Series">
                <img src="https://manga-doom.com/pictures/1/cover.jpg" alt="Test Series">
            </a>
        </div>
        </body></html>
    """.trimIndent()

    private val popularMangaHtml = """
        <html><body>
        <div class="manga-list-style">
            <a href="https://manga-doom.com/test-series" title="Test Series">
                <img src="https://manga-doom.com/pictures/1/cover.jpg" alt="Test Series">
            </a>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h5 class="widget-heading">Test Series</h5>
        <dl class="dl-horizontal">
            <dt>Status:</dt><dd>Ongoing</dd>
            <dt>Categories:</dt><dd><a href="https://manga-doom.com/category/action">Action</a>, <a href="https://manga-doom.com/category/comedy">Comedy</a></dd>
            <dt>Type :</dt><dd>Korean</dd>
            <dt>Author:</dt><dd><a href="/cast/jane">Jane Doe</a></dd>
            <dt>Artist:</dt><dd><a href="/cast/-">-</a></dd>
        </dl>
        <h5 class="widget-heading">Chapter list</h5>
        <ul class="chapter-list">
            <li><a href="https://manga-doom.com/test-series/2"><span class="val">Test Series - 2 </span></a></li>
            <li><a href="https://manga-doom.com/test-series/1"><span class="val">Test Series - 1 </span></a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val readerPage1Html = """
        <html><body>
        <img src="https://9giiu0g54k8c.redirectto.cc/s16/manga/1/chapters/1/1.jpg" class="img-responsive" id="chapter_img">
        <select><option value="https://manga-doom.com/test-series/1/1" selected="selected"></option><option value="https://manga-doom.com/test-series/1/2"></option></select>
        </body></html>
    """.trimIndent()

    private val readerPage2Html = """
        <html><body>
        <img src="https://9giiu0g54k8c.redirectto.cc/s16/manga/1/chapters/1/2.jpg" class="img-responsive" id="chapter_img">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" || path.startsWith("/?page=") -> MockResponse().setBody(popularHtml)
                    path.startsWith("/popular-manga") -> MockResponse().setBody(popularMangaHtml)
                    path == "/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/test-series/1/1" -> MockResponse().setBody(readerPage1Html)
                    path == "/test-series/1/2" -> MockResponse().setBody(readerPage2Html)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaDoomSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses manga-cover listing`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/test-series", result[0].url)
    }

    @Test
    fun `getMangaDetails infers MANHWA from Korean type field`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Ongoing", details.status)
        assertEquals("Jane Doe", details.author)
        assertEquals(null, details.artist)
        assertEquals(listOf("Action", "Comedy"), details.genres)
        assertEquals("MANHWA", details.contentType)
    }

    @Test
    fun `getChapterList parses chapter number from URL segment`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList discovers page count and getImageUrl resolves the real CDN URL`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s16/manga/1/chapters/1/1.jpg",
            source.getImageUrl(pages[0]),
        )
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s16/manga/1/chapters/1/2.jpg",
            source.getImageUrl(pages[1]),
        )
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = MangaDoomSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

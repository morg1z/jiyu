package com.haise.jiyu.source.comic

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ReadFreeComicsOnline pouziva na homepage/kategoriich netradicni "ultp" block
 * layout (h3.ultp-block-title), zatimco fulltextove hledani bezi pres standardni
 * WP sablonu (h2.entry-title) - proto vlastni getPopular mimo sdileny engine.
 */
class ReadFreeComicsOnlineSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ReadFreeComicsOnlineSource

    private val listHtml = """
        <html><body>
        <div class="ultp-block-item">
          <img src="https://cdn.example.com/cover.jpg" class="wp-post-image">
          <h3 class="ultp-block-title"><a href="/absolute-batman-issue-1-2024/">Absolute Batman Issue 1 (2024)</a></h3>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
        <meta property="og:description" content="Read Absolute Batman Issue 1 free online.">
        <meta property="og:image" content="https://cdn.example.com/page-1.webp">
        </head><body>
        <div class="entry-content">
          <img src="https://cdn.example.com/page-1.webp">
          <img src="https://cdn.example.com/page-2.webp">
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
                    path == "/" -> MockResponse().setBody(listHtml)
                    path == "/absolute-batman-issue-1-2024/" -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ReadFreeComicsOnlineSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, url and cover from ultp block layout`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Absolute Batman Issue 1 (2024)", result[0].title)
        assertEquals("/absolute-batman-issue-1-2024/", result[0].url)
        assertEquals("https://cdn.example.com/cover.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails reads description and cover from og meta tags`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Read Absolute Batman Issue 1 free online.", details.description)
        assertEquals("https://cdn.example.com/page-1.webp", details.coverUrl)
    }

    @Test
    fun `getChapterList returns a single synthetic Read chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Read", chapters[0].name)
    }

    @Test
    fun `getPageList extracts all issue page images from entry-content`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/page-1.webp", pages[0].url)
        assertEquals("https://cdn.example.com/page-2.webp", pages[1].url)
    }
}

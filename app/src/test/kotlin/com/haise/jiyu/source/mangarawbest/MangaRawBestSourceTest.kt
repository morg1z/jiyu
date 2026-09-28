package com.haise.jiyu.source.mangarawbest

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

class MangaRawBestSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaRawBestSource

    private val popularHtml = """
        <html><body>
        <a href="/raw/test-series"><div class="cover-frame"><img fetchpriority="high" src="https://cdn.example.com/cover.jpg" alt="Test Series" class="rounded-t-lg cover"></div></a>
        <div class="latest-chapter truncate"><a class="text-white font-semibold" href="/raw/test-series"> Test Series </a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Test Series</h1>
        <a href="/raw/test-series/di-2hua"> Chapter 2 </a>
        <a href="/raw/test-series/di-1hua"> Chapter 1 </a>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img class="chapter-image max-w-full my-0 mx-auto" loading="lazy" src="https://cdn.example.com/p1.jpg" data-original="https://cdn.example.com/p1.jpg" alt="Test Series Page 1">
        <img class="chapter-image max-w-full my-0 mx-auto" loading="lazy" src="https://cdn.example.com/p2.jpg" data-original="https://cdn.example.com/p2.jpg" alt="Test Series Page 2">
        <img class="lazy" src="https://blogger.googleusercontent.com/ad.jpg" alt="ad">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga-list") -> MockResponse().setBody(popularHtml)
                    path == "/raw/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/raw/test-series/di-1hua" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaRawBestSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses cover-frame cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/raw/test-series", result[0].url)
        assertEquals("https://cdn.example.com/cover.jpg", result[0].coverUrl)
    }

    @Test
    fun `search filters the catalog locally by title`() = runTest {
        assertEquals(1, source.search("test", 1).size)
        assertTrue(source.search("zzz", 1).isEmpty())
    }

    @Test
    fun `getChapterList extracts chapter number from di-Nhua href pattern`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList reads only chapter-image class, ignoring ad images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p1.jpg", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = MangaRawBestSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.todaymanga

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

class TodaymangaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: TodaymangaSource

    private val homeHtml = """
        <html><body>
        <a href="/book/test-series"><img loading="lazy" src="https://cdn.example.com/cover.jpg" alt="Test Series manga"></a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <a href="/author/jane-doe"><h2 itemprop="name">Jane Doe</h2></a>
        <a class="tag-item" href="/genre/action">Action</a>
        <a class="tag-item" href="/genre/comedy">Comedy</a>
        </body></html>
    """.trimIndent()

    private val chapterListHtml = """
        <html><body>
        <ul class="chapters-list">
            <li><h5 class="headline h5"><a href="/book/test-series/vol-1-ch-2">Vol.1 Chapter 2</a></h5></li>
            <li><h5 class="headline h5"><a href="/book/test-series/vol-1-ch-1">Vol.1 Chapter 1</a></h5></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val readerHtml = """
        <html><body>
        <img load="lazy" class="lazyload" data-src="https://i1.todaymanga.com/1/1/0.jpg" data-index="0">
        <img load="lazy" class="lazyload" data-src="https://i1.todaymanga.com/1/1/1.jpg" data-index="1">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search") -> MockResponse().setBody(homeHtml)
                    path == "/" -> MockResponse().setBody(homeHtml)
                    path.startsWith("/category/editor-pick") -> MockResponse().setBody(homeHtml)
                    path.startsWith("/category/recent") -> MockResponse().setBody(homeHtml)
                    path == "/book/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/book/test-series/chapter-list" -> MockResponse().setBody(chapterListHtml)
                    path == "/book/test-series/vol-1-ch-1" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = TodaymangaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular strips manga suffix from alt text`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/book/test-series", result[0].url)
    }

    @Test
    fun `search reuses card parsing`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
    }

    @Test
    fun `getMangaDetails parses author and tag-item genres`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action", "Comedy"), details.genres)
    }

    @Test
    fun `getChapterList reads chapter-list sub-page`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList reads lazyload data-src images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://i1.todaymanga.com/1/1/0.jpg", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = TodaymangaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

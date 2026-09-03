package com.haise.jiyu.source.mangaraw4u

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

class MangaRaw4uSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaRaw4uSource

    private val popularHtml = """
        <html><body>
        <a href="https://mangaraw4u.com/manga/test-series" class="result-card">
            <div class="result-card-image"><img src="https://cdn.example.com/cover.jpg"></div>
            <div class="result-card-title">Test Series</div>
        </a>
        </body></html>
    """.trimIndent()

    private val searchJson = """
        {"results":[{"id":"1","name":"Test Series","slug":"test-series","cover_full_url":"https://cdn.example.com/cover.jpg"}]}
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="detail-title">Test Series</h1>
        <span class="detail-stat-value detail-genres"><a href="/search?genre=drama">Drama</a><a href="/search?genre=fantasy">Fantasy</a></span>
        <div class="detail-chapter-row" data-chapter-number="2.20"><span class="detail-col-chapter"><a href="/manga/test-series/chapter-2-2">Ch. 2.20</a></span><span class="detail-col-updated">09/08/26</span></div>
        <div class="detail-chapter-row" data-chapter-number="1.00"><span class="detail-col-chapter"><a href="/manga/test-series/chapter-1">Ch. 1.00</a></span><span class="detail-col-updated">26/07/26</span></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="https://cdn.example.com/p1.jpg" alt="Test Series Chapter 1 - Page 1">
        <img src="data:image/svg+xml,x" data-src="https://cdn.example.com/p2.jpg" alt="Test Series Chapter 1 - Page 2">
        <img src="https://cdn.example.com/logo.png" alt="logo">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search?sort=") -> MockResponse().setBody(popularHtml)
                    path.startsWith("/api/search") -> MockResponse().setBody(searchJson)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaRaw4uSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses result-card listing`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/manga/test-series", result[0].url)
    }

    @Test
    fun `search parses JSON results array`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/manga/test-series", result[0].url)
    }

    @Test
    fun `getMangaDetails parses genres`() = runTest {
        val manga = source.search("test").first()
        val details = source.getMangaDetails(manga)
        assertEquals(listOf("Drama", "Fantasy"), details.genres)
    }

    @Test
    fun `getChapterList parses chapter number from data attribute`() = runTest {
        val manga = source.search("test").first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2.2f, chapters[0].chapterNumber)
        assertEquals(1.0f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList filters images by Page alt text, ignoring logo`() = runTest {
        val manga = source.search("test").first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p1.jpg", pages[0].url)
        assertEquals("https://cdn.example.com/p2.jpg", pages[1].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = MangaRaw4uSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
        assertTrue(emptySource.search("x").isEmpty())
    }
}

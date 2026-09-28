package com.haise.jiyu.source.hachiraw

import com.haise.jiyu.source.MangaFilter
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

class HachirawSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HachirawSource

    private val listHtml = """
        <html><body>
        <article class="post manga">
            <div class="featured-thumb wp-block-image">
                <a href="/manga-test-series/"><img src="lazy.png" data-src="https://cdn.example.com/cover.jpg" alt="Test Series"></a>
            </div>
            <header class="entry-header">
                <h3 class="entry-title"><a href="/manga-test-series/">Test Series</a></h3>
            </header>
        </article>
        </body></html>
    """.trimIndent()

    // Homepage nese kategorie v navigaci ("/category/{id}/").
    private val homeHtml = """
        <html><body>
        <nav>
            <a href="/category/6/">Action</a>
            <a href="/category/12/">Fantasy</a>
            <a href="/manga-test-series/">Some Manga</a>
        </nav>
        $listHtml
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="entry-title">Test Series</h1>
        <p>Author: Jane Doe</p>
        <p>Category: <a href="/category/6/" rel="category tag">Action</a>, <a href="/category/12/" rel="category tag">Fantasy</a>,</p>
        <table class="table table-hover"><tbody>
            <tr><td><p><a href="/chapter/1/2/">Chapter 2</a></p></td></tr>
            <tr><td><p><a href="/chapter/1/1/">Chapter 1</a></p></td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img decoding="async" class="aligncenter" src="/themes/hachiraw/img/lazy.png" data-src="https://cdn.example.com/p0.jpg" alt="img 0">
        <img decoding="async" class="aligncenter" src="/themes/hachiraw/img/lazy.png" data-src="https://cdn.example.com/p1.jpg" alt="img 1">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse().setBody(homeHtml)
                    path.startsWith("/?") -> MockResponse().setBody(listHtml)
                    path.startsWith("/category/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/page/") -> MockResponse().setBody(listHtml)
                    path == "/manga-test-series/" -> MockResponse().setBody(detailHtml)
                    path == "/chapter/1/1/" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HachirawSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses article cards with lazy-loaded cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/manga-test-series/", result[0].url)
        assertEquals("https://cdn.example.com/cover.jpg", result[0].coverUrl)
    }

    @Test
    fun `search reuses article card parsing`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `getMangaDetails parses author and category genres`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
    }

    @Test
    fun `getChapterList parses table-hover chapter rows`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList reads data-src lazy-loaded image URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p0.jpg", pages[0].url)
    }

    @Test
    fun `getAvailableTags reads numeric category links from the homepage nav`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertEquals("6", tags[0].id)
        assertEquals("Action", tags[0].label)
        assertEquals("12", tags[1].id)
    }

    @Test
    fun `getPopular with a selected genre reads the category archive`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("12")))
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = HachirawSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
        assertTrue(emptySource.search("x").isEmpty())
    }
}

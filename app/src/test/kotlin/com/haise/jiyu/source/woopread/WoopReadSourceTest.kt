package com.haise.jiyu.source.woopread

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

class WoopReadSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: WoopReadSource

    private val listHtml = """
        <html><body>
        <a class="block" href="/series/test-novel">
          <img alt="Test Novel" srcset="/_next/image?url=https%3A%2F%2Fimgcdn.example.com%2Fcover.jpg&amp;w=256&amp;q=75 256w"/>
        </a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head><meta name="description" content="A test summary."></head><body>
        <h1>Test Novel</h1>
        <div><span>Status:</span> <span>Ongoing</span></div>
        <div><span>Author:</span> <a href="#">Test Author</a></div>
        <div><span>Genres:</span><div class="flex flex-wrap gap-2"><a href="/browse?genres=drama"><div>Drama</div></a><a href="/browse?genres=fantasy"><div>Fantasy</div></a></div></div>
        <script>self.__next_f.push([1,"blah {\"title\":\"Chapter 1\",\"number\":1,\"slug\":\"chapter-1\",\"publishDate\":\"2026-05-01T00:00:00.000Z\"},{\"title\":\"Chapter 2\",\"number\":2,\"slug\":\"chapter-2\",\"publishDate\":\"2026-05-02T00:00:00.000Z\"} more"])</script>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body>
        <div id="chapter-abc123"><div data-paragraph-index="0"><p>First paragraph.</p></div><div data-paragraph-index="1"><p>Second paragraph.</p></div></div>
        </body></html>
    """.trimIndent()

    // "/api/genres" = prosta rada nazvu; "/api/novels?page=N" = 20/stranku,
    // zanrovy parametr server nebere, filtruje se lokalne.
    private val genresJson = """["Comedy","Drama","Fantasy"]"""

    private val novelsJson = """
        {"novels":[
          {"id":"1","title":"Test Novel","slug":"test-novel","cover":"https://imgcdn.example.com/cover.jpg","genres":["Comedy","Drama"]},
          {"id":"2","title":"Other Novel","slug":"other-novel","cover":"https://imgcdn.example.com/cover2.jpg","genres":["Fantasy"]}
        ],"totalCount":2}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/genres" -> MockResponse().setBody(genresJson)
                    path.startsWith("/api/novels") -> MockResponse().setBody(
                        if (path.endsWith("page=1")) novelsJson else """{"novels":[],"totalCount":2}"""
                    )
                    path.contains("/chapter-") -> MockResponse().setBody(chapterHtml)
                    path == "/series/test-novel" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/browse") || path.startsWith("/search") -> MockResponse().setBody(listHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = WoopReadSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses cards and decodes srcset cover url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
        assertEquals("https://imgcdn.example.com/cover.jpg", result[0].coverUrl)
        assertEquals("NOVEL", result[0].contentType)
    }

    @Test
    fun `search parses same card structure`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
    }

    @Test
    fun `getMangaDetails parses description, author, status and genres`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test summary.", details.description)
        assertEquals("Test Author", details.author)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Drama", "Fantasy"), details.genres)
        assertEquals("NOVEL", details.contentType)
    }

    @Test
    fun `getChapterList extracts escaped JSON chapter entries via regex`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertTrue(chapters.any { it.chapterNumber == 1f })
        assertTrue(chapters.any { it.chapterNumber == 2f })
        assertTrue(chapters.all { it.dateUpload > 0L })
    }

    @Test
    fun `getPageList joins paragraph text as a single novel text page`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters.first { it.chapterNumber == 1f })
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertEquals("First paragraph.\n\nSecond paragraph.", pages[0].url)
    }

    @Test
    fun `getAvailableTags reads the genres API`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("Comedy", "Drama", "Fantasy"), tags.map { it.id })
    }

    @Test
    fun `getPopular with a selected genre filters the novels API locally`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("Comedy")))
        assertEquals(listOf("Test Novel"), result.map { it.title })
        assertTrue(source.getPopular(2, MangaFilter(genres = listOf("Comedy"))).isEmpty())
    }

    @Test
    fun `search with a selected genre filters search results by catalog genres`() = runTest {
        val result = source.search("test", 1, MangaFilter(genres = listOf("Comedy")))
        assertEquals(listOf("Test Novel"), result.map { it.title })
        assertTrue(source.search("test", 1, MangaFilter(genres = listOf("Fantasy"))).isEmpty())
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = WoopReadSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.weebcentral

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

class WeebCentralSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: WeebCentralSource

    private val listHtml = """
        <html><body>
        <article class="bg-base-300 flex gap-4 p-4">
            <a href="https://weebcentral.com/series/ABC123/Test-Series"><img src="https://cdn.example.com/test.jpg" /></a>
            <a href="https://weebcentral.com/series/ABC123/Test-Series" class="link-hover">Test Series</a>
        </article>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head><meta property="og:image" content="https://cdn.example.com/test.jpg" /></head><body>
        <h1>Test Series</h1>
        <p class="whitespace-pre-wrap">A description.</p>
        <a href="https://weebcentral.com/search?included_tag=Action">Action</a>
        <a href="https://weebcentral.com/search?author=Some+Author">Some Author</a>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body>
        <a href="https://weebcentral.com/chapters/CH1"><span class="grow"><span>Chapter 1</span></span></a>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="https://cdn.example.com/test/1/01.jpg" alt="Page 1" />
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search/data") -> MockResponse().setBody(listHtml)
                    path == "/series/ABC123/Test-Series" -> MockResponse().setBody(detailHtml)
                    path == "/series/ABC123/full-chapter-list" -> MockResponse().setBody(chaptersHtml)
                    path.startsWith("/chapters/CH1/images") -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = WeebCentralSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses card title and cover`() = runTest {
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
        assertEquals("Some Author", details.author)

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
        val emptySource = WeebCentralSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.mangapill

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

class MangapillSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangapillSource

    private val listHtml = """
        <html><body>
        <div>
            <a href="/manga/1/test-series" class="relative block"><figure><img data-src="https://cdn.example.com/test.jpg"/></figure></a>
            <div class="flex flex-col justify-end">
                <a href="/manga/1/test-series" class="mb-2"><div class="mt-3 font-black">Test Series</div></a>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="font-bold text-lg">Test Series</h1>
        <p class="text-sm text--secondary">A summary.</p>
        <a href="/search?genre=Action">Action</a>
        <label class="text-secondary">Status</label>
        <div>publishing</div>
        <a href="/chapters/1-2/test-series-chapter-2">Chapter 2</a>
        <a href="/chapters/1-1/test-series-chapter-1">Chapter 1</a>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img class="js-page" data-src="https://cdn.example.com/p/1.jpg"/>
        <img class="js-page" data-src="https://cdn.example.com/p/2.jpg"/>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search?q=&type=manga") -> MockResponse().setBody(listHtml)
                    path == "/manga/1/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/chapters/1-2/test-series-chapter-2" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangapillSource(redirectingClient(server))
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
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Action"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p/1.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangapillSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

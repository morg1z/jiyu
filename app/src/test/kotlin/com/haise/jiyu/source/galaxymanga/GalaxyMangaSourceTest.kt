package com.haise.jiyu.source.galaxymanga

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

class GalaxyMangaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: GalaxyMangaSource

    private val listHtml = """
        <html><body>
        <div class="bsx">
          <a href="https://galaxymanga.io/manga/test-series/" title="Test Series">
            <img src="https://cdn.example.com/test.jpg" />
          </a>
          <div class="tt">Test Series</div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="entry-title">Test Series</h1>
        <div class="tsinfo"><div class="imptdt">Status <i>Ongoing</i></div></div>
        <span class="mgen"><a href="#">Action</a><a href="#">Romance</a></span>
        <div class="entry-content-single"><p>A test summary.</p></div>
        <div class="eplister"><ul>
          <li><a href="https://galaxymanga.io/test-series-chapter-1/"><span class="chapternum">Chapter 1</span><span class="chapterdate">May 1, 2026</span></a></li>
        </ul></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><script>ts_reader.run({"post_id":1,"sources":[{"source":"Server 1","images":["https:\/\/cdn.example.com\/1\/01.jpg","https:\/\/cdn.example.com\/1\/02.jpg"]}]});</script></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga/") && path != "/manga/test-series/" -> MockResponse().setBody(listHtml)
                    path.startsWith("/?s=") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series/" -> MockResponse().setBody(detailHtml)
                    path == "/test-series-chapter-1/" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = GalaxyMangaSource(redirectingClient(server))
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
    fun `getMangaDetails parses status, genres and description`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Action", "Romance"), details.genres)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList and getPageList parse eplister and embedded ts_reader JSON`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = GalaxyMangaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

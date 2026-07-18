package com.haise.jiyu.source.kingofshojo

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

class KingofshojoSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: KingofshojoSource

    private val listHtml = """
        <html><body><ul>
        <li>
            <div class="imgseries"><a class="series" href="/manga/test-series/" rel="12345"><img src="https://cdn.example.com/test.jpg"/></a></div>
            <div class="leftseries"><h2><a class="series" href="/manga/test-series/" rel="12345">Test Series</a></h2></div>
        </li>
        </ul></body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="entry-title">Test Series</h1>
        <div class="entry-content-single">A summary.</div>
        <div class="seriestugenre"><a>Action</a><a>Romance</a></div>
        <table><tr><td>Status</td><td>Ongoing</td></tr></table>
        </body></html>
    """.trimIndent()

    private val chaptersOptions = """
        <option data-id="2" value="https://kingofshojo.com/test-series-chapter-2/">Chapter 2</option>
        <option data-id="1" value="https://kingofshojo.com/test-series-chapter-1/">Chapter 1</option>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="https://cdn.kingofshojo.com/king-bucket/12345/2/1.webp"/>
        <img src="https://cdn.kingofshojo.com/king-bucket/12345/2/2.webp"/>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga/") && path.contains("order=update") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series/" -> MockResponse().setBody(detailHtml)
                    path == "/wp-admin/admin-ajax.php" -> MockResponse().setBody(chaptersOptions)
                    path == "/test-series-chapter-2/" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = KingofshojoSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, cover and encodes post id in url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/manga/test-series/::12345", result[0].url)
    }

    @Test
    fun `full flow parses details, chapters via ajax and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals(listOf("Action", "Romance"), details.genres)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.kingofshojo.com/king-bucket/12345/2/1.webp", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = KingofshojoSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

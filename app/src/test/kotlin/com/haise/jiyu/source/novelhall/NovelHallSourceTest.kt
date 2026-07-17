package com.haise.jiyu.source.novelhall

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

class NovelHallSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NovelHallSource

    private val listHtml = """
        <html><body>
        <table><tbody>
          <tr>
            <td class="w30">1</td>
            <td class="w70"><a href="/test-series-1/">Test Series</a></td>
            <td class="hidden-xs"><a class="writer" href="javascript:">Some Writer</a></td>
          </tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    private val searchHtml = """
        <html><body>
        <div class="search-item">
          <a title="Test Series" href="/test-series-1/"><img src="https://cdn.example.com/test.jpg"/></a>
          <div class="search-content"><h4 class="search-title"><a href="/test-series-1/">Test Series</a></h4></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="book-info">
          <h1>Test Series</h1>
          <img src="https://cdn.example.com/test.jpg" />
          <div class="total booktag">
            <a class="red" href="#">Urban</a>
            <span class="blue">Author：Jane Doe<p style="display: none;"><span>0</span></p></span>
            <span class="blue">Status：Active</span>
          </div>
          <div class="intro">
            <span class="js-open-wrap">Short preview...</span>
            <span class="js-close-wrap" style="display:none">A test summary.<span class="blue"> back&lt;&lt;</span></span>
          </div>
        </div>
        <div class="book-catalog"><ul>
          <li><a href="/test-series-1/1.html">Chapter 1: Beginning</a></li>
        </ul></div>
        </body></html>
    """.trimIndent()

    private val readerHtml = """
        <html><body><div class="entry-content" id="htmlContent">Once upon a time.<br><br>The end.</div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/lastupdate") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search-keyword-") -> MockResponse().setBody(searchHtml)
                    path == "/test-series-1/" -> MockResponse().setBody(detailHtml)
                    path == "/test-series-1/1.html" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NovelHallSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title from table row`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `search parses title and cover from search-title cards`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `getMangaDetails uses ownText to avoid hidden hit-counter leaking into author`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals("Active", details.status)
        assertEquals(listOf("Urban"), details.genres)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList parses chapter number from title`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getPageList converts br tags to newlines instead of ignoring them`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertEquals("Once upon a time.\n\nThe end.", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = NovelHallSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.ranobes

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

class RanobesSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: RanobesSource

    private val listHtml = """
        <html><body>
        <article class="block story shortstory mod-poster">
          <h2 class="title"><a href="https://ranobes.net/novels/12345-test-series.html">Test Series</a></h2>
          <div class="cont showcont"><div class="cont-in">
            <a href="https://ranobes.net/novels/12345-test-series.html" class="poster"><figure class="cover" style="background-image: url(https://cdn.example.com/test.jpg);"></figure></a>
          </div></div>
        </article>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="title">Test Series<span hidden> &bull; </span><span class="subtitle">测试</span></h1>
        <ul>
          <li title="Original status in">Status in COO: <span class="grey"><a href="#">Ongoing</a></span></li>
          <li>Authors: <span class="tag_list"><a href="#">Jane Doe</a></span></li>
        </ul>
        <div id="mc-fs-genre" class="mcollapse-cont"><div class="links"><a href="#">Action</a>, <a href="#">Fantasy</a></div></div>
        <div class="r-desription showcont"><div class="cont-in"><div class="cont-text">A test summary.</div></div></div>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body><script>
        window.__DATA__ = {"book_title":"Test Series","book_id":12345,"chapters":[{"id":"1","title":"Chapter 1: Beginning","date":"2026-07-17 13:14:06","comm_num":"2","showDate":"1 hour ago","link":"https://ranobes.net/test-series-12345/1.html"}],"pages_count":1,"count_all":1,"cstart":1,"limit":25,"search":"","default":[],"searchTimeout":null}
        </script></body></html>
    """.trimIndent()

    private val readerHtml = """
        <html><body><div class="text" id="arrticle"><p>Once upon a time.</p><p>The end.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/novels/" -> MockResponse().setBody(listHtml)
                    path.startsWith("/search/") -> MockResponse().setBody(listHtml)
                    path == "/novels/12345-test-series.html" -> MockResponse().setBody(detailHtml)
                    path == "/chapters/12345/" -> MockResponse().setBody(chaptersHtml)
                    path == "/test-series-12345/1.html" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = RanobesSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and background-image cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails strips subtitle from h1 and parses status, author, genres, description`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Ongoing", details.status)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList extracts book id from url and parses embedded window__DATA__ JSON with trailing keys`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("https://ranobes.net/test-series-12345/1.html", chapters[0].url)
    }

    @Test
    fun `getPageList extracts chapter text from arrticle id, marks as novel text`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("Once upon a time."))
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = RanobesSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

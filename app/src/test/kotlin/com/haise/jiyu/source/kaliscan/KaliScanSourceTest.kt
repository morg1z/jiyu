package com.haise.jiyu.source.kaliscan

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

class KaliScanSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: KaliScanSource

    private val listHtml = """
        <html><body>
        <div class="book-item">
          <div class="thumb"><a title="Painter of the Night" href="/manga/364-painter-of-the-night"><img class="lazy" src='/static/common/x.gif' data-src="https://cdn.example.com/thumb.jpg"/></a></div>
          <div class="title"><h3><a title="Painter of the Night" href="/manga/364-painter-of-the-night">Painter of the Night</a></h3></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="detail">
          <div class="name box"><h1>Painter of the Night</h1></div>
          <div class="meta box">
            <p><strong>Authors :</strong><a href="/authors/Byeonduck"><span>Byeonduck</span></a></p>
            <p><strong>Status :</strong><a href="/status/Ongoing"><span>Ongoing</span></a></p>
            <p><strong>Genres :</strong><a href="/genres/drama/">Drama ,</a><a href="/genres/romance/">Romance </a></p>
          </div>
        </div>
        <div class="summary"><p class="content">A test summary.</p></div>
        <ul class="chapter-list" id="chapter-list">
          <li id="c-1"><a href="/manga/364-painter-of-the-night/chapter-1" title="Chapter 1"><div><strong class="chapter-title">Chapter 1</strong><time class="chapter-update">3 days ago</time></div></a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><script>var chapImages = "https://cdn.example.com/1/01.jpg,https://cdn.example.com/1/02.jpg";</script></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/popular") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search") -> MockResponse().setBody(listHtml)
                    path == "/manga/364-painter-of-the-night" -> MockResponse().setBody(detailHtml)
                    path == "/manga/364-painter-of-the-night/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = KaliScanSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Painter of the Night", result[0].title)
        assertEquals("https://cdn.example.com/thumb.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails parses meta rows and summary`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test summary.", details.description)
        assertEquals("Byeonduck", details.author)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Drama", "Romance"), details.genres)
    }

    @Test
    fun `getChapterList and getPageList parse chapter-list and embedded JS image array`() = runTest {
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
        val emptySource = KaliScanSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.novelfire

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

class NovelFireSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NovelFireSource

    private val listHtml = """
        <html><body>
        <li class="novel-item">
          <div class="cover-wrap"><figure class="cover"><a href="/book/test-series"><img class="lazy" src="placeholder.gif" data-src="/server-1/test.jpg" /></a></figure></div>
          <div class="item-body">
            <div class="status-group"><span class="status"> Ongoing </span></div>
            <h2 class="title text2row"><a href="/book/test-series">Test Series</a></h2>
          </div>
        </li>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="main-head"><h1 class="novel-title">Test Series</h1><div class="author"><span>Author:</span> <a href="/author/jane"><span itemprop="author">Jane Doe</span></a></div></div>
        <div class="header-stats"><span><strong class="ongoing">Ongoing</strong></span></div>
        <div class="categories"><h4>Genres</h4><ul><li><a href="#" class="property-item">Action</a></li><li><a href="#" class="property-item">Drama</a></li></ul></div>
        <div class="summary"><h4>Summary</h4><div class="content"><p>A test summary.</p></div></div>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body>
        <ul class="chapter-list">
          <li><a href="/book/test-series/chapter-1" title="Chapter 1"><span class="chapter-no">1</span><strong class="chapter-title">Chapter 1 - Beginning</strong><time class="chapter-update" datetime="2022-06-02 14:30:56">4 years ago</time></a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private val readerHtml = """
        <html><body><div id="content" class="clearfix font_default"><p>Once upon a time.</p><p>The end.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/latest-release-novels") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search") -> MockResponse().setBody(listHtml)
                    path == "/book/test-series" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/book/test-series/chapters") -> MockResponse().setBody(chaptersHtml)
                    path == "/book/test-series/chapter-1" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NovelFireSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and lazy-loaded cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://novelfire.net/server-1/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails parses author, status, genres and description`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Action", "Drama"), details.genres)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList parses chapter-no, title and ISO datetime`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertTrue(chapters[0].dateUpload > 0)
    }

    @Test
    fun `getPageList extracts chapter text marked as novel text`() = runTest {
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
        val emptySource = NovelFireSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.wuxiabox

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

class WuxiaBoxSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: WuxiaBoxSource

    private val listHtml = """
        <html><body>
        <li class="novel-item">
          <a title="Test Series" href="/novel/test-series.html">
            <figure class="novel-cover">
              <img class="lazy" src="placeholder.jpg" data-src="/d/file/coverb/test.jpg" />
              <span class="status cpl">Completed</span>
            </figure>
            <h4 class="novel-title text2row">Test Series</h4>
          </a>
        </li>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="novel-info">
          <h1 itemprop="name" class="novel-title text2row">Test Series</h1>
          <div class="author"><span>Author:</span><span itemprop="author">Jane Doe</span></div>
        </div>
        <div class="header-stats">
          <span><strong>100</strong><small>Chapters</small></span>
          <span><strong class="">Completed</strong><small>Status</small></span>
        </div>
        <div class="categories"><strong>Categories</strong><ul>
          <li><a href='#' class="property-item">Action</a></li>
          <li><a href='#' class="property-item">Fantasy</a></li>
        </ul></div>
        <p class="description">A test summary.</p>
        </body></html>
    """.trimIndent()

    private val chaptersFragment0 = """
        <div id="chpagedlist">
        <ul class="chapter-list">
          <li data-chapterno="1" data-volumeno="0" data-orderno="1">
            <a href="/novel/test-series_1.html">
              <span class="chapter-no ">1</span>
              <strong class="chapter-title"> Chapter 1: Beginning</strong>
              <time class="chapter-update">2 days ago</time>
            </a>
          </li>
        </ul>
        </div>
    """.trimIndent()

    private val readerHtml = """
        <html><body><div class="chapter-content"><p>Once upon a time.</p><p>The end.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/updates/") -> MockResponse().setBody(listHtml)
                    path == "/novel/test-series.html" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/e/extend/fy.php?page=0") -> MockResponse().setBody(chaptersFragment0)
                    path.startsWith("/e/extend/fy.php") -> MockResponse().setBody("<div id=\"chpagedlist\"><ul class=\"chapter-list\"></ul></div>")
                    path == "/novel/test-series_1.html" -> MockResponse().setBody(readerHtml)
                    path == "/e/search/index.php" -> MockResponse().setBody(listHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = WuxiaBoxSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and lazy cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://www.wuxiabox.com/d/file/coverb/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `search POSTs to EmpireCMS endpoint and parses results`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `getMangaDetails finds Status by matching sibling small label, not first strong`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Completed", details.status)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList paginates through fy_php fragments until empty`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
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
        val emptySource = WuxiaBoxSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

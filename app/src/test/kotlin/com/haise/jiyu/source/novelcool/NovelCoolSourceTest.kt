package com.haise.jiyu.source.novelcool

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

class NovelCoolSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NovelCoolSource

    private val listHtml = """
        <html><body>
        <div class="book-item">
          <div class="book-pic"><a href="https://www.novelcool.com/novel/Test-Series.html"><img src="https://cdn.example.com/test.jpg"/></a></div>
          <div class="book-info">
            <a href="https://www.novelcool.com/novel/Test-Series.html">
              <div class="book-name single-line-ellipsis">Test Series</div>
            </a>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="bookinfo-title" itemprop="name">Test Series</h1>
        <div class="bookinfo-author"><a href="#"><span itemprop="creator">Jane Doe</span></a></div>
        <div class="bk-summary-txt">A test summary.</div>
        <div class="bookinfo-category-list">
          <div class="bk-cate-item bk-cate-type1 bk-going"><a href="#">Ongoing</a></div>
          <div class="bk-cate-item"><a href="#" title="Action">Action</a></div>
          <div class="bk-cate-item"><a href="#" title="Fantasy">Fantasy</a></div>
        </div>
        <div class="bk-status"><div class="bk-status-item"><a href="#">Ongoing</a></div></div>
        <div class="chp-item"><a href="https://www.novelcool.com/chapter/test-series/1/" title="Ch.1">
          <div class="chapter-item"><div class="chapter-item-title"><span class="chapter-item-headtitle">Ch.1</span></div><span class="chapter-item-time">Nov 13, 2025</span></div>
        </a></div>
        </body></html>
    """.trimIndent()

    private val readerHtml = """
        <html><body>
        <h2 class="chapter-title para-h6 out-bottom-small">Test Series</h2>
        <p class="chapter-start-mark"></p>
        <p>Once upon a time.</p>
        <p>The end.</p>
        <p class="chapter-end-mark para-h9">Chapter end</p>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/category/popular.html") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search/") -> MockResponse().setBody(listHtml)
                    path == "/novel/Test-Series.html" -> MockResponse().setBody(detailHtml)
                    path == "/chapter/test-series/1/" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NovelCoolSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, page 2 returns empty since no pagination exists`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertTrue(source.getPopular(2).isEmpty())
    }

    @Test
    fun `getMangaDetails excludes the status pill from genres list`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals("A test summary.", details.description)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
        assertEquals("Ongoing", details.status)
    }

    @Test
    fun `getChapterList parses chapter title and date`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getPageList extracts paragraphs between start and end marks, ignoring document-write wrapper`() = runTest {
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
        val emptySource = NovelCoolSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.lightnovelworld

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

class LightNovelWorldSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LightNovelWorldSource

    private val listHtml = """
        <html><body>
        <div class="recommendation-card">
            <a href="/novel/test-series/" class="card-cover-link">
                <img src="/media/covers/test.jpg" class="skel-img" />
            </a>
            <div class="card-content"><h3 class="card-title">Test Series</h3></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="novel-title">Test Series</h1>
        <p class="novel-author">Author: <a href="/author/jane/" class="author-link">Jane Doe</a></p>
        <span class="status-badge ongoing">Ongoing</span>
        <div class="genre-tags"><span class="genre-tag action">ACTION</span><span class="genre-tag drama">DRAMA</span></div>
        <div class="summary-content"><p>A test summary.</p></div>
        </body></html>
    """.trimIndent()

    private val chaptersPage1Html = """
        <html><body>
        <div class="chapter-card" onclick="location.href='/novel/test-series/chapter/1/'">
            <div class="chapter-number">1</div>
            <div class="chapter-info"><h3 class="chapter-title">Chapter 1 - Beginning</h3><p class="chapter-time">2 days ago</p></div>
        </div>
        <a class="page-link" title="Next Page">&rsaquo;</a>
        </body></html>
    """.trimIndent()

    private val chaptersPage2Html = """
        <html><body>
        <div class="chapter-card" onclick="location.href='/novel/test-series/chapter/2/'">
            <div class="chapter-number">2</div>
            <div class="chapter-info"><h3 class="chapter-title">Chapter 2 - Middle</h3><p class="chapter-time">1 day ago</p></div>
        </div>
        </body></html>
    """.trimIndent()

    private val readerHtml = """
        <html><body>
        <div class="chapter-text protected-content" id="chapterText">
            <div class="chapter-ad-container"><script>evil()</script></div>
            <p>Once upon a time.</p><p>The end.</p>
        </div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/genre-all/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search/") -> MockResponse().setBody(listHtml)
                    path == "/novel/test-series/" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/novel/test-series/chapters/?page=1") -> MockResponse().setBody(chaptersPage1Html)
                    path.startsWith("/novel/test-series/chapters/?page=2") -> MockResponse().setBody(chaptersPage2Html)
                    path == "/novel/test-series/chapter/1/" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = LightNovelWorldSource(redirectingClient(server))
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
        assertTrue(result[0].coverUrl!!.startsWith("https://lightnovelworld.org"))
    }

    @Test
    fun `getMangaDetails parses author, status, genres and description`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("ACTION", "DRAMA"), details.genres)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList follows pagination via onclick-encoded links`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("/novel/test-series/chapter/2/", chapters[1].url)
    }

    @Test
    fun `getPageList extracts chapter text and strips ad scripts, marks as novel text`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("Once upon a time."))
        assertTrue(!pages[0].url.contains("evil()"))
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = LightNovelWorldSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

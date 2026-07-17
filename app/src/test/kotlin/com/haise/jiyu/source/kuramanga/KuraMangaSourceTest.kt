package com.haise.jiyu.source.kuramanga

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

class KuraMangaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: KuraMangaSource

    private val listJson = """
        {"data":[{"id":1,"title":"Test Series","summary":"desc","status":"ongoing","genres":["Drama"],"normalized_title":"testseries","latestChapter":5,"thumb":"https://cdn.example.com/test.jpg"}]}
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="manga-title">Test Series</h1>
        <div class="summary-box"><div id="summary-text" class="summary-text"><div class="summary-inner">A test summary.</div></div></div>
        <div class="genre-row"><div class="genre-list"><a href="#" class="genre-chip">Action</a><a href="#" class="genre-chip">Romance</a></div></div>
        <div><strong>Status:</strong> Ongoing</div>
        <div><strong>Author:</strong> Jane Doe</div>
        <div class="chapter-list" id="chapterList">
          <div class="chapter-item"><a href="/testseries/chapter-1">Chapter 1</a><time>Apr 6, 2026</time></div>
        </div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div class="reader-width">
          <img src="https://cdn.example.com/testseries/chapters/0001/0001.webp" />
          <img src="https://cdn.example.com/testseries/chapters/0001/0002.webp" />
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
                    path.startsWith("/search") -> MockResponse().setBody(listJson)
                    path == "/testseries" -> MockResponse().setBody(detailHtml)
                    path == "/testseries/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = KuraMangaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses JSON data array`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/testseries", result[0].url)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails parses status and author from strong-tag rows without leaking page text`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Ongoing", details.status)
        assertEquals("Jane Doe", details.author)
        assertEquals("A test summary.", details.description)
        assertEquals(listOf("Action", "Romance"), details.genres)
    }

    @Test
    fun `getChapterList and getPageList parse server-rendered chapter list and reader images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/testseries/chapters/0001/0001.webp", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = KuraMangaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

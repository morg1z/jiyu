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
        <div class="novel-item"><a class="cover-image" href="/novel/test-novel"><img data-src="https://cdn.example.com/test.jpg" /></a><span class="novel-title">Test Novel</span></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="novel-title">Test Novel</h1>
        <div class="cover"><img data-src="https://cdn.example.com/test.jpg" /></div>
        <div class="summary"><div class="content">A summary.</div></div>
        <div class="categories"><a>Fantasy</a></div>
        <ul class="chapter-list"><li><a href="/novel/test-novel/chapter-1"><span class="chapter-title">Chapter 1</span></a></li></ul>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div id="chapter-container">This is the chapter text.</div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/browse/sort-popular") -> MockResponse().setBody(listHtml)
                    path == "/novel/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/novel/test-novel/chapter-1" -> MockResponse().setBody(pagesHtml)
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
    fun `getPopular parses title and cover, contentType is NOVEL`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
        assertEquals("NOVEL", result[0].contentType)
    }

    @Test
    fun `full flow parses details, chapters and chapter text`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].name)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
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

package com.haise.jiyu.source.lightnovelpub

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

class LightNovelPubSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LightNovelPubSource

    private val listHtml = """
        <html><body>
        <div class="novel-item"><a class="novel-cover" href="/novel/test-novel"><img data-src="/covers/test.jpg" /></a><div class="novel-title"><a href="/novel/test-novel">Test Novel</a></div></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="novel-title">Test Novel</h1>
        <div class="novel-cover"><img src="https://cdn.example.com/test.jpg" /></div>
        <div class="summary"><div class="content">A summary.</div></div>
        <div class="categories"><div class="content"><a>Fantasy</a></div></div>
        </body></html>
    """.trimIndent()

    private val chapterListHtml = """
        <html><body>
        <ul class="chapter-list"><li><a href="/novel/test-novel/chapter-1">Chapter 1</a></li></ul>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div id="chapter-container"><div class="content">This is the chapter text.</div></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/browse/genre/all-genres") -> MockResponse().setBody(listHtml)
                    path == "/novel/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/novel/test-novel/chapters/page-1/" -> MockResponse().setBody(chapterListHtml)
                    path == "/novel/test-novel/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = LightNovelPubSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and relative cover resolved against base`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
        assertTrue(result[0].coverUrl!!.endsWith("/covers/test.jpg"))
    }

    @Test
    fun `full flow parses details, paginated chapter list and chapter text`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("This is the chapter text.", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = LightNovelPubSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

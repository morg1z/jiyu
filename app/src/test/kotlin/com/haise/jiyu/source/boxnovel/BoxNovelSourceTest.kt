package com.haise.jiyu.source.boxnovel

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

class BoxNovelSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: BoxNovelSource

    private val listHtml = """
        <html><body>
        <div class="page-listing-item"><h3><a href="https://boxnovel.com/novel/test-novel">Test Novel</a></h3><img data-src="https://cdn.example.com/test.jpg" /></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="post-title"><h1>Test Novel</h1></div>
        <div class="summary_image"><img data-src="https://cdn.example.com/test.jpg" /></div>
        <div class="summary__content"><p>A novel summary.</p></div>
        <div class="genres-content"><a>Fantasy</a></div>
        <div class="wp-manga-chapter"><a href="https://boxnovel.com/novel/test-novel/chapter-1">Chapter 1</a></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div class="reading-content"><div class="text-left">This is the chapter text content.</div></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/novel/?m_orderby=") -> MockResponse().setBody(listHtml)
                    path == "/novel/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/novel/test-novel/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = BoxNovelSource(redirectingClient(server))
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
        assertEquals("NOVEL", source.contentType)
    }

    @Test
    fun `full flow parses details, chapters, and chapter text as a single page`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A novel summary.", details.description)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertEquals("This is the chapter text content.", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = BoxNovelSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

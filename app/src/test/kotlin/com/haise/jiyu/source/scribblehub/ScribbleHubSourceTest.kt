package com.haise.jiyu.source.scribblehub

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

class ScribbleHubSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ScribbleHubSource

    private val listHtml = """
        <html><body>
        <div class="search_main_box"><div class="search_title"><a href="/series/12345/test-novel">Test Novel</a></div><div class="search_img"><img src="https://cdn.example.com/test.jpg" /></div></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="fic_title">Test Novel</div>
        <div class="novel-cover"><img src="https://cdn.example.com/test.jpg" /></div>
        <div class="wi_fic_desc">A summary.</div>
        <div class="wi_fic_genre"><a>Fantasy</a></div>
        </body></html>
    """.trimIndent()

    private val chapterAjaxHtml = """
        <html><body><li class="toc_w"><a href="/series/12345/test-novel/chapter-1">Chapter 1</a></li></body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div class="chapter-inner"><div class="chp-raw">This is the chapter text.</div></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/series-ranking/") -> MockResponse().setBody(listHtml)
                    path == "/series/12345/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/wp-admin/admin-ajax.php" -> MockResponse().setBody(chapterAjaxHtml)
                    path == "/series/12345/test-novel/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ScribbleHubSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
    }

    @Test
    fun `getMangaDetails, chapter AJAX call and chapter text all parse`() = runTest {
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
    fun `chapter list without series-id in URL returns empty, not an exception`() = runTest {
        val manga = source.getPopular(1).first().copy(url = "/no-id-here")
        assertTrue(source.getChapterList(manga).isEmpty())
    }
}

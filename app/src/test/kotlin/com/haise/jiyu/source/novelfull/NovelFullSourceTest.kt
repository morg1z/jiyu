package com.haise.jiyu.source.novelfull

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

/**
 * NovelFullSource neodstranuje base prefix z href (na rozdil od BoxNovel) -
 * ukladá url = link.attr("href") primo, pak v getMangaDetails/getChapterList
 * pouziva "$base${manga.url}" - fixture proto pouziva relativni hrefy.
 */
class NovelFullSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NovelFullSource

    private val listHtml = """
        <html><body>
        <div class="list-truyen"><div class="row"><h3 class="truyen-title"><a href="/test-novel">Test Novel</a></h3><img class="cover" src="https://cdn.example.com/test.jpg" /></div></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h3 class="title">Test Novel</h3>
        <div class="book"><img src="https://cdn.example.com/test.jpg" /></div>
        <div class="desc-text">A novel summary.</div>
        <div class="info"><a href="/genre/fantasy">Fantasy</a></div>
        </body></html>
    """.trimIndent()

    private val chapterListHtml = """
        <html><body>
        <div id="list-chapter"><div class="row"><li><a href="/test-novel/chapter-1">Chapter 1</a></li></div></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div id="chapter-content">This is the chapter text.</div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/most-popular") -> MockResponse().setBody(listHtml)
                    path == "/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/test-novel?page=1" -> MockResponse().setBody(chapterListHtml)
                    path == "/test-novel/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NovelFullSource(redirectingClient(server))
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
        assertEquals("/test-novel", result[0].url)
        assertEquals("NOVEL", result[0].contentType)
    }

    @Test
    fun `getChapterList paginates until an empty page, getPageList extracts chapter text`() = runTest {
        val manga = source.getPopular(1).first()
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
        val emptySource = NovelFullSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

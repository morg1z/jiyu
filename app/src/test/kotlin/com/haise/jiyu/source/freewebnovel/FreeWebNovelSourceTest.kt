package com.haise.jiyu.source.freewebnovel

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

class FreeWebNovelSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: FreeWebNovelSource

    private val listHtml = """
        <html><body>
        <div class="li"><div class="con"><div class="pic"><a href="/novel/test-novel"><img src="/files/article/image/0/1/1s.jpg"></a></div>
        <div class="txt"><h3 class="tit"><a href="/novel/test-novel" title="Test Novel">Test Novel</a></h3></div></div></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
        <meta property="og:description" content="A test light novel.">
        </head><body>
        <ul id="idData"><li><a href="/novel/test-novel/chapter-1" class="con">Chapter 1: Beginning</a></li></ul>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div id="article"><p>This is the chapter text.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/sort/most-popular") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search") -> MockResponse().setBody(listHtml)
                    path == "/novel/test-novel" -> MockResponse().setBody(detailHtml)
                    path == "/novel/test-novel/chapter-1" -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = FreeWebNovelSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, url and cover, contentType is NOVEL`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Novel", result[0].title)
        assertEquals("/novel/test-novel", result[0].url)
        assertEquals("NOVEL", result[0].contentType)
    }

    @Test
    fun `full flow parses details, chapters and chapter text`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test light novel.", details.description)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1: Beginning", chapters[0].name)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
    }

    @Test
    fun `search returns matching results`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = FreeWebNovelSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.dynasty

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

class DynastySourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: DynastySource

    private val seriesJson = """
        {"tags": [ {"#": [ {"permalink": "yuru-yuri", "name": "Yuru Yuri"} ]} ], "current_page": 1, "total_pages": 1}
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h2 class="tag-title"><b>Yuru Yuri</b></h2>
        <div class="thumbnail"><img src="/system/series/1/cover.jpg" /></div>
        <div class="description">Cute girls doing cute things.</div>
        <div class="tags"><a href="/tags/comedy">comedy</a></div>
        </body></html>
    """.trimIndent()

    private val chapterListHtml = """
        <html><body>
        <dl class="chapter-list">
          <dd><a href="/chapters/yuru-yuri-ch1">Chapter 1</a></dd>
          <dd><a href="/chapters/yuru-yuri-ch2">Chapter 2</a></dd>
        </dl>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><script>
        var pages = [{"image": "//cdn.example.com/yuru-yuri/1/01.jpg"}, {"image": "//cdn.example.com/yuru-yuri/1/02.jpg"}];
        </script></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/series.json") -> MockResponse().setBody(seriesJson)
                    path == "/series/yuru-yuri" -> MockResponse().setBody(detailHtml.plus(chapterListHtml))
                    path == "/chapters/yuru-yuri-ch1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = DynastySource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses series slug and name from paginated series-json`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Yuru Yuri", result[0].title)
        assertEquals("/series/yuru-yuri", result[0].url)
    }

    @Test
    fun `getMangaDetails and getChapterList parse detail page HTML`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Cute girls doing cute things.", details.description)
        assertEquals(listOf("comedy"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
    }

    @Test
    fun `getPageList extracts image URLs from embedded JS array`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val target = chapters.first { it.url.endsWith("ch1") }
        val pages = source.getPageList(target)

        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/yuru-yuri/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed series-json returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val failingSource = DynastySource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

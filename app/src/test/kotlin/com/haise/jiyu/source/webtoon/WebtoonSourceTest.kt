package com.haise.jiyu.source.webtoon

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

class WebtoonSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: WebtoonSource

    private val listHtml = """
        <html><body>
        <ul class="card_lst"><li>
          <a href="https://www.webtoons.com/en/action/test-toon/list?title_no=1"><p class="subj">Test Toon</p><img data-url="https://cdn.example.com/test.jpg" /></a>
        </li></ul>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="detail_header"><p class="subj">Test Toon</p></div>
        <div class="summary">A toon summary.</div>
        <p class="author">Some Author</p>
        <a class="genre">Fantasy</a>
        </body></html>
    """.trimIndent()

    private val chapterListHtml = """
        <html><body>
        <ul id="_listUl"><li data-episode-no="1"><a href="/en/action/test-toon/ep1/viewer?title_no=1&episode_no=1"><span class="subj">Episode 1</span></a></li></ul>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div id="_imageList"><img data-url="https://cdn.example.com/test/1/01.jpg" /></div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/en/originals") -> MockResponse().setBody(listHtml)
                    path.startsWith("/en/action/test-toon/list") -> MockResponse().setBody(detailHtml.plus(chapterListHtml))
                    path.startsWith("/en/action/test-toon/ep1/viewer") -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = WebtoonSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses subj title and data-url cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Toon", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
        assertEquals("MANHWA", source.contentType)
    }

    @Test
    fun `full flow parses details, episodes and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A toon summary.", details.description)
        assertEquals("Some Author", details.author)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/test/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = WebtoonSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

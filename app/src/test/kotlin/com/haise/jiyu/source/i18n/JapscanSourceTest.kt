package com.haise.jiyu.source.i18n

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

class JapscanSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: JapscanSource

    private val listHtml = """
        <html><body>
        <div class="d-flex flex-column"><a class="text-dark" href="/manga/test-series/"><img src="https://cdn.example.com/test.jpg" />Test Series</a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Test Series</h1>
        <div class="d-flex"><img src="https://cdn.example.com/test.jpg" /></div>
        <p class="m-0">A description.</p>
        <a href="/tags/action">Action</a>
        <div id="chapters_list"><div class="chapters_list"><a href="/manga/test-series/1">Chapitre 1</a></div></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div id="images"><img data-src="https://cdn.example.com/test/1/01.jpg" /></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/mangas/") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series/" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = JapscanSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses french manga list, language tag is fr`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("fr", source.language)
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A description.", details.description)
        assertEquals(listOf("Action"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = JapscanSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

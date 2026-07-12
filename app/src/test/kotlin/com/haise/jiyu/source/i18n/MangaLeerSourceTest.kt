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

class MangaLeerSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaLeerSource

    private val listHtml = """
        <html><body>
        <div class="page-item-detail"><h3><a href="https://mangaleer.com/manga/test-series">Test Series</a></h3><img data-src="https://cdn.example.com/test.jpg" /></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="post-title"><h1>Test Series</h1></div>
        <div class="summary_image"><img data-src="https://cdn.example.com/test.jpg" /></div>
        <div class="summary__content"><p>Una sinopsis.</p></div>
        <div class="genres-content"><a>Acción</a></div>
        <div class="wp-manga-chapter"><a href="https://mangaleer.com/manga/test-series/chapter-1">Capítulo 1</a></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div class="reading-content"><img data-src="https://cdn.example.com/test/1/01.jpg" /></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga/?page=") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaLeerSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, language tag is es`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("es", source.language)
    }

    @Test
    fun `full flow parses details, chapters and pages via absolute URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Una sinopsis.", details.description)

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
        val emptySource = MangaLeerSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

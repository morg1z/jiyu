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

class UnionMangasSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: UnionMangasSource

    private val listHtml = """
        <html><body>
        <div class="div-manga"><a href="https://unionmangas.xyz/manga/test-series"><h3 class="manga-title">Test Series</h3><img data-src="https://cdn.example.com/test.jpg" /></a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Test Series</h1>
        <div class="img-manga"><img src="https://cdn.example.com/test.jpg" /></div>
        <div class="sinopse">Uma sinopse.</div>
        <div class="genres"><a>Ação</a></div>
        <div class="list-capitulos"><a href="https://unionmangas.xyz/manga/test-series/chapter-1">Capítulo 1</a></div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body><div class="chapter-images"><img data-src="https://cdn.example.com/test/1/01.jpg" /></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/lista-mangas?page=") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = UnionMangasSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, language tag is pt`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("pt", source.language)
    }

    @Test
    fun `full flow parses details, chapters and pages via absolute URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Uma sinopse.", details.description)

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
        val emptySource = UnionMangasSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

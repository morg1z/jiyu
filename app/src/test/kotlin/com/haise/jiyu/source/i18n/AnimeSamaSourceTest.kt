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

/**
 * AnimeSamaSource (na rozdil od Japscan) pouziva manga.url/chapter.url PRIMO
 * (bez base prefixu) v getMangaDetails/getChapterList/getPageList - proto
 * fixture pouziva absolutni URL v hrefech, presne jak to ocekava produkcni kod.
 */
class AnimeSamaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: AnimeSamaSource

    private val listHtml = """
        <html><body>
        <div class="cardListAnime"><a href="https://anime-sama.fr/catalogue/test-series"><h1>Test Series</h1><img src="https://cdn.example.com/test.jpg" /></a></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="titre">Test Series</h1>
        <img class="cover" src="https://cdn.example.com/test.jpg" />
        <p class="synopsis">A synopsis.</p>
        <a class="tag">Action</a>
        <div class="chapitreList"><a href="https://anime-sama.fr/catalogue/test-series/chapter-1">Chapitre 1</a></div>
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
                    path.startsWith("/catalogue/?page=") -> MockResponse().setBody(listHtml)
                    path == "/catalogue/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/catalogue/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = AnimeSamaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover, language tag is fr`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("fr", source.language)
    }

    @Test
    fun `full flow parses details, chapters and pages via absolute URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A synopsis.", details.description)
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
        val emptySource = AnimeSamaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

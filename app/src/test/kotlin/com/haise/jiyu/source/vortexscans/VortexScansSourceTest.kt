package com.haise.jiyu.source.vortexscans

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
 * Artist se na detailni strance vytahuje regexem z hydration-props (viz
 * komentar v VortexScansSource.kt), proto detail fixture obsahuje jeho
 * HTML-entity-escapovanou podobu presne tak, jak ji vraci realny web.
 * Kapitoly/stranky kopiruji aktualni server-renderovany markup webu.
 */
class VortexScansSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: VortexScansSource

    private val listHtml = """
        <html><body>
        <a href="/series/test-series" title="Test Series"><img src="https://cdn.example.com/test.jpg" /></a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 itemprop="name">Test Series</h1>
        <img itemprop="image" src="https://cdn.example.com/test.jpg" />
        <p itemprop="description">A description.</p>
        <span itemprop="genre">Action</span>
        <script>{&quot;postId&quot;:[0,999],&quot;artist&quot;:[0,&quot;Some Artist&quot;]}</script>
        </body></html>
    """.trimIndent()

    // Kapitoly jsou server-renderovane na ?tab=chapters jako odkazy
    // "/series/{slug}/chapter-{n}" (realny web, postId/API se uz nepouziva).
    private val chaptersHtml = """
        <html><body>
        <a href="/series/test-series/chapter-1">Chapter 1</a>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="https://storage.vortexscans.org//upload/series/test-series/abc123/01.webp" />
        <img src="https://storage.vortexscans.org//upload/series/featured/9/cover.png" />
        <img src="https://storage.vortexscans.org//upload/series/test-series/abc123/02.webp" />
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/series/?page=") -> MockResponse().setBody(listHtml)
                    path == "/series/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/series/test-series?tab=chapters" -> MockResponse().setBody(chaptersHtml)
                    path == "/series/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = VortexScansSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses card title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `getPopular requests the listing WITH a trailing slash, to avoid the site's http-downgrade redirect`() = runTest {
        // Overeno na realnem telefonu: "/series?page=1" (bez lomitka) dostane 301 na
        // "http://..." (ne https) a Android takove pripojeni spravne odmitne jako
        // cleartext - appka pak tise skoncila na prazdnem seznamu. S lomitkem web
        // odpovi rovnou 200, zadne presmerovani. Stejna pricina jako u HiveToonsSource
        // (oba bezi na Astro).
        source.getPopular(1)
        assertEquals("/series/?page=1", server.takeRequest().path)
    }

    @Test
    fun `search filters getPopular results by title`() = runTest {
        assertEquals(1, source.search("Test", 1).size)
        assertTrue(source.search("Nonexistent", 1).isEmpty())
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A description.", details.description)
        assertEquals(listOf("Action"), details.genres)
        assertEquals("Some Artist", details.author)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("/series/test-series/chapter-1", chapters[0].url)

        val pages = source.getPageList(chapters[0])
        // jen 01/02.webp - featured cover se filtruje
        assertEquals(2, pages.size)
        assertEquals("https://storage.vortexscans.org//upload/series/test-series/abc123/01.webp", pages[0].url)
        assertEquals("https://storage.vortexscans.org//upload/series/test-series/abc123/02.webp", pages[1].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = VortexScansSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

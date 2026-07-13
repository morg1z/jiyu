package com.haise.jiyu.source.mangapark

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
 * Puvodni GraphQL API (mangapark.net/apo/) je mrtve (cela domenova rodina
 * presmerovava na malvertising stenu). mangapark.page misto toho pouziva
 * server-rendered HTML (listing + detail) a dva JSON endpointy
 * (/api/search, /get-chapter-list) - viz komentar v MangaParkSource.kt.
 */
class MangaParkSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaParkSource

    private val listHtml = """
        <html><body>
        <div class="comic-item">
            <a href="/series/tower-of-god.ABC123"><img class="series-card-img" alt="Cover of Tower of God" data-src="https://cdn.example.com/tog.jpg" /></a>
            <h1>Tower of God</h1>
        </div>
        </body></html>
    """.trimIndent()

    private val searchJson = """
        { "comics": [ {"title": "Tower of God", "slug": "tower-of-god", "slug_hash": "tower-of-god.ABC123", "image": "https://cdn.example.com/tog.jpg"} ] }
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 itemprop="name">Tower of God</h1>
        <div itemprop="image"><img data-src="https://cdn.example.com/tog.jpg" /></div>
        <div itemprop="description">Climb the tower.</div>
        <a itemprop="genre" href="/genre/action">Action</a>
        </body></html>
    """.trimIndent()

    private val chaptersJson = """
        { "success": true, "data": [ {"chapter_num": 1, "chapter_name": "Chapter 1", "chapter_slug": "chapter-1"} ] }
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img data-number="1" src="https://cdn.example.com/tog/1/01.jpg" />
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/series?page=") -> MockResponse().setBody(listHtml)
                    path.startsWith("/api/search") -> MockResponse().setBody(searchJson)
                    path.startsWith("/get-chapter-list") -> MockResponse().setBody(chaptersJson)
                    path == "/series/tower-of-god.ABC123" -> MockResponse().setBody(detailHtml)
                    path == "/series/tower-of-god.ABC123/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaParkSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses comic-item cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Tower of God", result[0].title)
        assertEquals("https://cdn.example.com/tog.jpg", result[0].coverUrl)
    }

    @Test
    fun `search parses api search json`() = runTest {
        val result = source.search("tower", 1)
        assertEquals(1, result.size)
        assertEquals("/series/tower-of-god.ABC123", result[0].url)
    }

    @Test
    fun `getMangaDetails, getChapterList and getPageList parse real site data`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Climb the tower.", details.description)
        assertEquals(listOf("Action"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("/series/tower-of-god.ABC123/chapter-1", chapters[0].url)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/tog/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val failingSource = MangaParkSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

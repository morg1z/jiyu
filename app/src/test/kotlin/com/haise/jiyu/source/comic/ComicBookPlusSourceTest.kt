package com.haise.jiyu.source.comic

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
 * ComicBookPlus ma vlastni netypickou strukturu (dlid= v query, stranky jako
 * sekvence 0.jpg..N-1.jpg vedle sebe ve stejnem adresari jako thumbnailUrl) -
 * proto vlastni parsovani mimo sdileny ComicSiteSource.
 */
class ComicBookPlusSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ComicBookPlusSource

    private val listHtml = """
        <html><body>
        <div class="cbpLline">
          <a href="/?dlid=102167&comicpage=&b=i" itemprop="name">Pagets Super Comic</a>
          <img src="https://cdn.example.com/viewer/dd/abc123/mediumthumb.jpg">
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
        <meta itemprop="description" content="A golden age comic.">
        <meta itemprop="genre" content="Superhero">
        <meta itemprop="thumbnailUrl" content="https://cdn.example.com/viewer/dd/abc123/mediumthumb.jpg">
        </head><body>
        <span itemprop="numberOfPages">3</span>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/?cbplus=latestuploads_l_s_") -> MockResponse().setBody(listHtml)
                    path.startsWith("/?dlid=102167") -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ComicBookPlusSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, dlid-based url and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Pagets Super Comic", result[0].title)
        assertEquals("/?dlid=102167", result[0].url)
        assertEquals("COMIC", result[0].contentType)
    }

    @Test
    fun `getMangaDetails extracts description and genre from meta tags`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A golden age comic.", details.description)
        assertEquals(listOf("Superhero"), details.genres)
    }

    @Test
    fun `getChapterList returns a single synthetic Read chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Read", chapters[0].name)
    }

    @Test
    fun `getPageList builds sequential page URLs from thumbnailUrl directory and numberOfPages`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(3, pages.size)
        assertEquals("https://cdn.example.com/viewer/dd/abc123/0.jpg", pages[0].url)
        assertEquals("https://cdn.example.com/viewer/dd/abc123/1.jpg", pages[1].url)
        assertEquals("https://cdn.example.com/viewer/dd/abc123/2.jpg", pages[2].url)
    }

    @Test
    fun `malformed empty body returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html><body></body></html>")
        }
        server.start()
        val emptySource = ComicBookPlusSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

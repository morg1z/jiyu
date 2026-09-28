package com.haise.jiyu.source.raw1001

import com.haise.jiyu.source.MangaFilter
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

class Raw1001SourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: Raw1001Source

    private val listHtml = """
        <html><body>
        <a href="https://raw1001.net/manga/test-series"><img alt="Test Series" data-src="https://cdn.example.com/cover.jpg"></a>
        </body></html>
    """.trimIndent()

    // Simuluje JSON-LD breadcrumb blok se escapovanymi lomitky, presne jak ho vraci skutecny web.
    private val detailHtml = """
        <html><body>
        <a href="https://raw1001.net/genres/action/">Action</a>
        <script type="application/ld+json">
        {"@context":"https:\/\/schema.org","itemListElement":[{"@type":"ListItem","position":1,"url":"https:\/\/raw1001.net\/chapters\/test-series\/di2hua\/222"},{"@type":"ListItem","position":2,"url":"https:\/\/raw1001.net\/chapters\/test-series\/di1hua\/111"}]}
        </script>
        </body></html>
    """.trimIndent()

    private val pagesJson = """
        {"status":true,"html":"<div class=\"separator\"><a href=\"https:\/\/cdn.example.com\/1.webp\" class=\"readImg\"><img/></a></div><div class=\"separator\"><a href=\"https:\/\/cdn.example.com\/2.webp\" class=\"readImg\"><img/></a></div>"}
    """.trimIndent()

    // Homepage nese zanrovy index - nazev je v "title" atributu.
    private val homeHtml = """
        <html><body>
        <a href="https://raw1001.net/genres/academy" title="Academy">Academy</a>
        <a href="https://raw1001.net/genres/action" title="Action">Action</a>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse().setBody(homeHtml)
                    path.startsWith("/genres/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/all-manga/") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/ajax/image/list/chap/111" -> MockResponse().setBody(pagesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = Raw1001Source(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular filters to single-segment manga detail links only`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `getMangaDetails parses genre links`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals(listOf("Action"), details.genres)
    }

    @Test
    fun `getChapterList extracts numeric chapter id from escaped JSON-LD breadcrumbs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals("222", chapters[0].url)
        assertEquals("111", chapters[1].url)
    }

    @Test
    fun `getPageList parses readImg anchors from the ajax JSON html fragment`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/1.webp", pages[0].url)
    }

    @Test
    fun `getAvailableTags reads the homepage genre index`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertEquals("academy", tags[0].id)
        assertEquals("Academy", tags[0].label)
    }

    @Test
    fun `getPopular with a selected genre reads the genre archive`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("academy")))
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `search filters the catalog locally by title`() = runTest {
        assertEquals(1, source.search("test", 1).size)
        assertTrue(source.search("zzz", 1).isEmpty())
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = Raw1001Source(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

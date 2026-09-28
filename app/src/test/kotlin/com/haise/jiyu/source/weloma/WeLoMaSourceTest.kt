package com.haise.jiyu.source.weloma

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
import java.util.Base64

class WeLoMaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: WeLoMaSource

    private val popularHtml = """
        <html><body>
        <div class="thumb-item-flow col-6 col-md-3">
            <div class="thumb-wrapper" data-id="1">
                <a href="/m/testid">
                    <div class="a6-ratio">
                        <div class="content img-in-ratio lazyloaded" style="background-image: url('https://cdn.example.com/cover.jpg'); background-position: initial;"></div>
                    </div>
                </a>
                <div class="thumb-detail"></div>
            </div>
            <div class="thumb_attr series-title"><a href="/m/testid" title="Test Series - Raw">Test Series - Raw</a></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <title data-enc="dGVzdA=="></title>
        <li><b>Author(s)</b>: <small><a class="btn btn-xs btn-info" href='/l/a1'>Test Author</a></small></li>
        <li><b>Status</b>: <a href="/manga-on-going.html" class="btn btn-xs btn-success">On going</a></li>
        <li><b>Genre(s)</b>: <small><a class="btn btn-xs btn-danger" href='/l/g1'>Action</a> <a class="btn btn-xs btn-danger" href='/l/g2'>Fantasy</a></small></li>
        <div class="list-chapters at-series">
            <a href="/c/ch2" title="Chapter 2"><li><div class="chapter-name text-truncate">Chapter 2</div></li></a>
            <a href="/c/ch1" title="Chapter 1"><li><div class="chapter-name text-truncate">Chapter 1</div></li></a>
        </div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = buildString {
        append("<html><body>")
        listOf("https://cdn.example.com/p1.jpg", "https://cdn.example.com/p2.jpg").forEach { url ->
            val encoded = Base64.getEncoder().encodeToString(url.toByteArray())
            append("<img class='chapter-img lazyload' src='data:image/gif;base64,x' data-img='$encoded'>")
        }
        append("</body></html>")
    }

    // Homepage obsahuje sekci Genres - zanry poznavame podle data-title="Genre X",
    // autorove sdili stejny "/l/" prefix, ale data-title="Genre" nemaji.
    private val homeHtml = """
        <html><body>
        <ul><li>Genres</li>
            <li><a data-title="Genre Action" href="/l/g1">Action</a></li>
            <li><a data-title="Genre Fantasy" href="/l/g2">Fantasy</a></li>
            <li><a href="/l/a1">Some Author</a></li>
        </ul>
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
                    path.startsWith("/l/") -> MockResponse().setBody(popularHtml)
                    path.startsWith("/manga-list.html") -> MockResponse().setBody(popularHtml)
                    path == "/m/testid" -> MockResponse().setBody(detailHtml)
                    path == "/c/ch1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = WeLoMaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses series-title and background-image cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series - Raw", result[0].title)
        assertEquals("/m/testid", result[0].url)
        assertEquals("https://cdn.example.com/cover.jpg", result[0].coverUrl)
    }

    @Test
    fun `search reuses the same card parsing`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
    }

    @Test
    fun `getMangaDetails parses author, status and genres without touching title`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Test Series - Raw", details.title)
        assertEquals("Test Author", details.author)
        assertEquals("On going", details.status)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
    }

    @Test
    fun `getChapterList reads title attribute for chapter number`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList decodes base64 data-img into direct URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p1.jpg", pages[0].url)
        assertEquals("https://cdn.example.com/p2.jpg", pages[1].url)
    }

    @Test
    fun `getAvailableTags returns only data-title Genre links, not authors`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertEquals("g1", tags[0].id)
        assertEquals("Action", tags[0].label)
        assertEquals("g2", tags[1].id)
    }

    @Test
    fun `getPopular with a selected genre reads the genre archive`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("g1")))
        assertEquals(1, result.size)
        assertEquals("Test Series - Raw", result[0].title)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = WeLoMaSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

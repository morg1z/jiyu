package com.haise.jiyu.source.readnovelfull

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadNovelFullSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ReadNovelFullSource

    private val navGenres = """<a href="/genres/action">Action</a><a href="/genres/romance">Romance</a>"""

    private val listHtml = """
        <html><body><nav>$navGenres</nav>
        <div class="list-novel"><div class="row">
            <div class="col-novel-main"><h3 class="novel-title"><a href="/test.html" title="Test">Test</a></h3>
            <img class="cover" src="/c.jpg"></div>
        </div></div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h3 class="title">Test Novel Full</h3>
        <div class="desc-text"><p>Synopsis text.</p></div>
        <ul class="info info-meta">
            <li><h3>Author:</h3><a href="/authors/x">Author X</a></li>
            <li><h3>Genre:</h3><a href="/genres/action">Action</a></li>
        </ul>
        <div id="list-chapter"><div id="chapter-archive"><ul class="list-chapter">
            <li><a href="/test/chapter-1-a.html" title="Chapter 1">Chapter 1</a></li>
            <li><a href="/test/chapter-2-b.html" title="Chapter 2">Chapter 2</a></li>
        </ul></div></div>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div id="chr-content" class="chr-c"><p>Body of chapter.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/novel-list/most-popular") -> MockResponse().setBody(listHtml)
                    path.startsWith("/genres/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/novel-list/search") -> MockResponse().setBody(listHtml)
                    path == "/" -> MockResponse().setBody("<html><body>$navGenres</body></html>")
                    path == "/test.html" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/test/chapter-") -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ReadNovelFullSource(
            id = "ext:test", name = "Test", baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getPopular parses list-novel rows`() = runTest {
        val r = source.getPopular(1)
        assertEquals(1, r.size)
        assertEquals("Test", r[0].title)
        assertEquals("/test.html", r[0].url)
    }

    @Test
    fun `tags come from navbar genres links`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(setOf("action", "romance"), tags.map { it.id }.toSet())
    }

    @Test
    fun `genre filter and details, chapters, text`() = runTest {
        assertEquals(1, source.getPopular(1, MangaFilter(genres = listOf("action"))).size)
        val manga = SManga("ext:test", "/test.html", "T", null)
        val d = source.getMangaDetails(manga)
        assertEquals("Test Novel Full", d.title)
        assertEquals("Author X", d.author)
        assertEquals(listOf("Action"), d.genres)
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        val pages = source.getPageList(SChapter("ext:test", "/test.html", "/test/chapter-1-a.html", "C1", 1f, 0L))
        assertTrue(pages[0].url.contains("Body of chapter."))
        assertEquals("novel://text", pages[0].imageUrl)
    }
}

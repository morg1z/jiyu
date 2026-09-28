package com.haise.jiyu.source.readwn

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

class ReadWnSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ReadWnSource

    private val listHtml = """
        <html><body>
        <div class="novel-item"><a href="/novel/123-test.html">
            <span class="novel-cover"><img data-src="https://x.test/c.jpg"></span>
            <h4 class="novel-title">Test Novel</h4>
        </a></div>
        </body></html>
    """.trimIndent()

    private val tagsHtml = """
        <html><body>
        <a href="/list/action/all-newstime-0.html">Action</a>
        <a href="/list/wuxia/all-newstime-0.html">Wuxia</a>
        <a href="/list/all/all-newstime-0.html">All</a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="novel-title">Test Novel</h1>
        <div itemprop="description"><p>Synopsis.</p></div>
        <ol class="chapter-list">
            <li><a href="/novel/123-test/chapter-1.html"><span class="chapter-title">Chapter 1</span>
                <span class="chapter-update">2 days ago</span></a></li>
            <li><a href="/novel/123-test/chapter-2.html"><span class="chapter-title">Chapter 2</span></a></li>
        </ol>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div class="chapter-content">
            <p>Chapter text one.</p><br><p>Two.</p>
        </div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/list/all/all-onclick-0.html" -> MockResponse().setBody(listHtml)
                    path == "/list/action/all-onclick-0.html" -> MockResponse().setBody(listHtml)
                    path == "/browsetags/" -> MockResponse().setBody(tagsHtml)
                    path == "/" -> MockResponse().setBody(tagsHtml)
                    path == "/e/search/index.php" -> MockResponse().setBody(listHtml)
                    path == "/novel/123-test.html" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/novel/123-test/chapter-") -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ReadWnSource(
            id = "ext:test", name = "Test", baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getPopular parses novel-item cards`() = runTest {
        val r = source.getPopular(1)
        assertEquals(1, r.size)
        assertEquals("Test Novel", r[0].title)
        assertEquals("/novel/123-test.html", r[0].url)
        assertEquals("https://x.test/c.jpg", r[0].coverUrl)
    }

    @Test
    fun `genre filter hits list archive and skips 'all' tag`() = runTest {
        val r = source.getPopular(1, MangaFilter(genres = listOf("action")))
        assertEquals(1, r.size)
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/list/action/"))
        // Tagy se tahají z /browsetags/ - "all" se odfiltruje (není žánr).
        val tags = source.getAvailableTags()
        assertEquals(setOf("action", "wuxia"), tags.map { it.id }.toSet())
    }

    @Test
    fun `search posts EmpireCMS form`() = runTest {
        val r = source.search("test")
        assertEquals(1, r.size)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.body.readUtf8().contains("keyboard=test"))
    }

    @Test
    fun `chapters and page text`() = runTest {
        val chapters = source.getChapterList(SManga("ext:test", "/novel/123-test.html", "T", null))
        assertEquals(2, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertTrue(chapters[0].dateUpload > 0)
        val pages = source.getPageList(SChapter("ext:test", "/novel/123-test.html", "/novel/123-test/chapter-1.html", "C1", 1f, 0L))
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("Chapter text one."))
    }
}

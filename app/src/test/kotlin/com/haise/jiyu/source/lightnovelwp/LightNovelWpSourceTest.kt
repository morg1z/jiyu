package com.haise.jiyu.source.lightnovelwp

import com.haise.jiyu.source.MangaFilter
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

class LightNovelWpSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LightNovelWpSource

    private val archiveHtml = """
        <html><body>
        <div class="listupd">
        <article class="maindet">
            <div class="mdthumb"><a href="https://x.test/series/test-novel/"><img src="https://x.test/c.jpg"></a></div>
            <div class="mdinfo"><h2><a href="https://x.test/series/test-novel/">Test Novel</a></h2></div>
        </article>
        </div>
        <input type="checkbox" name="genre[]" value="action" id="g1"><label for="g1">Action</label>
        <input type="checkbox" name="genre[]" value="fantasy" id="g2"><label for="g2">Fantasy</label>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1 class="entry-title">Test Novel</h1>
        <div itemprop="description"><p>A synopsis.</p></div>
        <div class="sertogenre"><a href="#">Action</a><a href="#">Fantasy</a></div>
        <div class="eplister"><ul>
            <li><a href="https://x.test/test-novel-ch-1/"><span class="epl-num">1</span>
                <span class="epl-title">The Beginning</span><span class="epl-date">January 2, 2025</span></a></li>
            <li><a href="https://x.test/test-novel-ch-2/"><span class="epl-num">2</span>
                <span class="epl-title">Next</span><span class="epl-date">January 9, 2025</span></a></li>
        </ul></div>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div class="epcontent entry-content">
            <p>First paragraph.</p><p>Second paragraph.</p>
            <script>evil()</script><div class="pc-adv"><p>AD</p></div>
        </div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/series/test-novel/" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/test-novel-ch-") -> MockResponse().setBody(chapterHtml)
                    path.startsWith("/series/") -> MockResponse().setBody(archiveHtml)
                    path.startsWith("/?s=") -> MockResponse().setBody(archiveHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = LightNovelWpSource(
            id = "ext:test", name = "Test", baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getPopular parses cards with title, url, cover`() = runTest {
        val r = source.getPopular(1)
        assertEquals(1, r.size)
        assertEquals("Test Novel", r[0].title)
        assertEquals("https://x.test/series/test-novel/", r[0].url)
        assertEquals("https://x.test/c.jpg", r[0].coverUrl)
        assertEquals("NOVEL", r[0].contentType)
    }

    @Test
    fun `getAvailableTags parses genre checkboxes`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("action", "fantasy"), tags.map { it.id })
        assertEquals("Action", tags[0].label)
    }

    @Test
    fun `genre filter goes to archive with genre param`() = runTest {
        val r = source.getPopular(1, MangaFilter(genres = listOf("action")))
        assertEquals(1, r.size)
        val recorded = server.takeRequest()
        assertTrue(recorded.path.orEmpty().let { it.contains("genre") && it.contains("action") })
    }

    @Test
    fun `chapter list parses eplister with numbers and dates`() = runTest {
        val chapters = source.getChapterList(
            com.haise.jiyu.source.SManga("ext:test", "/series/test-novel/", "Test", null)
        )
        assertEquals(2, chapters.size)
        assertEquals("The Beginning", chapters[0].name)
        assertEquals(1f, chapters[0].chapterNumber)
        assertTrue(chapters[0].dateUpload > 0)
    }

    @Test
    fun `page list returns novel text preserving paragraphs`() = runTest {
        val pages = source.getPageList(
            com.haise.jiyu.source.SChapter("ext:test", "/series/test-novel/", "/test-novel-ch-1/", "Ch 1", 1f, 0L)
        )
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("First paragraph."))
        assertTrue(pages[0].url.contains("Second paragraph."))
        assertTrue(!pages[0].url.contains("evil()"))
        assertTrue(!pages[0].url.contains("AD"))
    }
}

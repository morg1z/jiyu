package com.haise.jiyu.source.luacomic

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

class LuaComicSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LuaComicSource

    private val listJson = """
        {"meta":{"total":1},"data":[{"id":658,"title":"Test Series","description":"A test summary.","series_type":"Comic","series_slug":"test-series","thumbnail":"https://cdn.example.com/cover.jpg","status":"Ongoing"}]}
    """.trimIndent()

    private val chaptersJson = """
        {"meta":{"total":2},"data":[{"id":2,"chapter_name":"Chapter 2","chapter_slug":"chapter-2","series_id":658},{"id":1,"chapter_name":"Chapter 1","chapter_slug":"chapter-1","series_id":658}]}
    """.trimIndent()

    // Audit 2026-11: web prestal pridavat ".jpg" suffix; stranky jsou
    // page_NNN.webp pod uploads/series/{slug}/{uuid}/. Mimo "/series/" cestu
    // na stejnem CDN lezi i thumbnaily - parser je musi odfiltrovat.
    private val readerHtml = """
        <html><body>
        <img src="https://media.luacomic.org/file/V4IKlhs/uploads/series/test-series/abc/page_001.webp">
        <img src="https://media.luacomic.org/file/V4IKlhs/uploads/series/test-series/abc/page_002.webp">
        <img src="https://media.luacomic.org/file/V4IKlhs/hc8zemhks29010jvb1v3fwir.webp">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/query") -> MockResponse().setBody(listJson)
                    path.startsWith("/chapter/query") -> MockResponse().setBody(chaptersJson)
                    path == "/series/test-series/chapter-1" -> MockResponse().setBody(readerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = LuaComicSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses JSON listing and encodes id-slug into url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("658/test-series", result[0].url)
        assertEquals("Ongoing", result[0].status)
    }

    @Test
    fun `search reuses the same query endpoint`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
    }

    @Test
    fun `getChapterList queries by numeric series_id extracted from url`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList extracts media-luacomic-org URLs embedded in SSR HTML`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://media.luacomic.org/file/V4IKlhs/uploads/series/test-series/abc/page_001.webp", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = LuaComicSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

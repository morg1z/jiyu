package com.haise.jiyu.source.mangadotnet

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

class MangaDotNetSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaDotNetSource

    private val listHtml = """
        <html><body>
        <a class="group flex flex-col gap-1.5" href="/manga/175" data-discover="true">
          <div class="relative aspect-[2/3]">
            <div><img src="/uploads/cover.jpg" alt=""/></div>
            <span>Manga</span>
          </div>
          <div class="line-clamp-2 text-[12px]">Omniscient Reader</div>
        </a>
        </body></html>
    """.trimIndent()

    private val searchJson = """
        {"manga_list":[{"id":175,"title":"Omniscient Reader","photo":"/uploads/cover.jpg"}]}
    """.trimIndent()

    private val detailJson = """
        {"manga":{"id":175,"title":"Omniscient Reader","genres":["Action","Fantasy"],"status":"Ongoing","photo":"/uploads/cover.jpg","description":"A test summary.","authors":"[\"Singsyong\",\"UMI\"]"}}
    """.trimIndent()

    private val chaptersJson = """
        [{"id":62029,"chapter_number":0,"chapter_title":"Prologue","date_added":"2026-03-20 12:18:04+00","source":"user"}]
    """.trimIndent()

    private val pagesJson = """
        {"images":[{"url":"/chapters/manga_175/001.jpg"}]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/view-all/most-tracked") -> MockResponse().setBody(listHtml)
                    path.startsWith("/api/search") -> MockResponse().setBody(searchJson)
                    path == "/api/manga/175" -> MockResponse().setBody(detailJson)
                    path == "/api/manga/175/chapters/list" -> MockResponse().setBody(chaptersJson)
                    path == "/api/uploads/62029/images" -> MockResponse().setBody(pagesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaDotNetSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Omniscient Reader", result[0].title)
        assertEquals("/manga/175", result[0].url)
    }

    @Test
    fun `search parses JSON manga_list`() = runTest {
        val result = source.search("solo", 1)
        assertEquals(1, result.size)
        assertEquals("Omniscient Reader", result[0].title)
    }

    @Test
    fun `getMangaDetails parses genres and double-encoded authors`() = runTest {
        val manga = source.search("solo", 1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test summary.", details.description)
        assertEquals("Singsyong, UMI", details.author)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
        assertEquals("Ongoing", details.status)
    }

    @Test
    fun `getChapterList and getPageList parse JSON API responses`() = runTest {
        val manga = source.search("solo", 1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("/chapter/62029?source=user", chapters[0].url)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertTrue(pages[0].url.startsWith("https://mangadot.net"))
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = MangaDotNetSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
        assertTrue(emptySource.search("x", 1).isEmpty())
    }
}

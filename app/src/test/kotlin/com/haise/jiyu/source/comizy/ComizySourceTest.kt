package com.haise.jiyu.source.comizy

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

class ComizySourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ComizySource

    private fun nextDataPage(pagePropsJson: String) = """
        <html><body><script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":$pagePropsJson},"page":"/","query":{},"buildId":"x"}
        </script></body></html>
    """.trimIndent()

    private val latestHtml = nextDataPage(
        """{"items":[{"id":"abc123","url":"/test-manga","name":"Test Manga","cover":"https://cdn.example.com/cover.webp"}]}"""
    )

    private val searchHtml = nextDataPage(
        """{"ssrItems":[{"id":"abc123","url":"/test-manga","name":"Test Manga","cover":"https://cdn.example.com/cover.webp"}]}"""
    )

    private val detailHtml = nextDataPage(
        """{"initialManga":{"name":"Test Manga","summary":"A test summary.","status":"Ongoing","cover":"https://cdn.example.com/cover.webp","genres":[{"name":"Action"},{"name":"Romance"}],"chapters":[{"id":"c1","name":"Chapter 1","url":"/test-manga/chapter-1","number":1,"updatedAt":"2026-05-01T00:00:00.000Z"}]}}"""
    )

    private val chapterHtml = nextDataPage(
        """{"initialChapter":{"images":["https://cdn.example.com/1/01.webp","https://cdn.example.com/1/02.webp"]}}"""
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/latest") -> MockResponse().setBody(latestHtml)
                    path.startsWith("/popular") -> MockResponse().setBody(latestHtml)
                    path.startsWith("/search") -> MockResponse().setBody(searchHtml)
                    path == "/test-manga" -> MockResponse().setBody(detailHtml)
                    path == "/test-manga/chapter-1" -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ComizySource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses items from NEXT_DATA JSON`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Manga", result[0].title)
        assertEquals("https://cdn.example.com/cover.webp", result[0].coverUrl)
        assertEquals("https://comizy.io/test-manga", result[0].url)
        assertEquals("MANHWA", result[0].contentType)
    }

    @Test
    fun `search parses ssrItems from NEXT_DATA JSON`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("Test Manga", result[0].title)
    }

    @Test
    fun `getMangaDetails parses summary, status and genres`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test summary.", details.description)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Action", "Romance"), details.genres)
        assertEquals("MANHWA", details.contentType)
    }

    @Test
    fun `getChapterList and getPageList parse initialManga chapters and initialChapter images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("https://comizy.io/test-manga/chapter-1", chapters[0].url)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/1/01.webp", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = ComizySource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

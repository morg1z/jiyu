package com.haise.jiyu.source.mangak

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
 * mangak.io je Next.js aplikace - vsechna data se ctou z __NEXT_DATA__
 * JSON bloku vlozeneho do stranky (viz komentar v MangaKSource.kt),
 * fixture proto simuluje tenhle presny tvar pro kazdou stranku.
 */
class MangaKSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaKSource

    private fun nextDataHtml(pageProps: String) = """
        <html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$pageProps}}</script></body></html>
    """.trimIndent()

    private val listHtml = nextDataHtml(
        """{"initialItems":[{"url":"/test-series","name":"Test Series","cover":"https://cdn.example.com/test.jpg"}]}"""
    )

    private val searchHtml = nextDataHtml(
        """{"ssrItems":[{"url":"/test-series","name":"Test Series","cover":"https://cdn.example.com/test.jpg"}]}"""
    )

    private val detailHtml = nextDataHtml(
        """{"initialManga":{"name":"Test Series","cover":"https://cdn.example.com/test.jpg","summary":"A description.","genres":[{"name":"Action"}],"authors":[{"name":"Some Author"}],"chapters":[{"name":"Chapter 1","url":"/test-series/chapter-1"}]}}"""
    )

    private val pagesHtml = nextDataHtml(
        """{"initialChapter":{"images":["https://cdn.example.com/test/1/01.jpg"]}}"""
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/ranking?page=") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search?q=") -> MockResponse().setBody(searchHtml)
                    path == "/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/test-series/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaKSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses initialItems from NEXT_DATA`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
    }

    @Test
    fun `search parses ssrItems from NEXT_DATA`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("/test-series", result[0].url)
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A description.", details.description)
        assertEquals(listOf("Action"), details.genres)
        assertEquals("Some Author", details.author)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/test/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaKSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

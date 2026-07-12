package com.haise.jiyu.source.mangahub

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

/** MangaHub pouziva GraphQL POST na jedinou cestu /graphql - dispatcher tedy vetvi podle obsahu body. */
class MangaHubSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaHubSource

    private val searchJson = """
        {"data": {"search": {"rows": [{"id": 1, "title": "Test Series", "slug": "test-series", "image": "test.jpg"}]}}}
    """.trimIndent()

    private val detailJson = """
        {"data": {"manga": {"id": 1, "title": "Test Series", "image": "test.jpg", "description": "A summary.", "genres": ["Action"], "status": "ongoing", "author": "Jane"}}}
    """.trimIndent()

    private val idLookupJson = """{"data": {"manga": {"id": 42}}}"""

    private val chaptersJson = """
        {"data": {"chapters": [{"id": 1, "number": 1.0, "title": "Chapter 1", "date": "2026-01-01"}]}}
    """.trimIndent()

    private val imagesJson = """
        {"data": {"chapter": {"id": 1, "images": "[\"https://cdn.example.com/test/1/01.jpg\"]"}}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    body.contains("chapter(x:") -> MockResponse().setBody(imagesJson)
                    body.contains("chapters(x:") -> MockResponse().setBody(chaptersJson)
                    body.contains("description") -> MockResponse().setBody(detailJson)
                    body.contains("manga(x:") -> MockResponse().setBody(idLookupJson)
                    body.contains("search(x:") -> MockResponse().setBody(searchJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaHubSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses rows from nested data-search-rows`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertTrue(result[0].coverUrl!!.endsWith("/test.jpg"))
    }

    @Test
    fun `getMangaDetails parses description, genres, author and status`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Jane", details.author)
        assertEquals("ongoing", details.status)
        assertEquals(listOf("Action"), details.genres)
    }

    @Test
    fun `getChapterList resolves numeric mangaID first, then fetches chapters`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].name)
    }

    @Test
    fun `getPageList parses images JSON-string field`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/test/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val failingSource = MangaHubSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

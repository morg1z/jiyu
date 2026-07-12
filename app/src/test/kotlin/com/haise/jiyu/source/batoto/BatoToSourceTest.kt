package com.haise.jiyu.source.batoto

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

/** Bato.to pouziva GraphQL POST na jedinou cestu /apo/ - dispatcher tedy vetvi podle obsahu body. */
class BatoToSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: BatoToSource

    private val popularJson = """
        { "data": { "getComics": { "comics": [
            {"id": 1, "name": "Solo Leveling", "urlPath": "/title/solo-leveling", "urlCoverOri": "https://cdn.example.com/solo.jpg"}
        ] } } }
    """.trimIndent()

    private val detailJson = """
        { "data": { "getComic": {
            "name": "Solo Leveling", "urlCoverOri": "https://cdn.example.com/solo.jpg",
            "desc": "A weak hunter grows strong.",
            "genres": [{"name": "Action"}], "authors": [{"name": "Chugong"}]
        } } }
    """.trimIndent()

    private val chaptersJson = """
        { "data": { "getComic": { "chapters": [
            {"id": 1, "title": "Ch. 1", "urlPath": "/chapter/1", "chapterNum": 1.0, "volNum": null}
        ] } } }
    """.trimIndent()

    private val imagesJson = """
        { "data": { "getChapter": { "images": ["https://cdn.example.com/1/01.jpg", "https://cdn.example.com/1/02.jpg"] } } }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    body.contains("getComics") -> MockResponse().setBody(popularJson)
                    body.contains("getChapter(chapterPath") -> MockResponse().setBody(imagesJson)
                    body.contains("chapters {") -> MockResponse().setBody(chaptersJson)
                    body.contains("getComic(comicPath") -> MockResponse().setBody(detailJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = BatoToSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses comics from GraphQL response`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Solo Leveling", result[0].title)
        assertEquals("https://cdn.example.com/solo.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails extracts description, genres and author`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A weak hunter grows strong.", details.description)
        assertEquals("Chugong", details.author)
        assertEquals(listOf("Action"), details.genres)
    }

    @Test
    fun `getChapterList and getPageList parse GraphQL arrays`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val failingSource = BatoToSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

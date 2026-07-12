package com.haise.jiyu.source.mangapark

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

class MangaParkSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaParkSource

    private val searchJson = """
        { "data": { "searchComics": { "items": [
            { "data": {"id": 1, "name": "Tower of God", "urlPath": "/title/tower-of-god", "imageCoverUrl": "https://cdn.example.com/tog.jpg"} }
        ] } } }
    """.trimIndent()

    private val detailJson = """
        { "data": { "comicByUrlPath": { "data": {
            "name": "Tower of God", "imageCoverUrl": "https://cdn.example.com/tog.jpg",
            "summary": "Climb the tower.", "genres": [{"name": "Action"}], "artists": [{"name": "SIU"}]
        } } } }
    """.trimIndent()

    private val chaptersJson = """
        { "data": { "comicByUrlPath": { "data": { "chapterNodes": [
            { "data": {"id": 1, "dname": "Chapter 1", "urlPath": "/title/tower-of-god/1", "numberFloat": 1.0} }
        ] } } } }
    """.trimIndent()

    private val pagesJson = """
        { "data": { "chapterByUrlPath": { "data": { "imageFile": { "urlList": ["https://cdn.example.com/tog/1/01.jpg"] } } } } }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    body.contains("imageFile") -> MockResponse().setBody(pagesJson)
                    body.contains("chapterNodes") -> MockResponse().setBody(chaptersJson)
                    body.contains("comicByUrlPath") -> MockResponse().setBody(detailJson)
                    body.contains("searchComics") -> MockResponse().setBody(searchJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaParkSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses comic items`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Tower of God", result[0].title)
    }

    @Test
    fun `getMangaDetails, getChapterList and getPageList parse GraphQL data`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Climb the tower.", details.description)
        assertEquals("SIU", details.author)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/tog/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val failingSource = MangaParkSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

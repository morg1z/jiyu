package com.haise.jiyu.source.mangacloud

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

private class FakeSession(private val cookie: String? = "session=fake") : MangaCloudSession {
    override fun getCookie(): String? = cookie
}

class MangaCloudSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaCloudSource

    private val popularJson = """
        {"data":{"period":"today","list":[{"id":"123","title":"Test Series","chapter_id":"999","number":1,"cover":{"id":"555","w":512,"h":742,"f":"jpeg"}}]}}
    """.trimIndent()

    private val searchJson = """
        {"data":[{"id":"123","title":"Test Series","cover":{"id":"555","f":"jpeg"}}]}
    """.trimIndent()

    private val detailJson = """
        {"data":{"id":"123","title":"Test Series","description":"A test summary.","status":"Ongoing","authors":"Jane Doe","tags":[{"name":"Action","type":"genre"}],"cover":{"id":"555","f":"jpeg"},"chapters":[{"id":"999","number":1,"name":null,"created_date":"2026-03-20T12:18:04.000Z"}]}}
    """.trimIndent()

    private val pagesJson = """
        {"data":{"id":"999","comic_id":"123","images":[{"id":"888","w":1200,"h":800,"f":"webp"}]}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/comic-popular-view/today") -> MockResponse().setBody(popularJson)
                    path.startsWith("/search") -> MockResponse().setBody(searchJson)
                    path == "/comic/123" -> MockResponse().setBody(detailJson)
                    path == "/chapters/999" -> MockResponse().setBody(pagesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaCloudSource(redirectingClient(server), FakeSession())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and constructed cover URL`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://pika.mangacloud.org/123/555.jpeg", result[0].coverUrl)
    }

    @Test
    fun `search parses data array`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `getMangaDetails and getChapterList parse embedded chapters array`() = runTest {
        val manga = source.search("test", 1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A test summary.", details.description)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action"), details.genres)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("/123/999", chapters[0].url)
    }

    @Test
    fun `getPageList constructs pika CDN URLs from comic and chapter ids`() = runTest {
        val manga = source.search("test", 1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://pika.mangacloud.org/123/999/888.webp", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = MangaCloudSource(redirectingClient(server), FakeSession())
        assertTrue(emptySource.getPopular(1).isEmpty())
    }

    @Test
    fun `missing session cookie still attempts request without crashing`() = runTest {
        val noSession = MangaCloudSource(redirectingClient(server), FakeSession(cookie = null))
        assertEquals(1, noSession.getPopular(1).size)
    }
}

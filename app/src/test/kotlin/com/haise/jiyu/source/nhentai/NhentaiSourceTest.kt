package com.haise.jiyu.source.nhentai

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

class NhentaiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NhentaiSource

    private val galleryJson = """
        {
          "id": 999,
          "media_id": "12345",
          "num_pages": 2,
          "title": {"english": "Test Gallery", "pretty": "Test", "japanese": "テスト"},
          "images": {
            "cover": {"t": "j"},
            "pages": [{"t": "j"}, {"t": "p"}]
          },
          "tags": [
            {"type": "artist", "name": "Some Artist"},
            {"type": "tag", "name": "comedy"}
          ]
        }
    """.trimIndent()

    private val searchJson = """{ "result": [ $galleryJson ] }"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/galleries/all") -> MockResponse().setBody(searchJson)
                    path.startsWith("/api/galleries/search") -> MockResponse().setBody(searchJson)
                    path.startsWith("/api/gallery/") -> MockResponse().setBody(galleryJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NhentaiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, cover and artist from tags`() = runTest {
        val result = source.getPopular(1)

        assertEquals(1, result.size)
        assertEquals("Test Gallery", result[0].title)
        assertEquals("Some Artist", result[0].author)
        assertTrue(result[0].coverUrl!!.contains("12345/cover.j"))
        assertEquals(listOf("Some Artist", "comedy"), result[0].genres)
    }

    @Test
    fun `getPageList builds one URL per page with correct extension`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(2, pages.size)
        assertTrue(pages[0].url.endsWith("/12345/1.jpg"))
        assertTrue(pages[1].url.endsWith("/12345/2.png"))
    }

    @Test
    fun `getChapterList always returns a single synthetic chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `server error returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        server.start()
        val failingSource = NhentaiSource(redirectingClient(server))

        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

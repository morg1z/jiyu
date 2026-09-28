package com.haise.jiyu.source.hivetoons

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
 * HiveToons bezi ciste pres api.hivetoons.org JSON API (frontend hivetoons.org
 * je od 2026-10 mrtvy - 307 redirect smycka). Test fixtures kopiruji realne
 * tvary odpovedi: /api/posts, /api/chapters?postId=, /api/chapter?chapterId=.
 */
class HiveToonsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HiveToonsSource

    private val postsJson = """
        {"posts":[
          {"id":320,"slug":"shadow-slave","postTitle":"Shadow Slave",
           "featuredImage":"https://cdn.example.com/cover.webp","seriesType":"MANHWA",
           "seriesStatus":"ONGOING","lastChapterAddedAt":"2026-09-24T01:25:45.893Z",
           "genres":[{"id":2,"name":"Drama"},{"id":5,"name":"Action"}]},
          {"id":321,"slug":"older-series","postTitle":"Older Series",
           "featuredImage":"https://cdn.example.com/old.webp","seriesType":"MANGA",
           "seriesStatus":"COMPLETED","lastChapterAddedAt":"2026-01-01T00:00:00.000Z"}
        ],"totalCount":2}
    """.trimIndent()

    private val chaptersJson = """
        {"post":{"chapters":[
          {"id":100,"slug":"chapter-1","number":1,"title":"","isLocked":false,"isAccessible":true,"createdAt":"2026-01-01T00:00:00.000Z"},
          {"id":101,"slug":"chapter-2","number":2,"title":"","isLocked":false,"isAccessible":true,"createdAt":"2026-02-01T00:00:00.000Z"},
          {"id":102,"slug":"chapter-3","number":3,"title":"Premium","isLocked":true,"isAccessible":false,"createdAt":"2026-03-01T00:00:00.000Z"}
        ]}}
    """.trimIndent()

    private val pagesJson = """
        {"chapter":{"images":[
          {"id":1,"url":"https://cdn.example.com/p/02.webp","order":1},
          {"id":2,"url":"https://cdn.example.com/p/01.webp","order":0}
        ]}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/chapter?") -> MockResponse().setBody(pagesJson)
                    path.startsWith("/api/chapters?") -> MockResponse().setBody(chaptersJson)
                    path == "/api/genres" -> MockResponse().setBody("""[{"name":"Action"},{"name":"Drama"}]""")
                    path.startsWith("/api/posts") -> MockResponse().setBody(postsJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HiveToonsSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses posts from JSON API`() = runTest {
        val result = source.getPopular(1)
        assertEquals(2, result.size)
        assertEquals("Shadow Slave", result[0].title)
        assertEquals("https://cdn.example.com/cover.webp", result[0].coverUrl)
        assertEquals("MANHWA", result[0].contentType)
        assertEquals("MANGA", result[1].contentType)
    }

    @Test
    fun `latest sorts catalog by lastChapterAddedAt descending`() = runTest {
        val result = source.getPopular(1, com.haise.jiyu.source.MangaFilter(sortBy = "latest"))
        assertEquals(listOf("Shadow Slave", "Older Series"), result.map { it.title })
    }

    @Test
    fun `search filters catalog locally by title`() = runTest {
        assertEquals(1, source.search("shadow", 1).size)
        assertTrue(source.search("nomatch", 1).isEmpty())
    }

    @Test
    fun `getAvailableTags reads flat genres array`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("Action", "Drama"), tags.map { it.label })
    }

    @Test
    fun `getMangaDetails reads genres and status from post JSON`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals(listOf("Drama", "Action"), details.genres)
        assertEquals("ongoing", details.status)
        assertEquals("MANHWA", details.contentType)
    }

    @Test
    fun `getChapterList skips locked chapters and getPageList sorts images by order`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertTrue(chapters.none { it.chapterNumber == 3f })

        val pages = source.getPageList(chapters.first())
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/p/01.webp", pages[0].url)
        assertEquals("https://cdn.example.com/p/02.webp", pages[1].url)
    }
}

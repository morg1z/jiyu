package com.haise.jiyu.source.nyxscans

import com.haise.jiyu.source.MangaFilter
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
 * NyxScans jede pres verejne JSON API (api.nyxscans.com), ne pres HTML. Zanrovy
 * server-side parametr API nepodporuje, takze se filtruje lokalne pres zanry,
 * ktere kazdy post nese primo v listingu.
 */
class NyxScansSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NyxScansSource

    private val genresJson = """[{"id":1,"name":"Action"},{"id":2,"name":"Fantasy"}]"""

    private val postsJson = """
        {"posts":[
          {"slug":"alpha","postTitle":"Alpha Story","featuredImage":"https://img.example.com/a.webp",
           "seriesStatus":"ongoing","seriesType":"manhwa","isNovel":false,
           "genres":[{"id":1,"name":"Action"},{"id":3,"name":"Drama"}]},
          {"slug":"beta","postTitle":"Beta Story","featuredImage":"https://img.example.com/b.webp",
           "seriesStatus":"ongoing","seriesType":"manhwa","isNovel":false,
           "genres":[{"id":2,"name":"Fantasy"}]}
        ],"novelPosts":[],"totalCount":2,"novelTotalCount":0}
    """.trimIndent()

    private val detailJson = """
        {"post":{"postTitle":"Alpha Story","postContent":"<p>Great plot.</p>",
        "featuredImage":"https://img.example.com/a.webp","seriesStatus":"ongoing",
        "seriesType":"manhwa","author":"Writer X","artist":"Artist Y",
        "genres":[{"id":1,"name":"Action"},{"id":3,"name":"Drama"}],
        "alternativeTitles":"Alpha Alt"},
        "firstChapter":{},"lastChapter":{},"totalChapterCount":1}
    """.trimIndent()

    private val chaptersJson = """[{"slug":"chapter-1","number":1,"title":"Start","createdAt":"2026-01-01T00:00:00Z"}]"""

    private val chapterHtml = """
        <html><body>
        <img src="https://storage.nyxscans.com/abc/page-1_xyz.webp"/>
        <img src="https://storage.nyxscans.com/abc/page-2_xyz.webp"/>
        <img src="https://storage.nyxscans.com/abc/cover_xyz.webp"/>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/genres" -> MockResponse().setBody(genresJson)
                    path.startsWith("/api/posts") -> MockResponse().setBody(
                        if (path.contains("page=1")) postsJson else """{"posts":[]}"""
                    )
                    path.startsWith("/api/post/public") -> MockResponse().setBody(detailJson)
                    path.startsWith("/api/post/chapters") -> MockResponse().setBody(chaptersJson)
                    path == "/series/alpha/chapter-1" -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NyxScansSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses posts json`() = runTest {
        val result = source.getPopular(1)
        assertEquals(2, result.size)
        assertEquals("Alpha Story", result[0].title)
        assertEquals("/series/alpha", result[0].url)
        assertEquals("MANHWA", result[0].contentType)
        assertEquals("ongoing", result[0].status)
    }

    @Test
    fun `getAvailableTags reads the genres API`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("Action", "Fantasy"), tags.map { it.id })
    }

    @Test
    fun `getPopular with a selected genre filters the catalog locally`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("Fantasy")))
        assertEquals(listOf("Beta Story"), result.map { it.title })
    }

    @Test
    fun `getMangaDetails parses post json including html description`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Great plot.", details.description)
        assertEquals("Writer X", details.author)
        assertEquals(listOf("Action", "Drama"), details.genres)
        assertEquals(listOf("Alpha Alt"), details.alternateTitles)
    }

    @Test
    fun `getChapterList parses chapters json sorted by number`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Start", chapters[0].name)
        assertEquals("/series/alpha/chapter-1", chapters[0].url)
    }

    @Test
    fun `getPageList keeps only page- prefixed storage images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        assertTrue(pages[0].url.contains("page-1"))
        assertTrue(pages.none { it.url.contains("cover") })
    }

    @Test
    fun `malformed json returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = NyxScansSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
        assertTrue(emptySource.getAvailableTags().isEmpty())
    }
}

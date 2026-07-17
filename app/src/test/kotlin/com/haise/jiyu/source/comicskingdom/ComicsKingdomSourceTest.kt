package com.haise.jiyu.source.comicskingdom

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

class ComicsKingdomSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ComicsKingdomSource

    private val featuresJson = """
        [{"id":706,"count":2878,"name":"Macanudo (Spanish)"}]
    """.trimIndent()

    private val latestPostJson = """
        [{"id":7633664,"date":"2026-07-17T00:00:00","ck_comic_byline":"Liniers",
          "assets":{"featured":{"url":"https://wp.comicskingdom.com/full.jpg"},
                    "single":{"url":"https://wp.comicskingdom.com/single.jpg"}}}]
    """.trimIndent()

    private val chapterPage1Json = """
        [{"id":7633664,"date":"2026-07-17T00:00:00","ck_formatted_date":"Fri, July 17, 2026"},
         {"id":7529714,"date":"2026-04-09T00:00:00","ck_formatted_date":"Thu, April 9, 2026"}]
    """.trimIndent()

    private val singlePostJson = """
        {"assets":{"featured":{"url":"https://wp.comicskingdom.com/full.jpg"},
                   "single":{"url":"https://wp.comicskingdom.com/single.jpg"}}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/wp-json/wp/v2/ck_comic/7633664") -> MockResponse().setBody(singlePostJson)
                    path.contains("ck_feature_taxonomy=706") && path.contains("per_page=1&") ->
                        MockResponse().setBody(latestPostJson)
                    path.contains("ck_feature_taxonomy=706") && path.contains("per_page=100") && path.contains("page=1") ->
                        MockResponse().setBody(chapterPage1Json)
                    path.contains("ck_feature_taxonomy=706") && path.contains("page=2") ->
                        MockResponse().setBody("[]")
                    path.startsWith("/wp-json/wp/v2/ck_feature_taxonomy") && path.contains("search=") ->
                        MockResponse().setBody(featuresJson)
                    path.startsWith("/wp-json/wp/v2/ck_feature_taxonomy") -> MockResponse().setBody(featuresJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ComicsKingdomSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular maps feature taxonomy terms to SManga with term id as url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Macanudo (Spanish)", result[0].title)
        assertEquals("/706", result[0].url)
    }

    @Test
    fun `search maps feature taxonomy terms the same way as getPopular`() = runTest {
        val result = source.search("Macanudo", 1)
        assertEquals(1, result.size)
        assertEquals("/706", result[0].url)
    }

    @Test
    fun `getMangaDetails fetches the newest post for cover and byline`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("https://wp.comicskingdom.com/single.jpg", details.coverUrl)
        assertEquals("Liniers", details.author)
    }

    @Test
    fun `getChapterList reverses newest-first API order so chapter 1 is the oldest strip`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertEquals("Thu, April 9, 2026", chapters[0].name)
        assertEquals(2f, chapters[1].chapterNumber)
        assertEquals("Fri, July 17, 2026", chapters[1].name)
        assertEquals("7633664", chapters[1].url)
    }

    @Test
    fun `getPageList fetches the single post by id and returns its strip image as one page`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(1, pages.size)
        assertEquals("https://wp.comicskingdom.com/single.jpg", pages[0].url)
    }

    @Test
    fun `malformed response returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        server.start()
        val emptySource = ComicsKingdomSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

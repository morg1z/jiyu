package com.haise.jiyu.source.yaoimangaonline

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
 * YaoiMangaOnline jede cele pres WP REST API ("/wp-json/wp/v2/posts") - HTML
 * frontend je za Cloudflare. Tagy = WP post_tag termy, filtrovani pres
 * "&tags={numericId}", kapitoly = "<!--nextpage-->" segmenty post contentu.
 */
class YaoiMangaOnlineSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: YaoiMangaOnlineSource

    private val tagsJson = """[{"id":5,"name":"School Life"},{"id":9,"name":"Yaoi"}]"""

    // Jeden WP post: intro + 2 kapitoly oddelene <!--nextpage-->; druha kapitola
    // obsahuje i reklamni obrazek (ad.jpg nesedi vzoru "NN-XX.ext", filtruje se).
    private val postJson = """
        [{"slug":"test-post","title":{"rendered":"Test Post"},"modified_gmt":"2026-01-01T00:00:00",
        "content":{"rendered":"<p>Intro description.</p><!--nextpage--><p><img src=\"https://cdn.example.com/up/01-1.webp\"/></p><!--nextpage--><p><img src=\"https://cdn.example.com/up/02-1.webp\"/><img src=\"https://cdn.example.com/rec/ad.jpg\"/></p>"},
        "_embedded":{
          "wp:featuredmedia":[{"source_url":"https://cdn.example.com/cover.jpg"}],
          "wp:term":[
            [{"taxonomy":"category","name":"Ongoing"}],
            [{"taxonomy":"post_tag","name":"Yaoi"},{"taxonomy":"post_tag","name":"School Life"}],
            [{"taxonomy":"mangaka","name":"Author X"}]
          ]}}]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/wp-json/wp/v2/tags") -> MockResponse().setBody(
                        if (path.contains("page=1")) tagsJson else "[]"
                    )
                    path.startsWith("/wp-json/wp/v2/posts") -> MockResponse().setBody(postJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = YaoiMangaOnlineSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses wp posts with embedded cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Post", result[0].title)
        assertEquals("/test-post/", result[0].url)
        assertEquals("https://cdn.example.com/cover.jpg", result[0].coverUrl)
    }

    @Test
    fun `getAvailableTags reads the wp tags endpoint`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("5", "9"), tags.map { it.id })
        assertEquals(listOf("School Life", "Yaoi"), tags.map { it.label })
    }

    @Test
    fun `getPopular with a selected genre appends the tags parameter`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("5")))
        assertEquals(1, result.size)
        assertEquals("Test Post", result[0].title)
    }

    @Test
    fun `search goes through the wp search parameter`() = runTest {
        val result = source.search("test", 1)
        assertEquals(1, result.size)
        assertEquals("Test Post", result[0].title)
    }

    @Test
    fun `getMangaDetails reads intro segment, mangaka and post_tag terms`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Intro description.", details.description)
        assertEquals("Author X", details.author)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Yaoi", "School Life"), details.genres)
    }

    @Test
    fun `getChapterList creates one chapter per nextpage segment`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals("test-post|1", chapters[0].url)
        assertEquals(2f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList keeps only NN-XX page images, not recommendation ads`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(1, pages.size)
        assertTrue(pages[0].url.endsWith("02-1.webp"))
    }

    @Test
    fun `malformed json returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = YaoiMangaOnlineSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
        assertTrue(emptySource.getAvailableTags().isEmpty())
    }
}

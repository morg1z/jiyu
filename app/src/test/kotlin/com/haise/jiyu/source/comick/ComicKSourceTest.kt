package com.haise.jiyu.source.comick

import com.haise.jiyu.settings.FakeDataStore
import com.haise.jiyu.settings.SettingsRepository
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

/** ComicK pouziva verejne REST JSON API (viz komentar v ComicKSource.kt). */
class ComicKSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ComicKSource

    private val searchArrayJson = """
        [ {"title": "Test Series", "slug": "test-series", "md_covers": [{"b2key": "cover.jpg"}]} ]
    """.trimIndent()

    private val mangaDetailJson = """
        {"comic": {"hid": "abcd", "desc": "A summary.", "status": 1, "year": 2020}, "authors": [{"name": "Jane"}], "genres": [{"name": "Action"}]}
    """.trimIndent()

    private val chaptersJson = """
        {"chapters": [{"hid": "ch1", "chap": "1", "vol": null, "title": "", "created_at": "2026-01-01T00:00:00Z"}]}
    """.trimIndent()

    private val pagesJson = """
        {"chapter": {"md_images": [{"b2key": "test/1/01.jpg"}]}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v1.0/search") -> MockResponse().setBody(searchArrayJson)
                    path == "/comic/test-series" -> MockResponse().setBody(mangaDetailJson)
                    path.startsWith("/comic/abcd/chapters") -> MockResponse().setBody(chaptersJson)
                    path.startsWith("/chapter/ch1") -> MockResponse().setBody(pagesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val settings = SettingsRepository(FakeDataStore())
        source = ComicKSource(redirectingClient(server), settings)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover from md_covers`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertTrue(result[0].coverUrl!!.endsWith("/cover.jpg"))
    }

    @Test
    fun `getMangaDetails maps numeric status to Czech label`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Vychází", details.status)
        assertEquals("Jane", details.author)
        assertEquals(2020, details.year)
    }

    @Test
    fun `getChapterList resolves hid first, then pages`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertTrue(pages[0].url.endsWith("/test/1/01.jpg"))
    }

    @Test
    fun `server error throws (no try-catch around ComicK network calls)`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        server.start()
        val settings = SettingsRepository(FakeDataStore())
        val failingSource = ComicKSource(redirectingClient(server), settings)

        var threw = false
        try { failingSource.getPopular(1) } catch (_: Exception) { threw = true }
        assertTrue(threw)
    }
}

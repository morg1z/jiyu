package com.haise.jiyu.source.mangadex

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

/**
 * MangaDex pouziva verejne REST JSON API (zadny HTML scraping) - viz komentar
 * v MangaDexSource.kt, ze je to referencni priklad zdroje.
 */
class MangaDexSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaDexSource

    private val mangaListJson = """
        {
          "data": [
            {
              "id": "abc-123",
              "attributes": {
                "title": {"en": "One Piece"},
                "description": {"en": "A pirate adventure."},
                "status": "ongoing",
                "year": 1997,
                "originalLanguage": "ja",
                "tags": [
                  {"attributes": {"group": "genre", "name": {"en": "Action"}}}
                ]
              },
              "relationships": [
                {"type": "cover_art", "attributes": {"fileName": "cover.jpg"}},
                {"type": "author", "attributes": {"name": "Oda"}}
              ]
            }
          ]
        }
    """.trimIndent()

    private val chapterFeedJson = """
        {
          "data": [
            {
              "id": "ch-1",
              "attributes": {"chapter": "1092", "title": "", "publishAt": "2026-07-01T00:00:00Z"},
              "relationships": [{"type": "scanlation_group", "attributes": {"name": "TCB"}}]
            }
          ],
          "total": 1
        }
    """.trimIndent()

    private val atHomeJson = """
        {
          "baseUrl": "https://uploads.example.com",
          "chapter": {"hash": "hash123", "data": ["01.png", "02.png"]}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga?title=") || path.startsWith("/manga?limit=") -> MockResponse().setBody(mangaListJson)
                    path.contains("/feed") -> MockResponse().setBody(chapterFeedJson)
                    path.startsWith("/at-home/server/") -> MockResponse().setBody(atHomeJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val settings = SettingsRepository(FakeDataStore())
        source = MangaDexSource(redirectingClient(server), settings)
        // Přepiš apiBase přes reflexi není potřeba - redirectingClient přesměruje host/port bez ohledu na apiBase string.
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, cover, genre and author from JSON`() = runTest {
        val result = source.getPopular(1)

        assertEquals(1, result.size)
        assertEquals("One Piece", result[0].title)
        assertEquals("Oda", result[0].author)
        assertTrue(result[0].coverUrl!!.endsWith("/abc-123/cover.jpg.256.jpg"))
        assertEquals(listOf("Action"), result[0].genres)
        assertEquals("MANGA", result[0].contentType)
    }

    @Test
    fun `getChapterList parses chapter number and scanlation group`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)

        assertEquals(1, chapters.size)
        assertEquals(1092f, chapters[0].chapterNumber)
        assertEquals("TCB", chapters[0].scanlationGroup)
    }

    @Test
    fun `getPageList builds image URLs from at-home server response`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(2, pages.size)
        assertEquals("https://uploads.example.com/data/hash123/01.png", pages[0].url)
    }

    @Test
    fun `malformed JSON body surfaces as failure rather than silently returning wrong data`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500).setBody("Internal Error")
        }
        server.start()
        val settings = SettingsRepository(FakeDataStore())
        val failingSource = MangaDexSource(redirectingClient(server), settings)

        var threw = false
        try {
            failingSource.getPopular(1)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("MangaDex getPopular is expected to throw on API error (no try/catch in source)", threw)
    }
}

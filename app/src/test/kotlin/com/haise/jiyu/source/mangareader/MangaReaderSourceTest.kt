package com.haise.jiyu.source.mangareader

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

class MangaReaderSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaReaderSource

    private val popularHtml = """
        <html><body>
        <div class="manga-list">
          <div class="manga-item"><a href="/read/jujutsu-kaisen-abc123" title="Jujutsu Kaisen"><img data-src="https://cdn.example.com/jjk.jpg" /></a></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h2 class="manga-name">Jujutsu Kaisen</h2>
        <div class="manga-poster"><img src="https://cdn.example.com/jjk.jpg" /></div>
        <div class="description">Cursed energy battles.</div>
        </body></html>
    """.trimIndent()

    private val chapterAjaxJson = """
        {"html": "<li><a href='/read/jujutsu-kaisen-abc123/chapter-1'><span class='name'>Chapter 1</span></a></li>"}
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div class="reading-content"><img data-src="https://cdn.example.com/jjk/1/01.jpg" /></div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/home") -> MockResponse().setBody(popularHtml)
                    path.startsWith("/ajax/manga/reading-list/") -> MockResponse().setBody(chapterAjaxJson)
                    path == "/read/jujutsu-kaisen-abc123" -> MockResponse().setBody(detailHtml)
                    path == "/read/jujutsu-kaisen-abc123/chapter-1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaReaderSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title attribute and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Jujutsu Kaisen", result[0].title)
        assertEquals("https://cdn.example.com/jjk.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails extracts description`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Cursed energy battles.", details.description)
    }

    @Test
    fun `getChapterList parses AJAX JSON wrapper and getPageList extracts images`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].name)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/jjk/1/01.jpg", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaReaderSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

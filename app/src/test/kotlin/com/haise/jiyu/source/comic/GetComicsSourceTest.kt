package com.haise.jiyu.source.comic

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GetComicsSource prepisuje getChapterList/getPageList (zdroj je jen odkaz na
 * stazeni CBR/PDF, ne strankovany reader) - proto ma vlastni test mimo
 * sdileny ComicSiteSourceTest engine test.
 */
class GetComicsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: GetComicsSource

    private val listHtml = """
        <html><body>
        <article><h1 class="post-title"><a href="/test-comic-issue-1">Test Comic Issue 1</a></h1><img class="wp-post-image" src="https://cdn.example.com/test.jpg" /></article>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <img class="wp-post-image" src="https://cdn.example.com/test-full.jpg" />
        <div class="post-content"><p>A description.</p></div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse().setBody(listHtml)
                    path == "/test-comic-issue-1" -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = GetComicsSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover from article listing`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Comic Issue 1", result[0].title)
    }

    @Test
    fun `getChapterList returns a single synthetic Download chapter without network call`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Download", chapters[0].name)
        assertEquals(manga.url, chapters[0].url)
    }

    @Test
    fun `getPageList fetches the manga page and extracts a single cover image`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/test-full.jpg", pages[0].url)
    }
}

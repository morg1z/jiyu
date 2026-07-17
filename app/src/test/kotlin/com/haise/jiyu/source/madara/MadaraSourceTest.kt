package com.haise.jiyu.source.madara

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
 * Overuje MadaraSource proti staticke fixture s bezným Madara markupem
 * (bez zavislosti na zivem webu - viz CLAUDE.md TODO o neoverenem parseru).
 */
class MadaraSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MadaraSource

    private val listHtml = """
        <html><body>
        <div class="page-item-detail">
          <a href="/manga/one-piece/" title="One Piece"><img data-src="https://cdn.example.com/one-piece.jpg" /></a>
        </div>
        <div class="page-item-detail">
          <a href="/manga/naruto/" title="Naruto"><img src="https://cdn.example.com/naruto.jpg" /></a>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="summary__content"><p>A great pirate adventure.</p></div>
        <div class="post-status"><div class="summary-content">Vychází</div></div>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <ul>
          <li class="wp-manga-chapter"><a href="/manga/one-piece/chapter-1092/">Chapter 1092</a><span class="chapter-release-date"><i>July 1, 2026</i></span></li>
          <li class="wp-manga-chapter"><a href="/manga/one-piece/chapter-1091/">Chapter 1091</a><span class="chapter-release-date"><i>June 24, 2026</i></span></li>
        </ul>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <div class="reading-content">
          <img data-src="https://cdn.example.com/one-piece/1092/01.jpg" />
          <img data-src="https://cdn.example.com/one-piece/1092/02.jpg" />
        </div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.contains("/ajax/chapters/") -> MockResponse().setBody(chaptersHtml)
                    path.startsWith("/manga/page/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/page/") && path.contains("post_type=wp-manga") -> MockResponse().setBody(listHtml)
                    path == "/manga/one-piece/" -> MockResponse().setBody(detailHtml)
                    path == "/manga/one-piece/chapter-1092/" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MadaraSource(
            id = "madara:test",
            name = "Test Madara",
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, url and cover from standard Madara list markup`() = runTest {
        val result = source.getPopular(1)

        assertEquals(2, result.size)
        assertEquals("One Piece", result[0].title)
        assertEquals("https://cdn.example.com/one-piece.jpg", result[0].coverUrl)
        assertTrue(result[0].url.endsWith("/manga/one-piece/"))
        // druhá položka nemá title atribut, spoléhá se na text odkazu + src fallback
        assertEquals("Naruto", result[1].title)
        assertEquals("https://cdn.example.com/naruto.jpg", result[1].coverUrl)
    }

    @Test
    fun `getMangaDetails extracts description and status`() = runTest {
        val manga = source.getPopular(1).first()

        val details = source.getMangaDetails(manga)

        assertEquals("A great pirate adventure.", details.description)
        assertEquals("Vychází", details.status)
    }

    @Test
    fun `getChapterList parses chapters from the AJAX endpoint`() = runTest {
        val manga = source.getPopular(1).first()

        val chapters = source.getChapterList(manga)

        assertEquals(2, chapters.size)
        assertEquals(1092f, chapters[0].chapterNumber)
        assertTrue(chapters[0].url.endsWith("/manga/one-piece/chapter-1092/"))
    }

    @Test
    fun `getPageList extracts image URLs from reading-content`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()

        val pages = source.getPageList(chapter)

        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/one-piece/1092/01.jpg", pages[0].url)
        assertEquals("https://cdn.example.com/one-piece/1092/02.jpg", pages[1].url)
    }

    @Test
    fun `custom popularUrl and searchUrl override the default WordPress permalink paths`() = runTest {
        val customSource = MadaraSource(
            id = "madara:custom",
            name = "Custom Madara",
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            popularUrl = { root, page, _ -> "$root/genre/manga?page=$page" },
            searchUrl = { root, query, page -> "$root/search?s=$query&page=$page" },
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/genre/manga") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search") -> MockResponse().setBody(listHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        assertEquals(2, customSource.getPopular(1).size)
        assertEquals(2, customSource.search("one piece", 1).size)
    }
}

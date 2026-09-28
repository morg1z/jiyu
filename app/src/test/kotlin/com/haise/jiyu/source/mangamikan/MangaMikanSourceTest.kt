package com.haise.jiyu.source.mangamikan

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

class MangaMikanSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaMikanSource

    private val browseHtml = """
        <html><body>
        <article class="collection-book manga-card--default">
            <a class="collection-book__cover" href="https://mangamikan.com/title/test-series">
                <img src="/uploads/covers/1.webp" alt="Cover of Test Series">
            </a>
            <h3><a href="https://mangamikan.com/title/test-series">Test Series</a></h3>
        </article>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <p class="collection-synopsis">A test summary.</p>
        <div class="collection-book-tags"><a href="https://mangamikan.com/genre/action">Action</a><a href="https://mangamikan.com/genre/fantasy">Fantasy</a></div>
        <p class="collection-creator">By Jane Doe</p>
        <div class="chapter-arsenal__list">
            <a href="https://mangamikan.com/read/2" data-chapter-row data-created-at="1620922186"><span class="chapter-arsenal__name"><strong>Chapter 2</strong></span></a>
            <a href="https://mangamikan.com/read/1" data-chapter-row data-created-at="1620922086"><span class="chapter-arsenal__name"><strong>Chapter 1</strong></span></a>
        </div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="/mangas/1/p0001.webp" alt="Page 1" data-page="1">
        <img src="/mangas/1/p0002.webp" alt="Page 2" data-page="2">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/browse") -> MockResponse().setBody(browseHtml)
                    path == "/title/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/read/1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaMikanSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses collection-book listing`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://mangamikan.com/title/test-series", result[0].url)
        assertEquals("https://mangamikan.com/uploads/covers/1.webp", result[0].coverUrl)
    }

    @Test
    fun `search reuses collection-book parsing`() = runTest {
        val result = source.search("test")
        assertEquals(1, result.size)
    }

    @Test
    fun `getMangaDetails parses creator and genre tag list`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
    }

    @Test
    fun `getChapterList parses data-chapter-row links`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList reads direct data-page image URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://mangamikan.com/mangas/1/p0001.webp", pages[0].url)
        assertEquals("https://mangamikan.com/mangas/1/p0002.webp", pages[1].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = MangaMikanSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

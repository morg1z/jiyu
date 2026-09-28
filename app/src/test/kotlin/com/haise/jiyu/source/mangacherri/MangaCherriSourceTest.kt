package com.haise.jiyu.source.mangacherri

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

class MangaCherriSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaCherriSource

    private val homeHtml = """
        <html><body>
        <article class="cherri-list-card">
            <a class="cherri-list-card__cover" href="https://mangacherri.com/title/test-series">
                <img src="/uploads/covers/1.webp" alt="Cover of Test Series" data-cover-image>
            </a>
            <div class="cherri-list-card__body">
                <h2><a href="https://mangacherri.com/title/test-series">Test Series</a></h2>
            </div>
        </article>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="cherri-detail__copy">
            <p class="eyebrow">ROSE LIBRARY · ONGOING</p>
            <nav class="cherri-detail__chips"><a href="https://mangacherri.com/genre/comedy">Comedy</a><a href="https://mangacherri.com/genre/drama">Drama</a></nav>
            <div class="cherri-detail__description">A test summary.</div>
            <p><strong>Jane Doe</strong></p>
        </div>
        <div class="cherri-chapter-list">
            <a class="cherri-chapter-row" href="https://mangacherri.com/read/2" data-chapter-row data-created-at="1620922186"><span><strong>Chapter 2</strong></span></a>
            <a class="cherri-chapter-row" href="https://mangacherri.com/read/1" data-chapter-row data-created-at="1620922086"><span><strong>Chapter 1</strong></span></a>
        </div>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img src="/mangas/1/p0001_abc.webp" alt="page 1" data-page="1">
        <img src="/mangas/1/p0002_def.webp" alt="page 2" data-page="2">
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/manga") -> MockResponse().setBody(homeHtml)
                    path == "/title/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/read/1" -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaCherriSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses cherri-list-card cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://mangacherri.com/title/test-series", result[0].url)
        assertEquals("https://mangacherri.com/uploads/covers/1.webp", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails parses status, author and genre chips`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("ongoing", details.status)
        assertEquals("Jane Doe", details.author)
        assertEquals(listOf("Comedy", "Drama"), details.genres)
    }

    @Test
    fun `getChapterList reads cherri-chapter-row entries as source paths`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals("/read/2", chapters[0].url)
        assertEquals("/read/1", chapters[1].url)
    }

    @Test
    fun `getPageList reads data-page image URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(2, pages.size)
        assertEquals("https://mangacherri.com/mangas/1/p0001_abc.webp", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = MangaCherriSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

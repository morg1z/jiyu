package com.haise.jiyu.source.dankemoe

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

class DankeMoeSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: DankeMoeSource

    private val homeHtml = """
        <html><body><script>
        const series_data = [{"html": `<div class="card h-100 text-center">
            <div class="embed-responsive embed-responsive-7by10">
                <a href="/read/manga/test-series/">
                    <img class="card-img-top embed-responsive-item lazy" data-src="/media/manga/test-series/volume_covers/1/cover.webp" alt="Cover for Test Series">
                </a>
            </div>
        </div>`}];
        </script></body></html>
    """.trimIndent()

    private val seriesJson = """
        {"slug":"test-series","title":"Test Series","description":"A test summary.","author":"Jane Doe","artist":"Jane Doe","groups":{"2":"Test Group"},"cover":"/media/manga/test-series/cover.webp","chapters":{"1":{"volume":"1","title":null,"folder":"0001_abc","is_public":true,"groups":{"2":["01.png","02.png"]},"release_date":{"2":1620922086}},"2":{"volume":"2","title":null,"folder":"0002_def","is_public":true,"groups":{"2":["01.png"]},"release_date":{"2":1620922186}}}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse().setBody(homeHtml)
                    path == "/api/series/test-series/" -> MockResponse().setBody(seriesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = DankeMoeSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular strips Cover for prefix from alt text`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/read/manga/test-series/", result[0].url)
    }

    @Test
    fun `getMangaDetails parses the single series API response`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Test Series", details.title)
        assertEquals("Jane Doe", details.author)
        assertEquals("A test summary.", details.description)
    }

    @Test
    fun `getChapterList reads both volume entries from the chapters object`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertTrue(chapters.any { it.chapterNumber == 1f })
        assertTrue(chapters.any { it.chapterNumber == 2f })
    }

    @Test
    fun `getPageList builds media URLs from folder, group and filenames`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val chapter1 = chapters.first { it.chapterNumber == 1f }
        val pages = source.getPageList(chapter1)
        assertEquals(2, pages.size)
        assertEquals("https://danke.moe/media/manga/test-series/chapters/0001_abc/2/01.png", pages[0].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = DankeMoeSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

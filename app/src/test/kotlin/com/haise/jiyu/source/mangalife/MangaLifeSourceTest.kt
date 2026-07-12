package com.haise.jiyu.source.mangalife

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
 * MangaLife (jako MangaSee) neni HTML-list scraping, ale JS promenna
 * "vm.Directory = [...]" / "vm.Chapters = [...]" vlozena primo do stranky - viz zdroj.
 */
class MangaLifeSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaLifeSource

    private val directoryHtml = """
        <html><script>
        vm.Directory = [{"i": "test-manga", "s": "Test Manga"}];
        </script></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><script>
        vm.Chapters = [{"Chapter": "10"}];
        </script></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><script>
        vm.CurChapter = {"Chapter": "10", "Page": "2"};
        vm.CurPathName = "cdn.example.com/path";
        </script></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search/") -> MockResponse().setBody(directoryHtml)
                    path == "/manga/test-manga" -> MockResponse().setBody(chaptersHtml)
                    path.startsWith("/read-online/test-manga-chapter-1.html") -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaLifeSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses vm-Directory JS array`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Manga", result[0].title)
        assertEquals("/manga/test-manga", result[0].url)
    }

    @Test
    fun `getChapterList parses vm-Chapters and builds read-online URL`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
        assertTrue(chapters[0].url.endsWith("test-manga-chapter-1.html"))
    }

    @Test
    fun `getPageList builds per-page URLs from vm-CurChapter and vm-CurPathName`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/path/manga/test-manga/1.0-001.png", pages[0].url)
        assertEquals("https://cdn.example.com/path/manga/test-manga/1.0-002.png", pages[1].url)
    }

    @Test
    fun `missing vm-Directory returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaLifeSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

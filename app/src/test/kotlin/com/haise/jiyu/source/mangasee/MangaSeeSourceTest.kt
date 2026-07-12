package com.haise.jiyu.source.mangasee

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

/** MangaSee sdili identickou vm.Directory/vm.Chapters JS-embed logiku jako MangaLife. */
class MangaSeeSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaSeeSource

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
        vm.CurChapter = {"Chapter": "10", "Page": "1"};
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
        source = MangaSeeSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses vm-Directory and builds cover from nep-li CDN`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Manga", result[0].title)
        assertEquals("https://cover.nep.li/cover/test-manga.jpg", result[0].coverUrl)
    }

    @Test
    fun `getChapterList and getPageList parse embedded JS state`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/path/manga/test-manga/1.0-001.png", pages[0].url)
    }

    @Test
    fun `search filters vm-Directory by title, falls back gracefully when missing`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaSeeSource(redirectingClient(server))
        assertTrue(emptySource.search("test", 1).isEmpty())
    }
}

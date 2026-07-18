package com.haise.jiyu.source.hachirumi

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

class HachirumiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HachirumiSource

    private val listHtml = """
        <html><body>
        <div class="card">
            <img class="card-img-top" data-src="/media/manga/test-series/cover.jpg"/>
            <div class="card-body"><h7 class="card-title"><a href="/read/manga/test-series/">Test Series</a></h7></div>
        </div>
        </body></html>
    """.trimIndent()

    private val seriesJson = """
        {"slug":"test-series","title":"Test Series","description":"<p>A summary.</p>","author":"Some Author",
         "artist":"Some Artist","cover":"/media/manga/test-series/cover.jpg",
         "chapters":{"2":{"volume":"1","title":"Second","folder":"0002_abc","groups":{"1":["1.png","2.png"]},"release_date":{"1":1700000000}},
                     "1":{"volume":"1","title":"First","folder":"0001_xyz","groups":{"1":["1.png"]},"release_date":{"1":1600000000}}}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse().setBody(listHtml)
                    path == "/api/series/test-series/" -> MockResponse().setBody(seriesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HachirumiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title and cover`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
    }

    @Test
    fun `search filters by title, second page is empty`() = runTest {
        assertEquals(1, source.search("test", 1).size)
        assertTrue(source.search("nomatch", 1).isEmpty())
        assertTrue(source.search("test", 2).isEmpty())
    }

    @Test
    fun `full flow parses details, chapters sorted newest-first and pages`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Some Author", details.author)
        assertEquals("Some Artist", details.artist)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://hachirumi.com/media/manga/test-series/chapters/0002_abc/1/1.png", pages[0].url)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = HachirumiSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

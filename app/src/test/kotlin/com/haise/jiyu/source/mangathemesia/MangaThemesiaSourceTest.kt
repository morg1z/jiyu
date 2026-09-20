package com.haise.jiyu.source.mangathemesia

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.redirectingClient
import com.haise.jiyu.util.SourceParseException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class MangaThemesiaSourceTest {

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()
    private var overrideBody: String? = null

    private val listHtml = """
        <html><body><div class="listupd">
        <div class="bsx">
          <a href="https://site.test/manga/test-series/" title="Test Series">
            <img src="data:image/gif;base64,AAA" data-src="https://cdn.example.com/test.jpg" />
          </a>
          <div class="tt">Test Series</div>
        </div>
        <div class="bsx">
          <a href="/manga/second/"><img src="/covers/second.jpg" /></a>
          <div class="tt">Second</div>
        </div>
        </div></body></html>
    """.trimIndent()

    private val imptdtDetail = """
        <html><body>
        <h1 class="entry-title">Test Series</h1>
        <div class="tsinfo"><div class="imptdt">Status <i>Ongoing</i></div></div>
        <span class="mgen"><a href="#">Action</a><a href="#">Romance</a></span>
        <div class="entry-content-single"><p>A test summary.</p></div>
        <div class="eplister"><ul>
          <li data-num="2"><a href="https://site.test/test-series-chapter-2/"><span class="chapternum">Chapter 2</span><span class="chapterdate">July 1, 2026</span></a></li>
          <li data-num="1.5"><a href="/test-series-chapter-1-5/"><span class="chapternum">Chapter 1.5</span><span class="chapterdate">garbage</span></a></li>
          <li><a href="/test-series-chapter-1/"><span class="chapternum">Chapter 1</span></a></li>
        </ul></div>
        </body></html>
    """.trimIndent()

    private val tableDetail = """
        <html><body>
        <h1 class="entry-title">Test Series</h1>
        <table>
            <tr><td>Status</td><td>Ongoing</td></tr>
            <tr><td>Type</td><td>Manhwa</td></tr>
        </table>
        <a href="https://site.test/genres/action/">Action</a>
        <a href="https://site.test/genres/romance/">Romance</a>
        <div class="entry-content-single" itemprop="description"><p>A test summary.</p></div>
        <div id="chapterlist"><ul></ul></div>
        </body></html>
    """.trimIndent()

    private val tsReaderPages = """
        <html><body><div id="readerarea"></div><script>ts_reader.run({"post_id":1,"sources":[{"source":"S1","images":["https:\/\/cdn.example.com\/1\/01.jpg","https:\/\/cdn.example.com\/1\/02.jpg"]}]});</script></body></html>
    """.trimIndent()

    private val readerAreaPages = """
        <html><body><div id="readerarea"><img data-src="/p/1.jpg" src="data:image/gif;base64,AAA"><img src="https://cdn.example.com/p/2.jpg"></div></body></html>
    """.trimIndent()

    private var detailHtml = imptdtDetail
    private var pagesHtml = tsReaderPages

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                overrideBody?.let { return MockResponse().setBody(it) }
                return when {
                    path == "/manga/test-series/" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/manga/") || path.startsWith("/genres/") || path.contains("?s=") -> MockResponse().setBody(listHtml)
                    path.contains("-chapter-") -> MockResponse().setBody(pagesHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source(
        contentType: String = "MANGA",
        pathPagination: Boolean = false,
        genreArchive: Boolean = false,
    ) = MangaThemesiaSource(
        "test", "Test", "https://site.test", redirectingClient(server),
        contentTypeOverride = contentType, pathPagination = pathPagination, genreArchive = genreArchive,
    )

    @Test
    fun `getPopular parses cards, resolves lazy covers and relative urls`() = runTest {
        val result = source(contentType = "MANHWA").getPopular(1)
        assertEquals(2, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://cdn.example.com/test.jpg", result[0].coverUrl)
        assertEquals("MANHWA", result[0].contentType)
        assertEquals("https://site.test/manga/second/", result[1].url)
        assertEquals("Second", result[1].title)
        assertEquals("https://site.test/covers/second.jpg", result[1].coverUrl)
    }

    @Test
    fun `popular and latest ask for different order`() = runTest {
        val s = source()
        s.getPopular(2, MangaFilter(sortBy = "popular"))
        s.getPopular(2, MangaFilter(sortBy = "latest"))
        assertEquals("/manga/?order=popular&page=2", paths[0])
        assertEquals("/manga/?order=update&page=2", paths[1])
    }

    @Test
    fun `path pagination and genre archive urls`() = runTest {
        source(pathPagination = true).getPopular(3, MangaFilter(sortBy = "latest"))
        assertEquals("/manga/page/3/?order=update", paths.last())
        source(pathPagination = true).getPopular(1, MangaFilter(genres = listOf("14", "15")))
        assertEquals("/manga/?genre%5B%5D=14&genre%5B%5D=15", paths.last())
        source(genreArchive = true).getPopular(2, MangaFilter(genres = listOf("action")))
        assertEquals("/genres/action/page/2/", paths.last())
        source().search("one piece", 2)
        assertEquals("/page/2/?s=one+piece", paths.last())
    }

    @Test
    fun `details from imptdt card`() = runTest {
        val s = source()
        val details = s.getMangaDetails(s.getPopular(1).first())
        assertEquals("ongoing", details.status)
        assertEquals(listOf("Action", "Romance"), details.genres)
        assertEquals("A test summary.", details.description)
        assertEquals("MANGA", details.contentType)
    }

    @Test
    fun `details from table card infer type and fall back to genre links`() = runTest {
        detailHtml = tableDetail
        val s = source()
        val details = s.getMangaDetails(s.getPopular(1).first())
        assertEquals("ongoing", details.status)
        assertEquals("MANHWA", details.contentType)
        assertEquals(listOf("Action", "Romance"), details.genres)
    }

    @Test
    fun `chapters keep absolute urls, numbers and parsed dates`() = runTest {
        val s = source()
        val chapters = s.getChapterList(s.getPopular(1).first())
        assertEquals(3, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals("https://site.test/test-series-chapter-2/", chapters[0].url)
        assertEquals(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), chapters[0].dateUpload)
        assertEquals(1.5f, chapters[1].chapterNumber)
        assertEquals("https://site.test/test-series-chapter-1-5/", chapters[1].url)
        assertEquals("unparseable date is 0, not now", 0L, chapters[1].dateUpload)
        assertEquals("number falls back to chapter name", 1f, chapters[2].chapterNumber)
    }

    @Test
    fun `an existing but empty chapter list is empty, not an error`() = runTest {
        detailHtml = tableDetail
        val s = source()
        assertTrue(s.getChapterList(s.getPopular(1).first()).isEmpty())
    }

    @Test
    fun `pages from ts_reader json`() = runTest {
        val s = source()
        val chapter = s.getChapterList(s.getPopular(1).first()).first()
        val pages = s.getPageList(chapter)
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/1/01.jpg", pages[0].url)
    }

    @Test
    fun `pages fall back to server rendered reader images`() = runTest {
        pagesHtml = readerAreaPages
        val s = source()
        val chapter = s.getChapterList(s.getPopular(1).first()).first()
        val pages = s.getPageList(chapter)
        assertEquals(listOf("https://site.test/p/1.jpg", "https://cdn.example.com/p/2.jpg"), pages.map { it.url })
    }

    @Test
    fun `tags come from genre checkboxes with labels`() = runTest {
        overrideBody = """
            <html><body>
            <input type="checkbox" name="genre[]" id="g1" value="14"><label for="g1">Action</label>
            <ul><li><input type="checkbox" name="genre[]" value="15"><label>Comedy</label></li></ul>
            </body></html>
        """.trimIndent()
        val tags = source().getAvailableTags()
        assertEquals(listOf("14" to "Action", "15" to "Comedy"), tags.map { it.id to it.label })
    }

    @Test
    fun `changed html is a parse error instead of a silent empty list`() {
        overrideBody = "<html><body><div>redesigned</div></body></html>"
        val s = source()
        assertThrows(SourceParseException::class.java) { runBlocking { s.getPopular(1) } }
        assertThrows(SourceParseException::class.java) {
            runBlocking { s.getChapterList(SManga("test", "/manga/x/", "X", null)) }
        }
        assertThrows(SourceParseException::class.java) {
            runBlocking { s.getPageList(SChapter("test", "/manga/x/", "/x-chapter-1/", "1", 1f, 0L)) }
        }
        assertThrows(SourceParseException::class.java) { runBlocking { s.getAvailableTags() } }
    }

    @Test
    fun `search without matches and a filtered genre without results stay empty`() = runTest {
        overrideBody = "<html><body><div class=\"listupd\"></div></body></html>"
        val s = source()
        assertTrue(s.search("nothing", 1).isEmpty())
        assertTrue(s.getPopular(1, MangaFilter(genres = listOf("99"))).isEmpty())
        assertTrue(s.getPopular(1).isEmpty())
    }

    @Test
    fun `http errors propagate`() {
        val s = source()
        assertThrows(java.io.IOException::class.java) {
            runBlocking { s.getPageList(SChapter("test", "/manga/x/", "/nope/", "1", 1f, 0L)) }
        }
    }
}

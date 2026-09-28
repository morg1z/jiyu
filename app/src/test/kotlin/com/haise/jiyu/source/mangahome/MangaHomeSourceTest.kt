package com.haise.jiyu.source.mangahome

import com.haise.jiyu.source.SManga
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

class MangaHomeSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaHomeSource

    private val listHtml = """
        <html><body>
        <a class="post-cover" title="Test Series" href="/manga/test-series"><img src="//cdn.example.com/test.jpg"/></a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Test Series</h1>
        <p><span>Status:</span> Ongoing<span class="mobile-none">latest</span></p>
        <p>Author(s):</span><a href="/author/x">Some Author</a></p>
        <p>Genre(s):</span><a href="/action">Action</a></p>
        <p class="hide">A summary.</p>
        <ul class="detail-chlist">
        <li><a href="/manga/test-series/c2" name="">Chapter 2</a><span class="time">Jul 18,2026</span></li>
        <li><a href="/manga/test-series/c1" name="">Chapter 1</a><span class="time">Jan 10,2026</span></li>
        </ul>
        </body></html>
    """.trimIndent()

    // Cteci stranka: JS reader - <img id="image"> prazdny, stranky pres
    // chapterfun.ashx?cid=&page= (packer JS s pvalue polem).
    private val readerHtml = """
        <html><body>
        <img id="image" />
        <script>var chapter_id=919333,pageindex=1,imagepage=1,imagecount=2;</script>
        </body></html>
    """.trimIndent()

    // Odpoved chapterfun.ashx = Dean Edwards packer JS (skutecna odpoved
    // z mangahome.com ulozena v test/resources/mangahome_chapterfun.txt;
    // rozbalene obsahuje pix="//zjcdn.../compressed" + pvalue=["/v000.jpg",...]).
    private val chapterfunBody: String get() =
        javaClass.classLoader!!.getResource("mangahome_chapterfun.txt")!!.readText()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/directory/") -> MockResponse().setBody(listHtml)
                    path == "/manga/test-series" -> MockResponse().setBody(detailHtml)
                    path == "/manga/test-series/c2" -> MockResponse().setBody(readerHtml)
                    path.startsWith("/manga/test-series/chapterfun.ashx") -> MockResponse().setBody(chapterfunBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaHomeSource(redirectingClient(server))
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
    fun `full flow parses details, chapters and single-page reader`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)

        // Stranky jsou virtualni chapterfun.ashx URL; skutecny obrazek se
        // resi az v getImageUrl (lazy, jako webovy reader).
        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertTrue(pages[0].url.contains("chapterfun.ashx?cid=919333&page=1"))

        val img = source.getImageUrl(pages[0])
        assertEquals("https://zjcdn.mangahere.org/store/manga/17871/077.4/compressed/v000.jpg", img)
    }

    @Test
    fun `real markup parses chapters from nested spans`() = runTest {
        // Reálný HTML tvar mangahome.com (tsubaki_chou_lonely_planet):
        // li obsahuje a > span.mobile-none + span.pc-none, pak span.vol a span.time.
        server.shutdown()
        server = MockWebServer()
        val realDetail = javaClass.classLoader!!.getResource("tsu_detail.html")!!.readText()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path.orEmpty()) {
                    "/manga/tsubaki_chou_lonely_planet" -> MockResponse().setBody(realDetail)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val realSource = MangaHomeSource(redirectingClient(server))
        val chapters = realSource.getChapterList(SManga(sourceId = "mangahome", url = "/manga/tsubaki_chou_lonely_planet", title = "T", coverUrl = null))
        assertTrue("očekávám kapitoly, dostal ${chapters.size}", chapters.isNotEmpty())
        assertEquals(77.4f, chapters.first().chapterNumber)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaHomeSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

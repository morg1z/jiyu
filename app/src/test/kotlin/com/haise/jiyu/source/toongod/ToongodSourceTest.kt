package com.haise.jiyu.source.toongod

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

class ToongodSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ToongodSource

    // Zjednodusene, ale strukturalne verne realnemu HTML z toongod.cc/webtoons/page/1/
    private val listHtml = """
        <html><body>
        <div class="latest-list flex">
            <div class="latest-item flex">
                <div class="latest-left">
                    <a href="/webtoon/martial-peak/" title="Martial Peak">
                        <img class="img-latest" src="https://toongod.cc/manga/martial-peak-cover.jpg" alt="Martial Peak">
                    </a>
                </div>
                <div class="latest-right">
                    <div class="mm-name">
                        <a href="/webtoon/martial-peak/" title="Martial Peak">
                            <h4 class="title-smaller hide-text">Martial Peak</h4>
                        </a>
                    </div>
                </div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    // Zjednoduseny, ale strukturalne verny detail z toongod.cc/webtoon/martial-peak/
    private val detailHtml = """
        <html><body>
        <div class="main-info-right">
            <div class="innbox">
                <div class="post-content">
                    <h1 class="main-info-title title-bigger">Martial Peak</h1>
                    <ul class="main-info-list">
                        <li><h5>Author(s)</h5><div><a href="/author/momo/">Momo</a></div></li>
                        <li><h5>Artist(s)</h5><div><a href="/artist/pikapi/">Pikapi</a></div></li>
                        <li>
                            <h5>Genres</h5>
                            <div><a href="/genre/action/">Action</a><a href="/genre/adventure/">Adventure</a></div>
                        </li>
                        <li><h5>Type</h5><span>Manhua</span></li>
                    </ul>
                </div>
                <div class="post-status">
                    <uL class="main-info-list">
                        <li><h5>Release</h5><span>2015</span></li>
                        <li><h5>Status</h5><span>Ongoing</span></li>
                    </uL>
                </div>
            </div>
        </div>
        <div class="short-desc box">
            <div class="short-desc-content"><p>The pinnacle of martial arts is solitude and loneliness.</p></div>
        </div>
        <ul class="chapter-list">
            <li>
                <a href="/webtoon/martial-peak/chapter-2/" title="Martial Peak - Chapter 2">
                    <span class="chapter-name">Chapter 2</span>
                    <span class="ct-update">17 Dec 2025</span>
                </a>
            </li>
            <li>
                <a href="/webtoon/martial-peak/chapter-1/" title="Martial Peak - Chapter 1">
                    <span class="chapter-name">Chapter 1</span>
                </a>
            </li>
        </ul>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body>
        <div class="reading-content">
            <img src="https://img03.toongod.cc/chapters001/4264/1/1-2ba.jpg" alt="Martial Peak Chapter 1 - Page 1">
            <img src="https://img03.toongod.cc/chapters001/4264/1/2-2ba.jpg" alt="Martial Peak Chapter 1 - Page 2">
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
                    path.startsWith("/webtoons/page/1/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search/") -> MockResponse().setBody(listHtml)
                    path == "/webtoon/martial-peak/" -> MockResponse().setBody(detailHtml)
                    path == "/webtoon/martial-peak/chapter-1/" -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ToongodSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses latest-item cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Martial Peak", result[0].title)
        assertTrue(result[0].url.endsWith("/webtoon/martial-peak/"))
    }

    @Test
    fun `search reuses the same latest-item parser`() = runTest {
        val result = source.search("Martial", 1)
        assertEquals(1, result.size)
        assertEquals("Martial Peak", result[0].title)
    }

    @Test
    fun `getMangaDetails reads author, artist, genres, status and detects Type override`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Momo", details.author)
        assertEquals("Pikapi", details.artist)
        assertEquals(listOf("Action", "Adventure"), details.genres)
        assertEquals("Ongoing", details.status)
        // Type pole u konkretniho titulu prebiji vychozi kontentType zdroje (MANHWA)
        assertEquals("MANHUA", details.contentType)
        assertTrue(details.description!!.contains("pinnacle"))
    }

    @Test
    fun `getChapterList reads chapter number and leaves the date unknown when the page has none`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertTrue(chapters.any { it.chapterNumber == 2f })
        assertTrue(chapters.any { it.chapterNumber == 1f })
        // "17 Dec 2025" se teď rozpozná (dřív spadlo na "teď"); chybějící datum je 0, ne "teď" - jinak by se
        // kapitola při každém stažení seznamu tvářila jako nová.
        assertEquals(java.time.Instant.parse("2025-12-17T00:00:00Z").toEpochMilli(), chapters.first { it.chapterNumber == 2f }.dateUpload)
        assertEquals(0L, chapters.first { it.chapterNumber == 1f }.dateUpload)
    }

    @Test
    fun `getPageList reads direct src without lazy-load attributes`() = runTest {
        val chapter = source.getChapterList(source.getPopular(1).first()).first { it.chapterNumber == 1f }
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        assertEquals("https://img03.toongod.cc/chapters001/4264/1/1-2ba.jpg", pages[0].url)
    }
}

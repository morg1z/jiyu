package com.haise.jiyu.source.hentaifox

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

class HentaiFoxSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HentaiFoxSource

    // Zjednodusena, ale strukturalne verna kopie realne homepage (curl, 2026-08-09).
    private val homeHtml = """
        <html><body>
        <div class="galleries_overview">
        <div class="lc_galleries"><div class="thumb" data-tags="7 24 26">
            <div class="g_type"><h3 class="g_cat"><a class="t_cat" href="/category/doujinshi/">Doujinshi</a></h3></div>
            <div class="inner_thumb">
                <div class="ribbon ribbon-left ribbon-orange">NEW</div>
                <a href="/gallery/169936/"><img class="lazy" src="data:x" data-src="https://i3.hentaifox.com/004/4108584/thumb.jpg" alt="" /></a>
            </div>
            <div class="caption"><h2 class="g_title"><a href="/gallery/169936/">SALAMANDER SHOCK</a></h2></div>
        </div><div class="thumb" data-tags="1 10 28">
            <div class="g_type"><h3 class="g_cat"><a class="t_cat" href="/category/doujinshi/">Doujinshi</a></h3></div>
            <div class="inner_thumb">
                <a href="/gallery/169933/"><img class="lazy" src="data:x" data-src="https://i3.hentaifox.com/004/4108430/thumb.jpg" alt="" /></a>
            </div>
            <div class="caption"><h2 class="g_title"><a href="/gallery/169933/">unsavory ties</a></h2></div>
        </div></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="info">
            <a class="g_button" href="/g/169933/1/"><i class="fa fa-book"></i> Read Online</a>
            <h1>unsavory ties</h1>
            <ul class="tags"><span class="i_text">Tags:</span><li><a class='tag_btn ' href='/tag/anal/'>anal <span class='t_badge'>33276</span></a></li><li><a class='tag_btn ' href='/tag/yaoi/'>yaoi <span class='t_badge'>15446</span></a></li></ul>
            <ul class="artists"><span class="i_text">Artists:</span><li><a class='tag_btn ' href='/artist/yamada-sakurako/'>yamada sakurako <span class='t_badge'>12</span></a></li></ul>
            <ul class="languages"><span class="i_text">Languages:</span><li><a class='tag_btn' href='/language/english/'>english <span class='t_badge'>156376</span></a></li></ul>
            <ul class="categories"><span class="i_text">Category:</span><li><a class='tag_btn' href='/category/doujinshi/'>doujinshi <span class='t_badge'>117427</span></a></li></ul>
            <span class="i_text pages">Pages: 5</span>
        </div>
        <!-- Realne: "Pages: 70" ale jen ~10 thumb divu (audit 2026-11) -
             proto getPageList cte i_text.pages a ne pocet thumbu. -->
        <div class="gallery_bottom"><div id="append_thumbs">
            <div class="gallery_thumb"><div class="g_thumb"><a href="/g/169933/1/"><img class="lazy preloader" src="data:x" data-src="https://i3.hentaifox.com/004/4108430/1t.jpg" /></a></div></div>
            <div class="gallery_thumb"><div class="g_thumb"><a href="/g/169933/2/"><img class="lazy preloader" src="data:x" data-src="https://i3.hentaifox.com/004/4108430/2t.jpg" /></a></div></div>
            <div class="gallery_thumb"><div class="g_thumb"><a href="/g/169933/3/"><img class="lazy preloader" src="data:x" data-src="https://i3.hentaifox.com/004/4108430/3t.jpg" /></a></div></div>
        </div></div>
        </body></html>
    """.trimIndent()

    private fun readerHtml(n: Int, ext: String) = """
        <html><body>
        <div class="full_image" style="max-width:731px;">
            <a class="next_img"><img id="gimg" class="lazy preloader image_$n" src="data:x" data-src="https://i3.hentaifox.com/004/4108430/$n.$ext" alt="unsavory ties page $n full" /></a>
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
                    path == "/" -> MockResponse().setBody(homeHtml)
                    path == "/gallery/169933/" -> MockResponse().setBody(detailHtml)
                    path == "/g/169933/1/" -> MockResponse().setBody(readerHtml(1, "jpg"))
                    path == "/g/169933/2/" -> MockResponse().setBody(readerHtml(2, "webp"))
                    path == "/g/169933/3/" -> MockResponse().setBody(readerHtml(3, "webp"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HentaiFoxSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses thumb cards from the gallery grid`() = runTest {
        val result = source.getPopular(1)
        assertEquals(2, result.size)
        assertEquals("SALAMANDER SHOCK", result[0].title)
        assertEquals("/gallery/169936/", result[0].url)
        assertEquals("https://i3.hentaifox.com/004/4108584/thumb.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails reads title, tags and artist, strips badge counts`() = runTest {
        val manga = source.getPopular(1).first { it.url == "/gallery/169933/" }
        val details = source.getMangaDetails(manga)
        assertEquals("unsavory ties", details.title)
        assertEquals("yamada sakurako", details.author)
        assertTrue(details.genres.contains("anal"))
        assertTrue(details.genres.contains("yaoi"))
        // ownText() nesmi vratit "anal 33276" (text vnoreneho t_badge span)
        assertTrue(details.genres.none { it.contains("3327") })
    }

    @Test
    fun `getChapterList returns a single synthetic chapter for the whole gallery`() = runTest {
        val manga = source.getPopular(1).first { it.url == "/gallery/169933/" }
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getPageList uses declared Pages count, not rendered thumbnails`() = runTest {
        val manga = source.getPopular(1).first { it.url == "/gallery/169933/" }
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        // Fixture: Pages: 5, ale jen 3 thumb divy - realne chovani webu.
        assertEquals(5, pages.size)
        assertEquals("https://hentaifox.com/g/169933/1/", pages[0].url)
        assertEquals("https://hentaifox.com/g/169933/5/", pages[4].url)
    }

    @Test
    fun `getImageUrl resolves the real per-page extension from the reader page`() = runTest {
        val manga = source.getPopular(1).first { it.url == "/gallery/169933/" }
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        // extenze se lisi stranku od stranky (jpg vs webp) - nejde ji uhodnout z thumbnailu
        assertEquals("https://i3.hentaifox.com/004/4108430/1.jpg", source.getImageUrl(pages[0]))
        assertEquals("https://i3.hentaifox.com/004/4108430/2.webp", source.getImageUrl(pages[1]))
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = HentaiFoxSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

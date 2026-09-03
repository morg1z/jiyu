package com.haise.jiyu.source.imhentai

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

class ImHentaiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ImHentaiSource

    // Zjednodusena, ale strukturalne verna kopie realne homepage (curl, 2026-08-09).
    private val homeHtml = """
        <html><body>
        <div class="thumb" data-tags="38" data-artists="15210">
            <div class="thumbnail">
                <a href="/language/japanese/"><img class="thumb_flag" src="/images/jap.png" /></a><h3 class="gallery_cat"><a class="thumb_cat" href="/category/doujinshi/">Doujinshi</a></h3>
                <a href="/gallery/1714318/"><img class="lazy" src="data:x" data-src="https://m11.imhentai.xxx/032/mtsf5q6a30/thumb.jpg" alt="Onee-chan to Torokeru Kimochi 7" /></a>
                <h2 class="gallery_title"><a href="/gallery/1714318/">(C103) [Candy Club (Sky)] Onee-chan to Torokeru Kimochi 7</a></h2>
            </div>
        </div>
        <div class="thumb" data-tags="17861">
            <div class="thumbnail">
                <a href="/gallery/1714317/"><img class="lazy" src="data:x" data-src="https://m11.imhentai.xxx/032/zrihpv4c67/thumb.jpg" alt="Castorice" /></a>
                <h2 class="gallery_title"><a href="/gallery/1714317/">[BMS07] Castorice [AI Generated]</a></h2>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>(C103) [Candy Club (Sky)] Onee-chan to Torokeru Kimochi 7</h1>
        <ul class="galleries_info">
            <li><span class='tags_text'>Tags:</span> <a class='tag  btn btn-primary' href='/tag/shotacon/'>shotacon<span class='badge'>110953</span></a></li>
            <li><span class='tags_text'>Artists:</span> <a class='tag  btn btn-primary' href='/artist/sky/'>sky<span class='badge'>155</span></a></li>
            <li><span class='tags_text'>Category:</span> <a class='tag  btn btn-primary' href='/category/doujinshi/'>doujinshi<span class='badge'>528218</span></a></li>
            <li class="pages">Pages: 3</li>
        </ul>
        <div id="append_thumbs">
            <div class="gallery_th"><div class="gthumb"><a href="/view/1714318/1/"><img class="lazy" src="data:x" data-src="https://m11.imhentai.xxx/032/mtsf5q6a30/1t.jpg" /></a></div></div>
            <div class="gallery_th"><div class="gthumb"><a href="/view/1714318/2/"><img class="lazy" src="data:x" data-src="https://m11.imhentai.xxx/032/mtsf5q6a30/2t.jpg" /></a></div></div>
            <div class="gallery_th"><div class="gthumb"><a href="/view/1714318/3/"><img class="lazy" src="data:x" data-src="https://m11.imhentai.xxx/032/mtsf5q6a30/3t.jpg" /></a></div></div>
        </div>
        </body></html>
    """.trimIndent()

    private fun readerHtml(n: Int, ext: String) = """
        <html><body>
        <a class="next_img"><img id="gimg" class="preloader image_$n" src="https://m11.imhentai.xxx/032/mtsf5q6a30/$n.$ext" alt="page $n full" /></a>
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
                    path == "/popular/" -> MockResponse().setBody(homeHtml)
                    path == "/gallery/1714318/" -> MockResponse().setBody(detailHtml)
                    path == "/view/1714318/1/" -> MockResponse().setBody(readerHtml(1, "webp"))
                    path == "/view/1714318/2/" -> MockResponse().setBody(readerHtml(2, "jpg"))
                    path == "/view/1714318/3/" -> MockResponse().setBody(readerHtml(3, "webp"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ImHentaiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses gallery_title cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(2, result.size)
        assertEquals("(C103) [Candy Club (Sky)] Onee-chan to Torokeru Kimochi 7", result[0].title)
        assertEquals("/gallery/1714318/", result[0].url)
    }

    @Test
    fun `getMangaDetails reads tags text label groups and strips badge counts`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("sky", details.author)
        assertTrue(details.genres.contains("shotacon"))
        assertTrue(details.genres.none { it.contains("11095") })
    }

    @Test
    fun `getPageList builds view URLs from thumbnail count`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(3, pages.size)
        assertEquals("https://imhentai.xxx/view/1714318/1/", pages[0].url)
    }

    @Test
    fun `getImageUrl resolves the real extension per page`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals("https://m11.imhentai.xxx/032/mtsf5q6a30/1.webp", source.getImageUrl(pages[0]))
        assertEquals("https://m11.imhentai.xxx/032/mtsf5q6a30/2.jpg", source.getImageUrl(pages[1]))
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = ImHentaiSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

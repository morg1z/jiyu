package com.haise.jiyu.source.ehentai

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

class EHentaiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: EHentaiSource

    // Zjednodusena, ale strukturalne verna kopie realne "Compact" (gltc) tabulky z e-hentai.org/.
    // Hrefs jsou schvalne absolutni na realnou domenu - presne tak, jak je e-hentai.org
    // opravdu vraci; redirectingClient pak pri odchozim requestu prepise jen host/port na
    // MockWebServer a zachova cestu, takze dispatcher nize matchuje na tyto cesty.
    private val listingHtml = """
        <html><body>
        <table class="itg gltc"><tr><th></th><th>Published</th><th>Title</th><th class="glhide">Uploader</th></tr>
        <tr><td class="gl1c glcat"><div class="cn ct6">Image Set</div></td>
        <td class="gl2c"><div class="glthumb"><div><img style="height:354px;width:250px" alt="cover" title="cover" data-src="https://ehgt.org/w/02/563/35173-h0dqsolt.webp" /></div></div></td>
        <td class="gl3c glname"><a href="https://e-hentai.org/g/4108576/1e6649c50f/"><div class="glink">Test Gallery Title</div>
        <div><div class="gt" title="parody:bocchi the rock">bocchi the rock</div></div></a></td>
        <td class="gl4c glhide"><div><a href="https://e-hentai.org/uploader/tester">tester</a></div><div>154 pages</div></td></tr>
        </table>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div id="gd1"><div style="width:250px; height:354px; background:transparent url(https://ehgt.org/w/02/563/35173-h0dqsolt.webp) 0 0 no-repeat"></div></div>
        <div id="gd2"><h1 id="gn">Test Gallery Title</h1><h1 id="gj"></h1></div>
        <div id="gmid">
        <div id="gd3"><div id="gdc"><div class="cs ct6">Image Set</div></div>
        <div id="gdd"><table>
        <tr><td class="gdt1">Language:</td><td class="gdt2">Japanese</td></tr>
        <tr><td class="gdt1">Length:</td><td class="gdt2">2 pages</td></tr>
        </table></div>
        </div>
        <div id="gd4">
        <div id="taglist"><table>
        <tr><td class="tc">parody:</td><td><a id="ta_parody:bocchi_the_rock">bocchi the rock</a></td></tr>
        <tr><td class="tc">artist:</td><td><a id="ta_artist:ethan">ethan</a></td></tr>
        <tr><td class="tc">female:</td><td><a id="ta_female:ahegao">ahegao</a></td></tr>
        </table></div>
        </div>
        </div>
        <div id="gdt" class="gt200">
        <a href="https://e-hentai.org/s/f2fbc8f828/4108576-1"><div title="Page 1"></div></a>
        <a href="https://e-hentai.org/s/c1f838f83c/4108576-2"><div title="Page 2"></div></a>
        </div>
        <p class="gpc">Showing 1 - 2 of 2 images</p>
        </body></html>
    """.trimIndent()

    private val readerPage1 = """
        <html><body><div id="i1" class="sni"><img id="img" src="https://example-cdn.test/page1.webp" /></div></body></html>
    """.trimIndent()

    private val readerPage2 = """
        <html><body><div id="i1" class="sni"><img id="img" src="https://example-cdn.test/page2.webp" /></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore("?")
                return when {
                    path == "/" -> MockResponse().setBody(listingHtml)
                    path == "/popular" -> MockResponse().setBody(listingHtml)
                    path == "/g/4108576/1e6649c50f/" -> MockResponse().setBody(detailHtml)
                    path == "/s/f2fbc8f828/4108576-1" -> MockResponse().setBody(readerPage1)
                    path == "/s/c1f838f83c/4108576-2" -> MockResponse().setBody(readerPage2)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = EHentaiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses the compact listing table`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Gallery Title", result[0].title)
        assertTrue(result[0].url.endsWith("/g/4108576/1e6649c50f/"))
        assertTrue(result[0].coverUrl!!.contains("35173-h0dqsolt.webp"))
    }

    @Test
    fun `getMangaDetails parses title, artist and tags from taglist`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Test Gallery Title", details.title)
        assertEquals("ethan", details.artist)
        assertTrue(details.genres.contains("ahegao"))
        assertTrue(details.description!!.contains("Language: Japanese"))
        assertTrue(details.description!!.contains("Parody: bocchi the rock"))
    }

    @Test
    fun `getChapterList returns a single synthetic chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getPageList collects reader links and getImageUrl resolves the real image`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        val resolved = source.getImageUrl(pages[0])
        assertEquals("https://example-cdn.test/page1.webp", resolved)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = EHentaiSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

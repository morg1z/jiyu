package com.haise.jiyu.source.doujiva

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DoujivaSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: DoujivaSource

    // Zkraceny, ale strukturalne realny vyrez z zive odpovedi https://doujiva.com/?page=1
    private val homeHtml = """
        <html><body>
        <a draggable="false" class="group relative block" href="/manga/apex-behavior">
            <div class="aspect-[3/4]"><img src="https://cdn.doujiva.com/apex-behavior/cover.thumb.webp" alt="cover"/></div>
            <div class="px-1.5 py-1.5"><p class="text-[10px]">Apex Behavior</p></div>
        </a>
        </body></html>
    """.trimIndent()

    // Zkraceny vyrez z zive odpovedi https://doujiva.com/manga/apex-behavior
    private val detailHtml = """
        <html><body>
        <h1 class="text-xl md:text-2xl font-extrabold">Apex Behavior</h1>
        <div><span>Artists:</span><a class="tag-artist" href="/artist/cypher05"><span>cypher05</span><span>3</span></a></div>
        <div><a href="/tag/big-breasts">Big Breasts</a></div>
        <div hidden id="S:e"><a href="/tag/stockings">Stockings</a></div>
        <!-- Realne thumbs jen pro prvnich ~24 stran; autoritativni soucet je
             "numberOfPages" v JSON-LD (audit 2026-11: 178 stran, 24 thumbu). -->
        <script type="application/ld+json">{"@type":"CreativeWork","numberOfPages":4}</script>
        <div class="grid">
            <a href="/manga/apex-behavior/read/cmmp4sesr002l0ss3fk0yxzaj?page=1"><img src="https://cdn.doujiva.com/apex-behavior/chapter-1/001.thumb.webp" alt="Page 1"/></a>
            <a href="/manga/apex-behavior/read/cmmp4sesr002l0ss3fk0yxzaj?page=2"><img src="https://cdn.doujiva.com/apex-behavior/chapter-1/002.thumb.webp" alt="Page 2"/></a>
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
                    path.startsWith("/?page=") || path == "/" -> MockResponse().setBody(homeHtml)
                    path.startsWith("/manga/apex-behavior") -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = DoujivaSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular reads title from the p tag and cover url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Apex Behavior", result[0].title)
        assertEquals("https://cdn.doujiva.com/apex-behavior/cover.thumb.webp", result[0].coverUrl)
        assertEquals("https://doujiva.com/manga/apex-behavior", result[0].url)
    }

    @Test
    fun `getMangaDetails reads artist and tags including hidden RSC tags`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("cypher05", details.artist)
        assertEquals(listOf("Big Breasts", "Stockings"), details.genres)
    }

    @Test
    fun `getPageList uses declared numberOfPages and builds full-res urls`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        // Fixture: numberOfPages=4, ale jen 2 thumby - realne chovani webu.
        assertEquals(4, pages.size)
        assertEquals("https://cdn.doujiva.com/apex-behavior/chapter-1/001.webp", pages[0].url)
        assertEquals("https://cdn.doujiva.com/apex-behavior/chapter-1/004.webp", pages[3].url)
    }
}

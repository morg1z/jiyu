package com.haise.jiyu.source.hentaizap

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

class HentaiZapSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HentaiZapSource

    // Zkraceny, ale strukturalne realny vyrez z zive odpovedi https://hentaizap.com/popular/?page=1
    // (redesign 2026-08-21 - stary markup div.thumb/div.caption/div.inner_thumb uz na
    // zivem webu neexistuje, nahrazen article.hz-gallery-card).
    private val popularHtml = """
        <html><body>
        <article class="hz-gallery-card">
            <header class="hz-gallery-card__meta">
                <a class="hz-gallery-card__category" href="/category/artist-cg/">Artist CG</a>
            </header>
            <div class="hz-gallery-card__media thumb">
                <a class="hz-gallery-card__cover" href="/gallery/1610961/">
                    <img src="https://m11.hentaizap.com/032/k5bi98t4z0/thumb.jpg" alt="">
                </a>
            </div>
            <footer class="hz-gallery-card__caption">
                <h2 class="hz-gallery-card__title"><a href="/gallery/1610961/">Childbirth Island 2&amp;3</a></h2>
            </footer>
        </article>
        </body></html>
    """.trimIndent()

    // Zkraceny vyrez z zive odpovedi https://hentaizap.com/gallery/1610961/ (redesign
    // 2026-08-21). Obsahuje i decoy "popular right now" postranni widget se STEJNYMA
    // tag/artist odkazy na CIZI galerie - overuje, ze parsovani je scopovane na
    // div.hz-gallery-metadata a nenaplni se nahodnymi hodnotami z widgetu.
    private val detailHtml = """
        <html><body>
        <div class="hz-gallery-details">
            <h1 id="gallery-title">Childbirth Island 2&amp;3</h1>
            <div class="hz-gallery-metadata">
                <div class="hz-gallery-entity-group">
                    <span class="hz-gallery-entity-label">Tags:</span>
                    <div class="hz-gallery-entity-items">
                        <a class="hz-gallery-tag" href="/tag/big-breasts/"><span class="hz-gallery-tag__name">big breasts</span><span class="hz-gallery-tag__count">448068</span></a>
                        <a class="hz-gallery-tag" href="/tag/futanari/"><span class="hz-gallery-tag__name">futanari</span><span class="hz-gallery-tag__count">80807</span></a>
                    </div>
                </div>
                <div class="hz-gallery-entity-group">
                    <span class="hz-gallery-entity-label">Artists:</span>
                    <div class="hz-gallery-entity-items">
                        <a class="hz-gallery-tag" href="/artist/niyasuke/"><span class="hz-gallery-tag__name">niyasuke</span><span class="hz-gallery-tag__count">12</span></a>
                    </div>
                </div>
            </div>
        </div>
        <div class="hz-home-ranking__body">
            <a href="/tag/decoy-tag/">Decoy Tag</a>
            <a href="/artist/decoy-artist/">Decoy Artist</a>
        </div>
        <div class="thumbstrip">
            <img src="https://m11.hentaizap.com/032/k5bi98t4z0/cover.jpg"/>
            <img src="https://m11.hentaizap.com/032/k5bi98t4z0/1t.jpg"/>
            <img src="https://m11.hentaizap.com/032/k5bi98t4z0/2t.jpg"/>
            <img src="https://m11.hentaizap.com/032/k5bi98t4z0/10t.jpg"/>
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
                    path.startsWith("/popular/") -> MockResponse().setBody(popularHtml)
                    path.startsWith("/gallery/1610961") -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HentaiZapSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular reads title and cover from data-src`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Childbirth Island 2&3", result[0].title)
        assertEquals("https://m11.hentaizap.com/032/k5bi98t4z0/thumb.jpg", result[0].coverUrl)
        assertEquals("https://hentaizap.com/gallery/1610961/", result[0].url)
    }

    @Test
    fun `getMangaDetails reads artist and tags without the trailing badge count`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("niyasuke", details.artist)
        assertEquals(listOf("big breasts", "futanari"), details.genres)
    }

    @Test
    fun `getPageList derives full-res webp urls from the thumbstrip, sorted numerically`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(3, pages.size)
        assertEquals("https://m11.hentaizap.com/032/k5bi98t4z0/1.webp", pages[0].url)
        assertEquals("https://m11.hentaizap.com/032/k5bi98t4z0/2.webp", pages[1].url)
        assertEquals("https://m11.hentaizap.com/032/k5bi98t4z0/10.webp", pages[2].url)
    }
}

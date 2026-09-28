package com.haise.jiyu.source.simplyhentai

import com.haise.jiyu.source.MangaFilter
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

/**
 * Fixture odpovídají reálnému server-renderovanému markupu webu (audit 2026-11):
 * výpis = `article.manga-container` karty, detail = `h1` + `pages-overview`
 * náhledy se sekvenčními `/page/{id}` odkazy, plné obrázky na `/all-pages`.
 */
class SimplyHentaiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: SimplyHentaiSource

    private val listingHtml = """
        <html><body><div data-testid="manga-cards">
        <article class="object-container manga-container" data-testid="manga-card-207580">
          <a class="with-fade content-link" href="/1-blue-archive/sensei-rentaru-talk-a0001">
            <div class="cover-slot"><img src="https://images.sh-cdn.com/x/small_thumb_0c325282.jpg" alt="Sensei Rentaru Talk"/></div>
          </a>
          <div class="info"><h3 class="title"><a href="/1-blue-archive/sensei-rentaru-talk-a0001">Sensei Rentaru Talk</a></h3></div>
        </article>
        </div></body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head><meta property="og:url" content="/1-blue-archive/sensei-rentaru-talk-a0001"/></head><body>
        <header><h1>Sensei Rentaru Talk | Sensei Rental Talk</h1></header>
        <a class="block" data-testid="cover-link" href="/x"><img src="https://images.sh-cdn.com/x/small_thumb_0c325282.jpg"/></a>
        <div data-testid="pages-overview">
          <a href="/1-blue-archive/sensei-rentaru-talk-a0001/page/100"><img src="https://images.sh-cdn.com/x/small_thumb_0c325282.jpg"/></a>
          <a href="/1-blue-archive/sensei-rentaru-talk-a0001/page/101"><img src="https://images.sh-cdn.com/x/small_thumb_500d7bb7.jpg"/></a>
        </div>
        <a class="btn" data-testid="all-pages-link" href="/1-blue-archive/sensei-rentaru-talk-a0001/all-pages">View all <!-- -->3<!-- --> images</a>
        </body></html>
    """.trimIndent()

    private val allPagesHtml = """
        <html><body>
        <img src="https://images.sh-cdn.com/x/0c325282.jpg"/>
        <img src="https://images.sh-cdn.com/x/500d7bb7.jpg"/>
        <img src="https://images.sh-cdn.com/x/77ccdd00.jpg"/>
        </body></html>
    """.trimIndent()

    // Detail bez /all-pages (druha galerie) - detail stale nese page linky +
    // pocet, takze getPageList padne do sekvcencniho fallbacku.
    private val fallbackDetailHtml = """
        <html><head><meta property="og:url" content="/2-other/other-gallery-a0002"/></head><body>
        <h1>Other Gallery</h1>
        <div data-testid="pages-overview">
          <a href="/2-other/other-gallery-a0002/page/200"><img src="https://images.sh-cdn.com/y/small_thumb_aa0011aa.jpg"/></a>
          <a href="/2-other/other-gallery-a0002/page/201"><img src="https://images.sh-cdn.com/y/small_thumb_bb0022bb.jpg"/></a>
        </div>
        <a class="btn" data-testid="all-pages-link" href="/2-other/other-gallery-a0002/all-pages">View all <!-- -->2<!-- --> images</a>
        </body></html>
    """.trimIndent()

    private val singlePageHtml = """
        <html><body><img src="https://images.sh-cdn.com/y/aa0011aa.jpg"/></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/2-mangas/sort-most-viewed") -> MockResponse().setBody(listingHtml)
                    path == "/search/sensei-rentaru-talk" -> MockResponse().setBody(detailHtml)
                    path == "/search/nonexistent-title" -> MockResponse().setResponseCode(404)
                    path == "/1-blue-archive/sensei-rentaru-talk-a0001" -> MockResponse().setBody(detailHtml)
                    path == "/1-blue-archive/sensei-rentaru-talk-a0001/all-pages" -> MockResponse().setBody(allPagesHtml)
                    path == "/2-other/other-gallery-a0002" -> MockResponse().setBody(fallbackDetailHtml)
                    // "/all-pages" pro druhou galerii zamerne 404 -> fallback veta
                    path == "/2-other/other-gallery-a0002/page/200" -> MockResponse().setBody(singlePageHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = SimplyHentaiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses server-rendered manga cards`() = runTest {
        val result = source.getPopular(1, MangaFilter())
        assertEquals(1, result.size)
        assertEquals("Sensei Rentaru Talk", result[0].title)
        assertEquals("https://www.simply-hentai.com/1-blue-archive/sensei-rentaru-talk-a0001", result[0].url)
        // Nahled small_thumb_ se orizne na plne rozliseni
        assertEquals("https://images.sh-cdn.com/x/0c325282.jpg", result[0].coverUrl)
    }

    @Test
    fun `search resolves an exact slug match via the redirect trick`() = runTest {
        val result = source.search("Sensei Rentaru Talk", 1, MangaFilter())
        assertEquals(1, result.size)
        assertEquals("Sensei Rentaru Talk | Sensei Rental Talk", result[0].title)
        assertEquals("https://www.simply-hentai.com/1-blue-archive/sensei-rentaru-talk-a0001", result[0].url)
    }

    @Test
    fun `search returns empty list when no gallery slug matches exactly`() = runTest {
        val result = source.search("nonexistent title", 1, MangaFilter())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getMangaDetails reads title and full-size cover from detail HTML`() = runTest {
        val manga = source.getPopular(1, MangaFilter())[0]
        val detail = source.getMangaDetails(manga)
        assertEquals("Sensei Rentaru Talk | Sensei Rental Talk", detail.title)
        assertEquals("https://images.sh-cdn.com/x/0c325282.jpg", detail.coverUrl)
    }

    @Test
    fun `getPageList prefers all-pages with direct full URLs`() = runTest {
        val manga = source.getPopular(1, MangaFilter())[0]
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(3, pages.size)
        assertEquals("https://images.sh-cdn.com/x/0c325282.jpg", pages[0].url)
    }

    @Test
    fun `getPageList falls back to sequential page ids and getImageUrl resolves lazily`() = runTest {
        val manga = com.haise.jiyu.source.SManga(
            sourceId = source.id,
            url = "https://www.simply-hentai.com/2-other/other-gallery-a0002",
            title = "Other Gallery",
            coverUrl = null,
            contentType = "MANGA",
        )
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://www.simply-hentai.com/2-other/other-gallery-a0002/page/200", pages[0].url)
        assertEquals("https://images.sh-cdn.com/y/aa0011aa.jpg", source.getImageUrl(pages[0]))
    }
}

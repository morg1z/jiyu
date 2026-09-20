package com.haise.jiyu.source.asmhentai

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

class AsmHentaiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: AsmHentaiSource

    // Strukturalne verna kopie realneho `div.preview_item` z asmhentai.com/.
    private val listingHtml = """
        <html><body>
        <div class="preview_item" data-tags="10 13 32">
            <div class="cl"><h3><a href="/category/doujinshi/">Doujinshi</a></h3></div>
            <div class="image">
                <a href="/g/669519/"><img class="lazy" src="data:image/svg+xml,x" data-src="//images.asmhentai.com/018/669519/thumb.jpg" alt="vita sexualis" /></a>
            </div>
            <div class="cpt"><a href="/g/669519/"><h2 class="caption">[Ayataka (Ugetsu Nono)] vita sexualis (Hetalia Axis Powers)</h2></a></div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="cover"><a href="/gallery/669519/1/"><img class="lazy" data-src="//images.asmhentai.com/018/669519/cover.jpg" alt="cover" /></a></div>
        <div class="right"><div class="info">
            <h1>[Ayataka (Ugetsu Nono)] vita sexualis (Hetalia Axis Powers)</h1>
            <h2>紋鷹 (雨月望乃) vita sexualis</h2>
            <ul><div class="tags"><h3>Parodies:</h3>
                <div class="tag_list"><a href="/parody/axis-powers-hetalia/"><span class="badge tag">axis powers hetalia <span class="gallery_count">(544)</span></span></a></div>
            </div></ul>
            <ul><div class="tags"><h3>Tags:</h3>
                <div class="tag_list">
                    <a href="/tag/anal/"><span class="badge tag">anal <span class="gallery_count">(113,089)</span></span></a>
                    <a href="/tag/yaoi/"><span class="badge tag">yaoi <span class="gallery_count">(64,022)</span></span></a>
                </div>
            </div></ul>
            <ul><div class="tags"><h3>Languages:</h3>
                <div class="tag_list"><a href="/language/japanese/"><span class="badge tag">japanese <span class="gallery_count">(353,890)</span></span></a></div>
            </div></ul>
            <ul><div class="tags"><h3>Category:</h3>
                <div class="tag_list"><a href="/category/doujinshi/"><span class="badge tag">doujinshi <span class="gallery_count">(518,443)</span></span></a></div>
            </div></ul>
            <div class="pages"><h3>Pages: 20</h3></div>
        </div></div>
        <input type="hidden" id="load_id" value="669519" />
        <input type="hidden" id="load_dir" value="018" />
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore("?")
                return when {
                    path == "/" || path == "/language/english/" || path.startsWith("/tag/") -> MockResponse().setBody(listingHtml)
                    path == "/g/669519/" -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = AsmHentaiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun recordPaths(): MutableList<String> {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return MockResponse().setBody(listingHtml)
            }
        }
        return paths
    }

    @Test
    fun `popular uses the language archive with sort=popular, latest uses the front page`() = runTest {
        val paths = recordPaths()

        source.getPopular(2, com.haise.jiyu.source.MangaFilter(sortBy = "popular"))
        source.getPopular(2, com.haise.jiyu.source.MangaFilter(sortBy = "latest"))

        assertEquals(listOf("/language/english/?page=2&sort=popular", "/?page=2"), paths)
    }

    @Test
    fun `a tag filter keeps the tag and adds the sort`() = runTest {
        val paths = recordPaths()

        source.getPopular(1, com.haise.jiyu.source.MangaFilter(sortBy = "popular", genres = listOf("big-breasts")))
        source.getPopular(1, com.haise.jiyu.source.MangaFilter(sortBy = "latest", genres = listOf("big-breasts")))

        assertEquals(listOf("/tag/big-breasts/?page=1&sort=popular", "/tag/big-breasts/?page=1"), paths)
    }

    @Test
    fun `getPopular parses preview_item cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("[Ayataka (Ugetsu Nono)] vita sexualis (Hetalia Axis Powers)", result[0].title)
        assertEquals("/g/669519/", result[0].url)
        assertEquals("https://images.asmhentai.com/018/669519/thumb.jpg", result[0].coverUrl)
    }

    @Test
    fun `getMangaDetails strips gallery counts from tags`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertTrue(details.genres.contains("anal"))
        assertTrue(details.genres.contains("yaoi"))
        assertTrue(details.genres.none { it.contains("(") })
        assertTrue(details.description!!.contains("Parody: axis powers hetalia"))
        assertTrue(details.description!!.contains("Language: japanese"))
    }

    @Test
    fun `getChapterList packs dir, id and page count into a single synthetic chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("018|669519|20", chapters[0].url)
    }

    @Test
    fun `getPageList builds direct image URLs without extra requests`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(20, pages.size)
        assertEquals("https://images.asmhentai.com/018/669519/1.jpg", pages[0].url)
        assertEquals("https://images.asmhentai.com/018/669519/20.jpg", pages[19].url)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = AsmHentaiSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

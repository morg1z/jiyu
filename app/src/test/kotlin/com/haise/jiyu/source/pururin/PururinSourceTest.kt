package com.haise.jiyu.source.pururin

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

class PururinSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: PururinSource

    // Zkraceny, ale strukturalne realny vyrez z zive odpovedi https://pururin.me/browse?sort=most-popular&page=1
    private val browseHtml = """
        <html><body>
        <div class="row-gallery" data-current-page="1" data-last-page="2182">
            <a id="G67980" data-gid="67980" class="card card-gallery" href="https://pururin.me/gallery/67980/an-academy-with-servile-female-teachers" title="An Academy with Servile Female Teachers">
                <img class="card-img-top" src="https://i.pururin.me/2eb5d88b_5183/cover.jpg" alt="An Academy with Servile Female Teachers">
                <div class="meta">
                    <div class="title"><div><h2>
                        An Academy with Servile Female Teachers
                        <br>
                        女教師奴隷学園
                    </h2></div></div>
                </div>
            </a>
        </div>
        </body></html>
    """.trimIndent()

    // Zkraceny vyrez z zive odpovedi https://pururin.me/gallery/67980/an-academy-with-servile-female-teachers
    private val galleryHtml = """
        <html><body>
        <h1><span itemprop="name">An Academy with Servile Female Teachers / 女教師奴隷学園</span></h1>
        <img itemprop="image" src="https://i.pururin.me/2eb5d88b_5183/cover.jpg" class="cover" alt="cover" />
        <table class="table table-info">
            <tbody>
                <tr><td>Artist</td><td><ul class="list-inline"><li><a href="/browse/tags/artist/9683/sink">Sink</a></li></ul></td></tr>
                <tr><td>Contents</td><td><ul class="list-inline">
                    <li><a href="/browse/tags/content/1591/ahegao">Ahegao</a></li>
                    <li><a href="/browse/tags/content/1576/anal">Anal</a></li>
                </ul></td></tr>
            </tbody>
        </table>
        <div class="box">
            <div class="gallery-preview">
                <a href="https://pururin.me/read/67980/01/an-academy-with-servile-female-teachers">
                    <img src="https://i.pururin.me/2eb5d88b_5183/1t.jpg" alt="Thumbnail Page 01" />
                    <div>1</div>
                </a>
                <a href="https://pururin.me/read/67980/02/an-academy-with-servile-female-teachers">
                    <img data-src="https://i.pururin.me/2eb5d88b_5183/2t.jpg" class="lazy" alt="Thumbnail Page 02" />
                    <div>2</div>
                </a>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    // Vyrez z zive odpovedi https://pururin.me/tags/content?page=1 - content-tag
    // index, odkazy ve tvaru "/browse/tags/content/{id}/{slug}".
    private val tagsHtml = """
        <html><body>
        <a href="/browse/tags/content/2196/abortion">Abortion</a>
        <a href="/browse/tags/content/1591/ahegao">Ahegao</a>
        <a href="/browse/tags/artist/9683/sink">Sink</a>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/tags/content") -> MockResponse().setBody(
                        if (path.endsWith("page=1")) tagsHtml else ""
                    )
                    path.startsWith("/browse") -> MockResponse().setBody(browseHtml)
                    path.startsWith("/search") -> MockResponse().setBody(browseHtml)
                    path.startsWith("/gallery/67980") -> MockResponse().setBody(galleryHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = PururinSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular reads title before the br and the cover url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("An Academy with Servile Female Teachers", result[0].title)
        assertEquals("https://i.pururin.me/2eb5d88b_5183/cover.jpg", result[0].coverUrl)
        assertEquals("https://pururin.me/gallery/67980/an-academy-with-servile-female-teachers", result[0].url)
    }

    @Test
    fun `search falls back to getPopular for a blank query`() = runTest {
        val result = source.search("", 1)
        assertEquals(1, result.size)
    }

    @Test
    fun `getMangaDetails parses artist and content tags`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("Sink", details.artist)
        assertEquals(listOf("Ahegao", "Anal"), details.genres)
    }

    @Test
    fun `getChapterList returns a single synthetic chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getPageList strips the t suffix to build full-size image URLs`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        assertEquals("https://i.pururin.me/2eb5d88b_5183/1.jpg", pages[0].url)
        assertEquals("https://i.pururin.me/2eb5d88b_5183/2.jpg", pages[1].url)
    }

    @Test
    fun `getAvailableTags returns only content tags, not artist links`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertEquals("2196/abortion", tags[0].id)
        assertEquals("Abortion", tags[0].label)
        assertEquals("1591/ahegao", tags[1].id)
    }

    @Test
    fun `getPopular with a selected genre reads the content-tag archive`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("1591/ahegao")))
        assertEquals(1, result.size)
        assertEquals("An Academy with Servile Female Teachers", result[0].title)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = PururinSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

package com.haise.jiyu.source.hentai3

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

class Hentai3SourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: Hentai3Source

    // Zkraceny, ale strukturalne realny vyrez z zive odpovedi https://3hentai.net/language/english?page=1
    private val listHtml = """
        <html><body>
        <div class="listing-container container-xl">
            <div class="doujin-col">
                <div class="doujin ">
                    <a href="https://3hentai.net/d/713098" class="cover" style="padding:0 0 141.4% 0">
                        <img class="lazy small-bg-load" data-src="https://s1.3hentai.net/d2421239/thumb.jpg" width="250" height="353" />
                        <div class="title flag flag-eng">[Cammy] Couldn&#039;t Mother in law be &quot;USED&quot; 3?</div>
                    </a>
                </div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    // Zkraceny vyrez z zive odpovedi https://3hentai.net/d/713098
    private val detailHtml = """
        <html><body>
        <h1 class="text-left font-weight-bold">[Cammy] Couldn't Mother in law be "USED" 3?</h1>
        <a href="https://3hentai.net/d/713098/1" rel="nofollow">
            <img class="lazy small-bg-load" data-src="https://s1.3hentai.net/d2421239/cover.jpg" width="350" height="495"/>
        </a>
        <div class="tag-container field-name">
            Tags:
            <span class="filter-elem"><a class="name" href="https://3hentai.net/tags/ahegao-female" data-qty="51k">ahegao (female)</a></span>
            <span class="filter-elem"><a class="name" href="https://3hentai.net/tags/big-breasts-female" data-qty="187k">big breasts (female)</a></span>
        </div>
        <div class="tag-container field-name">
            Artists:
            <span class="filter-elem"><a class="name" href="https://3hentai.net/artists/cammy" data-qty="14">cammy</a></span>
        </div>
        <div class="tag-container field-name">
            Pages:
            <span class="field-light-text">2</span>
        </div>
        <div class="single-thumb-col">
            <div class="single-thumb">
                <a href="https://3hentai.net/d/713098/1" rel="nofollow">
                    <img class="lazy small-bg-load" data-src="https://s1.3hentai.net/d2421239/1t.jpg" width="200" height="283" />
                </a>
            </div>
        </div><div class="single-thumb-col">
            <div class="single-thumb">
                <a href="https://3hentai.net/d/713098/2" rel="nofollow">
                    <img class="lazy small-bg-load" data-src="https://s1.3hentai.net/d2421239/2t.jpg" width="200" height="283" />
                </a>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    // Vyrez z zive odpovedi https://3hentai.net/tags?page=1 - index tagu, odkazy
    // jsou absolutni "/tags/{slug}".
    private val tagsHtml = """
        <html><body>
        <a href="https://3hentai.net/tags/ahegao-female">ahegao (female)</a>
        <a href="https://3hentai.net/tags/big-breasts-female">big breasts (female)</a>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/tags" || path.startsWith("/tags?") -> MockResponse().setBody(
                        if (path.endsWith("page=1")) tagsHtml else ""
                    )
                    path.startsWith("/tags/") -> MockResponse().setBody(listHtml)
                    path.startsWith("/language/english") -> MockResponse().setBody(listHtml)
                    path.startsWith("/search") -> MockResponse().setBody(listHtml)
                    path == "/d/713098" -> MockResponse().setBody(detailHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = Hentai3Source(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular reads title and thumbnail from the language listing`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("[Cammy] Couldn't Mother in law be \"USED\" 3?", result[0].title)
        assertEquals("https://s1.3hentai.net/d2421239/thumb.jpg", result[0].coverUrl)
        assertEquals("https://3hentai.net/d/713098", result[0].url)
    }

    @Test
    fun `getMangaDetails parses artist and tags from tag-container rows`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("cammy", details.artist)
        assertEquals(listOf("ahegao (female)", "big breasts (female)"), details.genres)
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
        assertEquals("https://s1.3hentai.net/d2421239/1.jpg", pages[0].url)
        assertEquals("https://s1.3hentai.net/d2421239/2.jpg", pages[1].url)
    }

    @Test
    fun `getAvailableTags reads the paginated tag index`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertEquals("ahegao-female", tags[0].id)
        assertEquals("ahegao (female)", tags[0].label)
    }

    @Test
    fun `getPopular with a selected genre reads the tag archive`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("ahegao-female")))
        assertEquals(1, result.size)
        assertEquals("[Cammy] Couldn't Mother in law be \"USED\" 3?", result[0].title)
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = Hentai3Source(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

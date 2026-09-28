package com.haise.jiyu.source.hentaipaw

import com.haise.jiyu.source.MangaFilter
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

class HentaiPawSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HentaiPawSource

    // Zkraceny, ale strukturalne realny vyrez z zive odpovedi https://hentaipaw.com/?page=1
    private val homeHtml = """
        <html><body>
        <a class="group" href="/articles/9999"><div class="relative"><img class="h-full w-full object-cover" alt="Sample Gallery" src="https://cdn.imagedeliveries.com/9999/thumbnails/cover.webp"/></div>
        <div><div class="line-clamp-2 h-10 text-sm" title="Sample Gallery">Sample Gallery</div></div></a>
        </body></html>
    """.trimIndent()

    // Zkraceny vyrez z zive odpovedi https://hentaipaw.com/articles/9999
    private val detailHtml = """
        <html><body>
        <h1 class="text-wrap text-lg font-semibold">Sample Gallery</h1>
        <div id="article-tag-information">
            <div class="flex flex-wrap gap-1"><h3>Artists:</h3><a href="/artists/1"><div>great mosu</div></a></div>
            <div class="flex flex-wrap gap-1"><h3>Tags:</h3><a href="/tags/186"><div>big breasts</div></a><a href="/tags/718"><div>dark skin</div></a></div>
        </div>
        </body></html>
    """.trimIndent()

    // "self.__next_f.push(...)" RSC stream obsahuje escapovane "src\":\"..." odkazy
    // na plne rozliseni - viz komentar u HentaiPawSource.getPageList.
    private val viewerHtml = """
        <html><body><script>
        self.__next_f.push([1,"0:[\"${'$'}\",\"img\",null,{\"src\":\"https://cdn.imagedeliveries.com/9999/aaa111hash/1.webp\",\"alt\":\"page 1\"}]"])
        self.__next_f.push([1,"1:[\"${'$'}\",\"img\",null,{\"src\":\"https://cdn.imagedeliveries.com/9999/bbb222hash/2.webp\",\"alt\":\"page 2\"}]"])
        </script></body></html>
    """.trimIndent()

    // Vyrez z zive odpovedi https://hentaipaw.com/tags?page=1 - nazev tagu je
    // v "title" atributu; specialni "/tags/popular" odkaz se preskakuje.
    private val tagsHtml = """
        <html><body>
        <a href="/tags/popular" class="group"><div>popular</div></a>
        <a href="/tags/186" class="group" title="big breasts"><div>big breasts</div></a>
        <a href="/tags/718" class="group" title="dark skin"><div>dark skin</div></a>
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
                    path.startsWith("/tags/") -> MockResponse().setBody(homeHtml)
                    path.startsWith("/?page=") || path == "/" -> MockResponse().setBody(homeHtml)
                    path.startsWith("/articles/9999") -> MockResponse().setBody(detailHtml)
                    path.startsWith("/viewer") -> MockResponse().setBody(viewerHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HentaiPawSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular reads title from title attribute and cover url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Sample Gallery", result[0].title)
        assertEquals("https://cdn.imagedeliveries.com/9999/thumbnails/cover.webp", result[0].coverUrl)
        assertEquals("https://hentaipaw.com/articles/9999", result[0].url)
    }

    @Test
    fun `getMangaDetails reads artist and tags`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("great mosu", details.artist)
        assertEquals(listOf("big breasts", "dark skin"), details.genres)
    }

    @Test
    fun `getChapterList returns a single synthetic chapter`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `getAvailableTags reads numeric ids from the title attribute and skips the popular shortcut`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertEquals("186", tags[0].id)
        assertEquals("big breasts", tags[0].label)
        assertEquals("718", tags[1].id)
    }

    @Test
    fun `getPopular with a selected genre reads the tag archive`() = runTest {
        val result = source.getPopular(1, MangaFilter(genres = listOf("186")))
        assertEquals(1, result.size)
        assertEquals("Sample Gallery", result[0].title)
    }

    @Test
    fun `getPageList extracts full-res urls from the next_f RSC payload, sorted by page number`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        assertEquals("https://cdn.imagedeliveries.com/9999/aaa111hash/1.webp", pages[0].url)
        assertEquals("https://cdn.imagedeliveries.com/9999/bbb222hash/2.webp", pages[1].url)
    }
}

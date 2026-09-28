package com.haise.jiyu.source.ifreedom

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IFreedomSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: IFreedomSource

    private val listHtml = """
        <html><body>
        <div class="item-book-slide"><a class="link-book-slide" href="https://x.test/ranobe/test-novel/"></a>
            <div class="block-book-slide-img"><img src="https://x.test/c.jpg"></div>
            <div class="block-book-slide-title">Тест Новелла</div>
        </div>
        <a href="/vse-knigi/?genre[]=Боевые искусства">Боевые искусства</a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <h1>Тест Новелла</h1>
        <div class="genreslist"><a href="?genre[]=Драма">Драма</a></div>
        <div class="chapterinfo"><a href="https://x.test/test-novel/glava-1-start/">Глава 1: Старт</a>
            <div class="chaptdesc"><span class="timechapter">19.06.24</span></div></div>
        <div class="chapterinfo"><a href="https://x.test/podpiska/">999 - VIP глава</a></div>
        <div class="chapterinfo"><a href="https://x.test/test-novel/glava-2-next/">Глава 2: Дальше</a></div>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div class="chapter-content"><p>Текст главы.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/vse-knigi") -> MockResponse().setBody(listHtml)
                    path == "/ranobe/test-novel/" -> MockResponse().setBody(detailHtml)
                    path.startsWith("/test-novel/") -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = IFreedomSource(
            id = "ext:test", name = "Test", baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getPopular parses item-book-slide cards`() = runTest {
        val r = source.getPopular(1)
        assertEquals(1, r.size)
        assertEquals("Тест Новелла", r[0].title)
        assertEquals("https://x.test/ranobe/test-novel/", r[0].url)
    }

    @Test
    fun `genre tags from archive links`() = runTest {
        // Filtr jde do /vse-knigi/?genre[]=...
        val r = source.getPopular(1, MangaFilter(genres = listOf("Боевые искусства")))
        assertEquals(1, r.size)
        assertTrue(server.takeRequest().path.orEmpty().let { it.contains("genre") && it.contains("vse-knigi") })
        val tags = source.getAvailableTags()
        assertEquals(1, tags.size)
        assertEquals("Боевые искусства", tags[0].id)
    }

    @Test
    fun `VIP podpiska chapters are filtered out`() = runTest {
        val chapters = source.getChapterList(SManga("ext:test", "/ranobe/test-novel/", "T", null))
        assertEquals(2, chapters.size)
        assertTrue(chapters.none { it.url.contains("podpiska") })
        assertEquals(1f, chapters[0].chapterNumber)
    }

    @Test
    fun `page list returns chapter text`() = runTest {
        val pages = source.getPageList(
            SChapter("ext:test", "/ranobe/test-novel/", "/test-novel/glava-1-start/", "Г1", 1f, 0L)
        )
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("Текст главы."))
    }
}

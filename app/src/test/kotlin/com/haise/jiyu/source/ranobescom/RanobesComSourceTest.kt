package com.haise.jiyu.source.ranobescom

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

class RanobesComSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: RanobesComSource

    private val listHtml = """
        <html><body>
        <article class="block story shortstory">
            <div class="short-cont"><h2 class="title"><a href="https://x.test/ranobe/123-test.html">Тест</a></h2>
            <a href="https://x.test/ranobe/123-test.html" class="poster">
                <figure class="cover" style="background-image: url(https://x.test/cover.jpg);"></figure></a></div>
        </article>
        </body></html>
    """.trimIndent()

    private val genresHtml = """
        <html><body>
        <a href="/cloud/genre/Боевые%20искусства/">Боевые искусства</a>
        <a href="/cloud/genre/Драма/">Драма</a>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body><div class="r-fullstory">
            <h1 class="title">Тест</h1>
            <div class="moreless">Описание ранобэ.</div>
            <a href="/cloud/status-trs/Завершено/">Завершено</a>
        </div></body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body>
        <div class="cat_block cat_line"><a href="https://x.test/chapters/test/501-2.html">
            <div><h6 class="title">Том 1. Глава 2</h6><span class="grey small"><small>13 сентября 2026</small></span></div></a></div>
        <div class="cat_block cat_line"><a href="https://x.test/chapters/test/500-1.html">
            <div><h6 class="title">Том 1. Глава 1</h6><span class="grey small"><small>10 сентября 2026</small></span></div></a></div>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div id="arrticle"><p>Текст главы здесь.</p></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/ranobe/" -> MockResponse().setBody(listHtml)
                    path.startsWith("/cloud/genre/") && path != "/cloud/genre/" -> MockResponse().setBody(listHtml)
                    path == "/cloud/genre/" -> MockResponse().setBody(genresHtml)
                    path == "/index.php?do=search" || path == "/index.php" -> MockResponse().setBody(listHtml)
                    path == "/ranobe/123-test.html" -> MockResponse().setBody(detailHtml)
                    path == "/chapters/test/" -> MockResponse().setBody(chaptersHtml)
                    path.startsWith("/chapters/test/") -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = RanobesComSource(
            baseUrl = server.url("/").toString().trimEnd('/'), client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `archive parses story cards with background-image cover`() = runTest {
        val r = source.getPopular(1)
        assertEquals(1, r.size)
        assertEquals("Тест", r[0].title)
        assertEquals("https://x.test/ranobe/123-test.html", r[0].url)
        assertEquals("https://x.test/cover.jpg", r[0].coverUrl)
    }

    @Test
    fun `genres discovered from cloud catalog`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(2, tags.size)
        assertTrue(tags.any { it.id == "Драма" })
        val r = source.getPopular(1, MangaFilter(genres = listOf("Драма")))
        assertEquals(1, r.size)
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/cloud/genre/"))
    }

    @Test
    fun `chapters come from chapters slug archive`() = runTest {
        val chapters = source.getChapterList(SManga("ext:ranobescom", "/ranobe/123-test.html", "T", null))
        assertEquals(2, chapters.size)
        assertEquals("Том 1. Глава 2", chapters[0].name)
        assertEquals(2f, chapters[0].chapterNumber)
        assertTrue(chapters[0].dateUpload > 0)
    }

    @Test
    fun `page text from arrticle`() = runTest {
        val pages = source.getPageList(
            SChapter("ext:ranobescom", "/ranobe/123-test.html", "/chapters/test/500-1.html", "Г1", 1f, 0L)
        )
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("Текст главы здесь."))
    }
}

package com.haise.jiyu.source.teamshadowi

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

/** Fixtury podle skutečných stránek team-shadowi.com (ověřeno živě 2026-09-19). */
class TeamShadowiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: TeamShadowiSource

    private fun card(slug: String, title: String) =
        """<a href="/series/$slug"><img alt="$title" src="https://images.team-shadowi.com/Series/$slug/cover.jpg"/></a>"""

    private val seriesHtml = """
        <html><body>
        ${card("alpha", "Alpha")}${card("beta", "Beta")}${card("gamma", "Gamma")}
        <a href="/series/alpha/extra">skip</a>
        </body></html>
    """.trimIndent()

    // /popular: všechny tři, jiné pořadí; /latest: jen dva a bez obrázků (jiná struktura karet)
    private val popularHtml = """<html><body><a href="/series/gamma">Gamma</a><a href="/series/alpha">Alpha</a><a href="/series/beta">Beta</a></body></html>"""
    private val latestHtml = """<html><body><a href="/series/beta">Beta</a><a href="/series/beta">Beta</a></body></html>"""

    // Detail serie: kapitoly uz /api/series nevraci - jsou embedovane v RSC
    // payloadu self.__next_f.push s escapovanymi uvozovkami (audit 2026-09).
    private fun detailHtml(slug: String) = """<html><body>
        <a href="/read/$slug/2">Read</a>
        <script>self.__next_f.push([1,"{\"series\":{\"slug\":\"$slug\",\"chapters\":[{\"id\":\"c1\",\"series_id\":\"s1\",\"number\":\"1\",\"title\":\"Start\",\"volume\":null,\"image_paths\":[\"https://images.team-shadowi.com/x/1/1.webp\",\"https://images.team-shadowi.com/x/1/2.webp\"],\"is_locked\":false,\"created_at\":\"${'$'}D2026-07-12T11:17:54.566Z\"},{\"id\":\"c2\",\"series_id\":\"s1\",\"number\":\"2\",\"title\":\"\",\"volume\":null,\"image_paths\":[\"https://images.team-shadowi.com/x/2/1.webp\"],\"is_locked\":true,\"created_at\":\"${'$'}D2026-07-13T11:17:54.566Z\"}]}}"])</script>
        </body></html>""".trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/series" -> MockResponse().setBody(seriesHtml)
                "/popular" -> MockResponse().setBody(popularHtml)
                "/latest" -> MockResponse().setBody(latestHtml)
                "/api/series/alpha" -> MockResponse().setBody("""{"series":{"genres":["Action"],"chapters":[]}}""")
                "/api/series/beta" -> MockResponse().setBody("""{"series":{"genres":["Fantasy"],"chapters":[]}}""")
                "/api/series/gamma" -> MockResponse().setBody("""{"series":{"genres":["Action","Fantasy"],"chapters":[]}}""")
                "/series/alpha" -> MockResponse().setBody(detailHtml("alpha"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        source = TeamShadowiSource(redirectingClient(server))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `popular follows the order of the site's popular page and keeps title and cover from the series list`() = runTest {
        val result = source.getPopular(1, MangaFilter(sortBy = "popular"))

        assertEquals(listOf("gamma", "alpha", "beta"), result.map { it.url })
        assertEquals("Gamma", result.first().title)
        assertEquals("https://images.team-shadowi.com/Series/gamma/cover.jpg", result.first().coverUrl)
    }

    @Test
    fun `latest puts recently updated titles first and the rest after them in catalogue order`() = runTest {
        val result = source.getPopular(1, MangaFilter(sortBy = "latest"))

        assertEquals(listOf("beta", "alpha", "gamma"), result.map { it.url })
    }

    @Test
    fun `when the ordering page fails the catalogue is still returned`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/series") MockResponse().setBody(seriesHtml) else MockResponse().setResponseCode(500)
        }

        assertEquals(listOf("alpha", "beta", "gamma"), source.getPopular(1, MangaFilter()).map { it.url })
    }

    @Test
    fun `there is only one page`() = runTest {
        assertEquals(emptyList<Any>(), source.getPopular(2, MangaFilter()))
    }

    @Test
    fun `getAvailableTags collects distinct genres from the series API`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("Action", "Fantasy"), tags.map { it.id })
    }

    @Test
    fun `getPopular with a selected genre filters the catalogue locally`() = runTest {
        // Filtrovane tituly se porad radi podle "/popular" (gamma, alpha, beta).
        val result = source.getPopular(1, MangaFilter(genres = listOf("Action")))
        assertEquals(listOf("gamma", "alpha"), result.map { it.url })
    }

    @Test
    fun `getChapterList reads chapters embedded in the RSC payload`() = runTest {
        val manga = source.getPopular(1).first { it.url == "alpha" }
        val chapters = source.getChapterList(manga)
        assertEquals(listOf(2f, 1f), chapters.map { it.chapterNumber })
        assertEquals("Chapter 1.0: Start", chapters[1].name)
        assertEquals("alpha/2.0", chapters[0].url)
        assert(chapters[0].dateUpload > 0)
    }

    @Test
    fun `getPageList returns image_paths and skips locked chapters`() = runTest {
        val manga = source.getPopular(1).first { it.url == "alpha" }
        val chapters = source.getChapterList(manga)
        val unlocked = chapters.first { it.chapterNumber == 1f }
        val pages = source.getPageList(unlocked)
        assertEquals(
            listOf(
                "https://images.team-shadowi.com/x/1/1.webp",
                "https://images.team-shadowi.com/x/1/2.webp",
            ),
            pages.map { it.imageUrl },
        )
        val locked = chapters.first { it.chapterNumber == 2f }
        assertEquals(emptyList<Any>(), source.getPageList(locked))
    }
}

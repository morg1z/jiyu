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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/series" -> MockResponse().setBody(seriesHtml)
                "/popular" -> MockResponse().setBody(popularHtml)
                "/latest" -> MockResponse().setBody(latestHtml)
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
}

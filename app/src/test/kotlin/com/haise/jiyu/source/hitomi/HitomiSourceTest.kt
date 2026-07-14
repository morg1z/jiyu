package com.haise.jiyu.source.hitomi

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ltn.hitomi.la je mrtve, nahrazuje ho ltn.gold-usergeneratedcontent.net;
 * listing/detail HTML na hitomi.la je dnes uz jen prazdna kostra pro JS,
 * takze zdroj cte primo `/index-all.nozomi` (binarni index ID), server-
 * rendered `/galleryblock/{id}.html` a `/galleries/{id}.js` - viz komentar
 * v HitomiSource.kt. Fixture pro "999" v gg.js odpovida realne overenemu
 * hashi koncicimu na "e73" (viz getPageList test).
 */
class HitomiSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HitomiSource

    // Big-endian 4B pro gallery ID 42; test nezavisi na presnem Range rozsahu.
    private val nozomiBytes = byteArrayOf(0, 0, 0, 42)

    private val galleryBlockHtml = """
        <h1 class="lillie"><a href="/doujinshi/test-gallery-42.html" title="Test Gallery">Test Gallery JP</a></h1>
        <div class="dj-img1"><img data-src="//tn.example.com/webpbigtn/a/bc/testhash.webp" /></div>
    """.trimIndent()

    // hash konci na "e73" -> g = parseInt("3e7", 16) = 999, coz je v gg.js fixture v "zero" case listu.
    private val galleryInfoJs = """
        var galleryinfo = {"title": "Test Gallery", "type": "doujinshi", "language_localname": "japanese",
        "tags": [{"tag": "action"}], "files": [{"hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaae73"}]}
    """.trimIndent()

    private val ggJs = """
        gg = { m: function(g) { var o = 1; switch (g) { case 999: o = 0; break; } return o; }, b: '123456/' };
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/index-all.nozomi" -> MockResponse().setResponseCode(206)
                        .setBody(Buffer().write(nozomiBytes))
                    path == "/galleryblock/42.html" -> MockResponse().setBody(galleryBlockHtml)
                    path == "/galleries/42.js" -> MockResponse().setBody(galleryInfoJs)
                    path == "/gg.js" -> MockResponse().setBody(ggJs)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = HitomiSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular decodes nozomi ids and parses galleryblock cards`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Gallery", result[0].title)
        assertEquals("/doujinshi/test-gallery-42.html", result[0].url)
        assertTrue(result[0].coverUrl!!.endsWith("testhash.webp"))
    }

    @Test
    fun `getMangaDetails parses galleryinfo js`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals(listOf("action"), details.genres)
        assertTrue(details.description!!.contains("Type: doujinshi"))
    }

    @Test
    fun `getPageList builds full-size image url via gg-js algorithm`() = runTest {
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(1, pages.size)
        assertEquals(
            "https://w1.gold-usergeneratedcontent.net/123456/999/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaae73.webp",
            pages[0].url,
        )
    }

    @Test
    fun `getPageList handles flipped gg-js polarity (default 0, case list to 1)`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/index-all.nozomi" -> MockResponse().setResponseCode(206).setBody(Buffer().write(nozomiBytes))
                    path == "/galleryblock/42.html" -> MockResponse().setBody(galleryBlockHtml)
                    path == "/galleries/42.js" -> MockResponse().setBody(galleryInfoJs)
                    path == "/gg.js" -> MockResponse().setBody(
                        "gg = { m: function(g) { var o = 0; switch (g) { case 999: o = 1; break; } return o; }, b: '123456/' };"
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val manga = source.getPopular(1).first()
        val chapter = source.getChapterList(manga).first()
        val pages = source.getPageList(chapter)

        assertEquals(1, pages.size)
        assertEquals(
            "https://w2.gold-usergeneratedcontent.net/123456/999/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaae73.webp",
            pages[0].url,
        )
    }

    @Test
    fun `server error returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        server.start()
        val failingSource = HitomiSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}

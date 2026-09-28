package com.haise.jiyu.source.globalcomix

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SChapter
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
 * GlobalComix bezi pres api.globalcomix.com JSON API s X-Gc-Client klicem z
 * window.gc.global v HTML. Fixtures kopiruji realne tvary: search/query
 * (payload.results.series.items), comics/{slug} (detail), comics/{id}/releases,
 * readV3/{key} (reader_cdn_access + page_objects), app/init (comic_genres).
 */
class GlobalComixSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: GlobalComixSource

    private val homepageHtml = """
        <html><script>window.gc = {"global":{"api_key":"gck_test123abc","api_url":"https://api.globalcomix.com"}};</script></html>
    """.trimIndent()

    private val searchJson = """
        {"meta":{"code":200},"payload":{"results":{"series":{"total":2,"items":[
          {"id":33221,"name":"The Backwards House","slug":"the-backwards-house","url":"/c/the-backwards-house",
           "image_url":"https://globalcomix.com/img/cover1.webp","is_free":true,"total_releases":14},
          {"id":26982,"name":"Absolute Batman (2024-)","slug":"absolute-batman-2024-","url":"/c/absolute-batman-2024-",
           "image_url":"https://globalcomix.com/img/cover2.webp","is_free":false,"total_releases":29}
        ]}}}}
    """.trimIndent()

    private val detailJson = """
        {"meta":{"code":200},"payload":{"results":{
          "id":33221,"name":"The Backwards House","slug":"the-backwards-house",
          "description":"A cursed VHS tape story.","status_name":"Ongoing","category_name":"Horror",
          "image_url":"https://globalcomix.com/img/cover1.webp","year":2024,
          "artist":{"entity_type":"Artist","name":"skynix-art"}
        }}}
    """.trimIndent()

    private val releasesJson = """
        {"meta":{"code":200},"payload":{"results":[
          {"id":183483,"key":"ca21969c-eb4d-4d8e-8a17-d4607b678ec8","slug":"dont-forget-your-name",
           "chapter":"1","title":"Don't Forget Your Name","is_free":true,"page_count":40,
           "published_time":"2025-11-16 19:58:00","is_published":true,"is_public_now":true,"lang_id":"en"},
          {"id":183488,"key":"2f4d0353-7724-4f1c-8898-997bc59cc0e7","slug":"escape",
           "chapter":"2","title":"Escape","is_free":true,"page_count":21,
           "published_time":"2025-11-20 10:00:00","is_published":true,"is_public_now":true,"lang_id":"en"},
          {"id":999999,"key":"draft-uuid","slug":"draft","chapter":"3","title":"Unpublished Draft",
           "is_published":false,"is_public_now":false}
        ]}}
    """.trimIndent()

    private val readJson = """
        {"meta":{"code":200},"payload":{"results":{
          "entity_type":"Release","id":183483,"key":"ca21969c-eb4d-4d8e-8a17-d4607b678ec8","is_free":true,
          "page_objects":[
            {"id":1,"order":1,"is_page_paid":false},
            {"id":2,"order":2,"is_page_paid":false},
            {"id":3,"order":3,"is_page_paid":true}
          ],
          "reader_cdn_access":{"base_url":"https://reader-cdn.globalcomix.com",
            "release_key":"ca21969c-eb4d-4d8e-8a17-d4607b678ec8",
            "url_template":"{base_url}/r/{release_key}/p/{order}/{quality}.webp",
            "ranges":[[1,3]],"page_count":3}
        }}}
    """.trimIndent()

    private val initJson = """
        {"meta":{"code":200},"payload":{"results":{"comic_genres":[
          {"id":1,"name":"Action","slug":"action"},{"id":9,"name":"Horror","slug":"horror"}
        ]}}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse().setBody(homepageHtml)
                    path.startsWith("/v1/search/query") -> MockResponse().setBody(searchJson)
                    path.startsWith("/v1/comics/the-backwards-house") -> MockResponse().setBody(detailJson)
                    path.startsWith("/v1/comics/33221/releases") -> MockResponse().setBody(releasesJson)
                    path.startsWith("/v1/readV3/") -> MockResponse().setBody(readJson)
                    path.startsWith("/v1/app/init") -> MockResponse().setBody(initJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = GlobalComixSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses series items and stores slug url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(2, result.size)
        assertEquals("The Backwards House", result[0].title)
        assertEquals("/c/the-backwards-house", result[0].url)
        assertEquals("https://globalcomix.com/img/cover1.webp", result[0].coverUrl)
        assertEquals("COMIC", result[0].contentType)
        assertEquals(14f, result[0].lastChapter)
    }

    @Test
    fun `sort and genre map to query params`() = runTest {
        source.getPopular(1, MangaFilter(sortBy = "latest", genres = listOf("9")))
        val path = server.takeRequest().path.orEmpty() // homepage (api key)
        assertEquals("/", path)
        val apiPath = server.takeRequest().path.orEmpty()
        assertTrue(apiPath.contains("sort=recent"))
        assertTrue(apiPath.contains("comic_genre_id=9"))
    }

    @Test
    fun `getMangaDetails reads description status author and genre`() = runTest {
        val manga = source.getPopular(1).first()
        val d = source.getMangaDetails(manga)
        assertEquals("A cursed VHS tape story.", d.description)
        assertEquals("Ongoing", d.status)
        assertEquals("skynix-art", d.author)
        assertEquals(listOf("Horror"), d.genres)
        assertEquals(2024, d.year)
    }

    @Test
    fun `getChapterList skips unpublished releases and sorts desc`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals("Escape", chapters[0].name)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals("/r/2f4d0353-7724-4f1c-8898-997bc59cc0e7", chapters[0].url)
    }

    @Test
    fun `getPageList expands cdn template and skips paid pages`() = runTest {
        val pages = source.getPageList(SChapter("globalcomix", "/c/x", "/r/ca21969c-eb4d-4d8e-8a17-d4607b678ec8", "c", 1f, 0L))
        assertEquals(2, pages.size)
        assertEquals(
            "https://reader-cdn.globalcomix.com/r/ca21969c-eb4d-4d8e-8a17-d4607b678ec8/p/1/desktop.webp",
            pages[0].url,
        )
        assertTrue(pages[1].url.endsWith("/p/2/desktop.webp"))
    }

    @Test
    fun `getAvailableTags reads comic_genres taxonomy`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(listOf("1", "9"), tags.map { it.id })
        assertEquals(listOf("Action", "Horror"), tags.map { it.label })
    }
}

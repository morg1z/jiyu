package com.haise.jiyu.source.mangafreak

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

/**
 * Fixtury jsou zkrácené výřezy ze skutečných stránek ww3.mangafreak.me (ověřeno živě 2026-09-19) -
 * dřívější test používal vymyšlené značkování, které skutečnému webu vůbec neodpovídalo.
 */
class MangaFreakSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaFreakSource
    private val requestedPaths = mutableListOf<String>()

    private val mangalistHtml = """
        <html><body><div class="list_item_main"><div class="list_item_content">
        <div class="list_item">
          <div class="list_image"><a href="/Manga/One_Piece"><img src="https://images.mangafreak.me/mini_images/one_piece/100x140"></a></div>
          <div class="list_item_info"><h3><!-- 1. --><a href="/Manga/One_Piece">One Piece</a></h3></div>
        </div>
        </div></div></body></html>
    """.trimIndent()

    private val latestHtml = """
        <html><body>
        <div class="latest_releases_item">
          <div class="latest_releases_image"><img src="https://images.mangafreak.me/mini_images/berserk/55x85"></div>
          <div class="latest_releases_info"><a href="/Manga/Berserk"><strong>Berserk</strong></a>
            <div><div><a href="/Read1_Berserk_376">Berserk 376</a></div></div></div>
        </div>
        </body></html>
    """.trimIndent()

    private val searchHtml = """
        <html><body><div class="search_result"><div class="manga_result">
        <div class="manga_search_item"><h6>1.</h6>
          <span><a href="/Manga/One_Piece"><img src="https://images.mangafreak.me/manga_images/one_piece.jpg"></a></span>
          <span><h3><a href="/Manga/One_Piece">One Piece</a></h3></span>
        </div></div></div></body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="manga_series_image"><img src="https://images.mangafreak.me/manga_images/one_piece.jpg"></div>
        <div class="manga_series_data">
          <h1>One Piece</h1>
          <div>Alternative Title: One Piece</div>
          <div>This is ON-GOING series</div>
          <div>Written By: Oda, Eiichiro</div>
          <div>Illustrated By: Oda, Eiichiro</div>
          <div class="series_sub_genre_list"><a href="/Genre/Action">Action</a><a href="/Genre/Adventure">Adventure</a></div>
        </div>
        <div class="manga_series_description"><div>Synopsis</div><p>Pirates.</p></div>
        <div class="manga_series_list"><table>
          <tr><td><a class="chapter-link" href="/Read1_One_Piece_1">Chapter 1 - Romance Dawn</a></td><td>2009/07/06</td></tr>
          <tr><td><a class="chapter-link" href="/Read1_One_Piece_2">Chapter 2 - They Call Him Strawhat Luffy</a></td><td>2009/07/06</td></tr>
        </table></div>
        </body></html>
    """.trimIndent()

    private val readHtml = """
        <html><body><div class="slideshow-container">
        <div class="mySlides"><img id="gohere" src="https://images.mangafreak.me/mangas/one_piece/one_piece_1/one_piece_1_1.jpg"></div>
        <div class="mySlides"><img id="gohere" src="https://images.mangafreak.me/mangas/one_piece/one_piece_1/one_piece_1_2.jpg"></div>
        </div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requestedPaths += path
                return when {
                    path.startsWith("/Mangalist/All/") -> MockResponse().setBody(mangalistHtml)
                    path.startsWith("/Latest_Releases") -> MockResponse().setBody(latestHtml)
                    path.startsWith("/Find/") -> MockResponse().setBody(searchHtml)
                    path == "/Manga/One_Piece" -> MockResponse().setBody(detailHtml)
                    path == "/Read1_One_Piece_1" -> MockResponse().setBody(readHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaFreakSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `popular lists the alphabetical catalogue and derives a full-size cover`() = runTest {
        val result = source.getPopular(1)

        assertEquals(1, result.size)
        assertEquals("One Piece", result[0].title)
        assertEquals("/Manga/One_Piece", result[0].url)
        assertEquals("https://images.mangafreak.me/manga_images/one_piece.jpg", result[0].coverUrl)
    }

    @Test
    fun `latest tab uses Latest_Releases with the page in the path`() = runTest {
        val result = source.getPopular(2, MangaFilter(sortBy = "latest"))

        assertEquals("Berserk", result.single().title)
        assertTrue(requestedPaths.contains("/Latest_Releases/2"))
    }

    @Test
    fun `search encodes spaces as %20 in the path and passes the page as a query parameter`() = runTest {
        val result = source.search("One Piece", 3)

        assertEquals("One Piece", result.single().title)
        assertTrue("očekávána cesta s %20 a ?page=3, bylo $requestedPaths", requestedPaths.contains("/Find/one%20piece?page=3"))
    }

    @Test
    fun `full flow parses details, chapters and pages`() = runTest {
        val manga = source.getPopular(1).first()

        val details = source.getMangaDetails(manga)
        assertEquals("Pirates.", details.description)
        assertEquals(listOf("Action", "Adventure"), details.genres)
        assertEquals("Oda, Eiichiro", details.author)
        assertEquals("ongoing", details.status)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(1f, chapters[0].chapterNumber, 0f)
        assertEquals(2f, chapters[1].chapterNumber, 0f)
        assertTrue(chapters[0].dateUpload > 0L)

        val pages = source.getPageList(chapters[0])
        assertEquals(2, pages.size)
        assertEquals("https://images.mangafreak.me/mangas/one_piece/one_piece_1/one_piece_1_1.jpg", pages[0].imageUrl)
    }

    @Test
    fun `malformed HTML returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
        }
        server.start()
        val emptySource = MangaFreakSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

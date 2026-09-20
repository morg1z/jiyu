package com.haise.jiyu.source.oppaistream

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

class OppaiStreamSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: OppaiStreamSource

    private fun cardHtml(slug: String, title: String) = """
        <div class='in-grid read-over'><a href='https://read.oppai.stream/manhwa?m=$slug' class='in-grid-wrap'>
            <div class='img-wrap'><img class='read-cover' src='https://myspacecat.pictures/manhwa/$slug/cover.png'></div>
            <div class='read-info'><h3 class='white bebas line-2 man-title'>$title</h3></div>
        </a></div>
    """.trimIndent()

    private val listHtml = cardHtml("the-regressed-man", "The Reincarnated Man")

    private val detailHtml = """
        <html><body>
        <h1 class="white bebas line-3">The Reincarnated Man By <a href='' class='red'>Dareng</a></h1>
        <h5 class="white line-3 description">A short summary.</h5>
        <div class="genres"><a href=''><h5 class='gray inline genre-in'>Comedy</h5></a><a href=''><h5 class='gray inline genre-in'>Drama</h5></a></div>
        <section class="chapters"><div class="chapters-grid grid"><div class="in-grid">
        <div class="categories-in sort-chapters category-chapters">
            <a style='position:relative;' href='https://read.oppai.stream/page?m=the-regressed-man&c=2' ch-num='2'><div class='in-grid-wrap'>
                <h6 class='gray'>3 days ago</h6>
                <h4 class='white line-3'><font class='hide-phone'>Chapter </font>2</h4>
            </div></a>
            <a style='position:relative;' href='https://read.oppai.stream/page?m=the-regressed-man&c=1' ch-num='1'><div class='in-grid-wrap'>
                <h6 class='gray'>10 days ago</h6>
                <h4 class='white line-3'><font class='hide-phone'>Chapter </font>1</h4>
            </div></a>
        </div>
        </div></div></section>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/load-more.php") -> MockResponse().setBody(listHtml)
                    path.startsWith("/api-search.php") -> MockResponse().setBody(listHtml)
                    request.requestUrl?.encodedPath == "/manhwa" && request.requestUrl?.queryParameter("m") == "the-regressed-man" ->
                        MockResponse().setBody(detailHtml)
                    request.requestUrl?.encodedPath == "/manhwa/images.php" -> MockResponse().setBody("15")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = OppaiStreamSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `popular is ordered by views and latest by recently uploaded chapters`() = runTest {
        val orders = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                orders += request.requestUrl?.queryParameter("order")
                return MockResponse().setBody(listHtml)
            }
        }

        source.getPopular(3, MangaFilter(sortBy = "popular"))
        source.getPopular(1, MangaFilter(sortBy = "latest"))

        assertEquals(listOf("views", "uploaded"), orders)
    }

    @Test
    fun `getPopular parses card listing from load-more php`() = runTest {
        val result = source.getPopular(1, MangaFilter())
        assertEquals(1, result.size)
        assertEquals("The Reincarnated Man", result[0].title)
        assertTrue(result[0].url.contains("?m=the-regressed-man"))
    }

    @Test
    fun `getMangaDetails splits title from the trailing By-author text and reads genres`() = runTest {
        val manga = source.getPopular(1, MangaFilter())[0]
        val detail = source.getMangaDetails(manga)
        assertEquals("The Reincarnated Man", detail.title)
        assertEquals("Dareng", detail.author)
        assertEquals(listOf("Comedy", "Drama"), detail.genres)
        assertEquals("A short summary.", detail.description)
    }

    @Test
    fun `getChapterList reads chapter number from the ch-num attribute`() = runTest {
        val manga = source.getPopular(1, MangaFilter())[0]
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList builds sequential page URLs from the images-php page count`() = runTest {
        val manga = source.getPopular(1, MangaFilter())[0]
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[1])
        assertEquals(15, pages.size)
        assertTrue(pages[0].url.endsWith("/the-regressed-man/1/1.jpg"))
        assertTrue(pages[14].url.endsWith("/the-regressed-man/1/15.jpg"))
    }
}

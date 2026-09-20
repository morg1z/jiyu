package com.haise.jiyu.source.zeistmanga

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.redirectingClient
import com.haise.jiyu.util.SourceParseException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ZeistMangaSourceTest {

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()

    private val listFeed = """
        {"feed":{"entry":[
          {"title":{"${'$'}t":"Test Series"},
           "link":[{"rel":"replies","href":"https://site.test/x"},{"rel":"alternate","href":"https://site.test/p/test-series.html"}],
           "media${'$'}thumbnail":{"url":"https://blogger.googleusercontent.com/img/a/abc=s72-c"}},
          {"title":{"${'$'}t":"Second"},
           "link":[{"rel":"alternate","href":"https://site.test/p/second.html"}],
           "content":{"${'$'}t":"<p><img src=\"https://cdn.example.com/second.jpg\"></p>"}}
        ]}}
    """.trimIndent()

    private val emptyFeed = """{"feed":{"id":{"${'$'}t":"x"}}}"""

    private val chaptersFeed = """
        {"feed":{"entry":[
          {"title":{"${'$'}t":"Test Series Chapter 2"},"published":{"${'$'}t":"2026-07-02T10:00:00.000-07:00"},
           "link":[{"rel":"alternate","href":"https://site.test/2026/07/test-series-chapter-2.html"}]},
          {"title":{"${'$'}t":"Test Series Chapter 1.5"},"published":{"${'$'}t":"garbage"},
           "link":[{"rel":"alternate","href":"https://site.test/2026/07/test-series-chapter-1-5.html"}]},
          {"title":{"${'$'}t":"Prologue"},"published":{"${'$'}t":"2026-06-01T00:00:00.000+00:00"},
           "link":[{"rel":"alternate","href":"https://site.test/2026/06/prologue.html"}]},
          {"title":{"${'$'}t":"Test Series"},"published":{"${'$'}t":"2026-05-01T00:00:00.000+00:00"},
           "link":[{"rel":"alternate","href":"https://site.test/p/test-series.html"}]}
        ]}}
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <div class="y6x11p"><span>Status</span><span class="dt">Ongoing</span></div>
        <div class="y6x11p"><span>Author</span><span class="dt">Jane Doe</span></div>
        <div id="synopsis">A test summary.</div>
        <article><div class="mt-15"><a href="/search/label/Action">Action</a><a href="/search/label/Romance">Romance</a></div></article>
        <div id="myUL"><script src="https://site.test/feeds/posts/default/-/Test%20Series?alt=json"></script></div>
        </body></html>
    """.trimIndent()

    private val homeHtml = """
        <html><body><div class="filter"><ul>
          <li><input type="checkbox" value="Action"><label>action</label></li>
          <li><input type="checkbox" value="Comedia"><label>Comedia</label></li>
        </ul></div></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                return when {
                    path.startsWith("/feeds/posts/default/-/Test%20Series") -> MockResponse().setBody(chaptersFeed)
                    path.contains("start-index=41") -> MockResponse().setBody(emptyFeed)
                    path.startsWith("/feeds/") -> MockResponse().setBody(listFeed)
                    path == "/p/test-series.html" -> MockResponse().setBody(detailHtml)
                    path == "/" -> MockResponse().setBody(homeHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source(category: String = "Series", selectPage: String = ZeistMangaSource.DEFAULT_SELECT_PAGE) =
        ZeistMangaSource(
            "test", "Test", "https://site.test", redirectingClient(server),
            mangaCategory = category, selectPage = selectPage,
        )

    @Test
    fun `list reads titles and covers from the feed`() = runTest {
        val result = source().getPopular(1)
        assertEquals(2, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("https://site.test/p/test-series.html", result[0].url)
        assertEquals("https://blogger.googleusercontent.com/img/a/abc=w600", result[0].coverUrl)
        assertEquals("cover falls back to the first image of the post", "https://cdn.example.com/second.jpg", result[1].coverUrl)
    }

    @Test
    fun `paging uses start-index and a genre replaces the category label`() = runTest {
        val s = source()
        s.getPopular(1)
        assertEquals("/feeds/posts/default/-/Series?alt=json&orderby=published&max-results=20&start-index=1", paths.last())
        s.getPopular(3, MangaFilter(genres = listOf("Ação Aventura")))
        assertEquals(
            "/feeds/posts/default/-/A%C3%A7%C3%A3o%20Aventura?alt=json&orderby=published&max-results=20&start-index=41",
            paths.last(),
        )
    }

    @Test
    fun `a page past the end is an empty list, not an error`() = runTest {
        assertTrue(source().getPopular(3).isEmpty())
    }

    @Test
    fun `search queries the category label`() = runTest {
        source(category = "Seriler").search("one piece", 2)
        assertEquals(
            "/feeds/posts/default/-/Seriler?alt=json&orderby=published&max-results=20&start-index=21&q=label:Seriler+one%20piece",
            paths.last(),
        )
    }

    @Test
    fun `tags come from the filter box`() = runTest {
        val tags = source().getAvailableTags()
        assertEquals(listOf("Action", "Comedia"), tags.map { it.id })
        assertEquals("action", tags[0].label)
    }

    @Test
    fun `details parse status author description and genres`() = runTest {
        val s = source()
        val details = s.getMangaDetails(s.getPopular(1).first())
        assertEquals("ongoing", details.status)
        assertEquals("Jane Doe", details.author)
        assertEquals("A test summary.", details.description)
        assertEquals(listOf("Action", "Romance"), details.genres)
    }

    @Test
    fun `chapters come from the label feed without the title itself`() = runTest {
        val s = source()
        val chapters = s.getChapterList(s.getPopular(1).first())
        assertEquals(3, chapters.size)
        assertEquals("/feeds/posts/default/-/Test%20Series?alt=json&orderby=published&max-results=9999", paths.last())
        assertEquals("Test Series Chapter 2", chapters[0].name)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(Instant.parse("2026-07-02T17:00:00Z").toEpochMilli(), chapters[0].dateUpload)
        assertEquals(1.5f, chapters[1].chapterNumber)
        assertEquals("unparseable date is 0, not now", 0L, chapters[1].dateUpload)
        assertEquals("no number in the name falls back to the position (oldest = 1)", 1f, chapters[2].chapterNumber)
        assertEquals("https://site.test/p/test-series.html", chapters[0].mangaUrl)
    }

    @Test
    fun `chapter label is found in every template variant`() {
        val s = source()
        fun label(html: String) = s.chapterLabel(org.jsoup.Jsoup.parse(html), "https://site.test/p/x.html")
        assertEquals("Serie A", label("""<div id="myUL"><script src="https://s.test/feeds/posts/default/-/Serie%20A?alt=json"></script></div>"""))
        assertEquals("Serie B", label("""<div id="latest"><script>var label = 'Serie B';</script></div>"""))
        assertEquals("Serie C", label("""<div id="clwd"><script>clwd.run('Serie C', 9)</script></div>"""))
        assertEquals("Serie D", label("""<div id="chapterlist" data-post-title="Serie D"></div>"""))
        assertEquals("Serie E", label("""<script>var label_chapter = "Serie E";</script>"""))
        assertThrows(SourceParseException::class.java) { label("<html></html>") }
    }

    @Test
    fun `pages from a chapterImage script`() {
        val html = """<html><script>var chapterImage = ["https://cdn.example.com/1.jpg", "/2.jpg"];</script></html>"""
        val pages = source().parsePages(html, "https://site.test/c.html")
        assertEquals(listOf("https://cdn.example.com/1.jpg", "https://site.test/2.jpg"), pages.map { it.url })
        assertEquals(listOf(0, 1), pages.map { it.index })
    }

    @Test
    fun `pages from a content template literal`() {
        val html = "<html><script>const content = `<img src=\"https://cdn.example.com/a.jpg\"><img src=\"https://cdn.example.com/b.jpg\">`;</script></html>"
        val pages = source().parsePages(html, "https://site.test/c.html")
        assertEquals(listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg"), pages.map { it.url })
    }

    @Test
    fun `pages from reader images with lazy loading and a custom selector`() {
        val html = """<html><div id="readarea"><img data-src="/p/1.jpg" src="data:image/gif;base64,AAA"><img src="https://cdn.example.com/p/2.jpg"></div>
            <div class="own"><img src="https://cdn.example.com/own.jpg"></div></html>"""
        assertEquals(
            listOf("https://site.test/p/1.jpg", "https://cdn.example.com/p/2.jpg"),
            source().parsePages(html, "https://site.test/c.html").map { it.url },
        )
        assertEquals(
            listOf("https://cdn.example.com/own.jpg"),
            source(selectPage = "div.own img").parsePages(html, "https://site.test/c.html").map { it.url },
        )
    }

    @Test
    fun `a chapter without images is a parse error`() {
        assertThrows(SourceParseException::class.java) {
            source().parsePages("<html><body>nothing</body></html>", "https://site.test/c.html")
        }
    }

    @Test
    fun `a non json feed is a parse error`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>blocked</html>")
        }
        var thrown: Throwable? = null
        try { source().getPopular(1) } catch (e: SourceParseException) { thrown = e }
        assertTrue(thrown is SourceParseException)
    }

    @Test
    fun `only the latest sort is offered and the source stays out of global search`() {
        val s = source()
        assertEquals(setOf("latest"), s.availableSorts)
        assertEquals(false, s.includeInGlobalSearch)
    }
}

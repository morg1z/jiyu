package com.haise.jiyu.source.wprest

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

class WpRestNovelSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: WpRestNovelSource

    private val categoriesJson = """[
        {"id":1,"name":"Uncategorized","slug":"uncategorized","count":5,"link":"https://x.test/category/uncategorized/"},
        {"id":39,"name":"My &amp; Novel","slug":"my-novel","count":17,"link":"https://x.test/category/my-novel/","description":"<p>Series description.</p>"}
    ]"""

    private val postsJson = """[
        {"id":101,"date":"2025-01-02T10:00:00","link":"https://x.test/2025/01/ch-1/","slug":"ch-1","title":{"rendered":"Chapter 1"}},
        {"id":102,"date":"2025-01-09T10:00:00","link":"https://x.test/2025/01/ch-2/","slug":"ch-2","title":{"rendered":"Chapter 2"}}
    ]"""

    private val postJson = """[{"content":{"rendered":"<p>Chapter text here.</p><p>More.</p>"}}]"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/wp-json/wp/v2/categories?") && "slug=" in path ->
                        MockResponse().setBody("""[{"id":39,"name":"My Novel","slug":"my-novel","description":"<p>Series description.</p>"}]""")
                    path.startsWith("/wp-json/wp/v2/categories") -> MockResponse().setBody(categoriesJson)
                    path.startsWith("/wp-json/wp/v2/posts?categories=39") -> MockResponse().setBody(postsJson)
                    path.startsWith("/wp-json/wp/v2/posts?slug=") -> MockResponse().setBody(postJson)
                    else -> MockResponse().setResponseCode(404).setBody("[]")
                }
            }
        }
        server.start()
        val api = server.url("/wp-json/wp/v2").toString().trimEnd('/')
        source = WpRestNovelSource(
            id = "ext:test", name = "Test", baseUrl = "https://x.test", apiBase = api,
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `browse returns categories as series, uncategorized excluded`() = runTest {
        val r = source.getPopular(1)
        assertEquals(1, r.size)
        assertEquals("My & Novel", r[0].title)
        assertEquals("https://x.test/category/my-novel/", r[0].url)
        assertEquals("Series description.", r[0].description)
    }

    @Test
    fun `chapters resolve category slug to id and list posts`() = runTest {
        val chapters = source.getChapterList(SManga("ext:test", "https://x.test/category/my-novel/", "T", null))
        assertEquals(2, chapters.size)
        // Nejnovější první (kontrakt) - API dodává asc, výsledek obrácený
        assertEquals("Chapter 2", chapters[0].name)
        assertEquals("Chapter 1", chapters[1].name)
        assertTrue(chapters[0].dateUpload > 0)
    }

    @Test
    fun `page list fetches post content by slug`() = runTest {
        val pages = source.getPageList(
            SChapter("ext:test", "https://x.test/category/my-novel/", "https://x.test/2025/01/ch-1/", "Ch 1", 1f, 0L)
        )
        assertEquals(1, pages.size)
        assertEquals("novel://text", pages[0].imageUrl)
        assertTrue(pages[0].url.contains("Chapter text here."))
    }
}

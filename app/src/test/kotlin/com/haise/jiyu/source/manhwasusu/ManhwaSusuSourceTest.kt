package com.haise.jiyu.source.manhwasusu

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

class ManhwaSusuSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ManhwaSusuSource

    private fun cardsHtml(vararg entries: Pair<String, String>) = buildString {
        append("<html><body><div class=\"grid\">")
        entries.forEach { (slug, title) ->
            append(
                """
                <div class="flex overflow-hidden rounded-lg bg-white">
                  <a href="/read/$slug/" link:app data-prefetch class="relative">
                    <img alt="$title" data-src="https://s1.manhwature.com/cdn/wp-content/uploads/$slug-193x278.jpg" src="data:image/png;base64,AAAA" class="lazyimage">
                  </a>
                  <div class="w-7/12 p-3">
                    <h2><a href="/read/$slug/">$title</a></h2>
                  </div>
                </div>
                """.trimIndent(),
            )
        }
        append("</div></body></html>")
    }

    private val detailHtml = """
        <html><body>
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"ComicSeries","name":"Secret Class","description":"Dae Ho was adopted by his father's friend.","image":"https://s1.manhwature.com/wp-content/uploads/secret-class-01.jpg","author":[],"genre":["Adult","Drama"],"inLanguage":"en","url":"https://manhwasusu.com/read/secret-class/","numberOfEpisodes":320}</script>
        <div class="mt-4 flex w-full items-center">
          <div class="w-full rounded-l-full bg-red-800 px-4 py-3 text-center text-sm font-bold text-white">Comic</div>
          <div class="w-full rounded-r-full bg-green-800 px-4 py-3 text-center text-sm font-bold text-white">on-going</div>
        </div>
        <div class="mt-4 flex w-full items-center">
          <a href="/read/secret-class/chapter-1/" class="w-full rounded-l-full bg-indigo-700">First Chapter</a>
          <a href="/read/secret-class/chapter-310/" class="w-full rounded-r-full bg-violet-700">Last Chapter</a>
        </div>
        <div class="mt-4 w-full">
          <h2>Chapter List</h2>
          <div class="mt-4 flex max-h-96 flex-col gap-2">
            <a href="/read/secret-class/chapter-310/" class="text-md flex items-center rounded-md">
              <span class="mr-2"><svg></svg></span>
              <div><p>Chapter 310 </p><p class="text-xs font-medium">2 week ago</p></div>
            </a>
            <a href="/read/secret-class/chapter-309.6/" class="text-md flex items-center rounded-md">
              <span class="mr-2"><svg></svg></span>
              <div><p>Chapter 309.6 </p><p class="text-xs font-medium">1 mth ago</p></div>
            </a>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    // Realny web: stranky jsou <img data-src=".../{serie}/chapters/{id}/{file}.jpg">,
    // src je jen lazy placeholder (covers maji /covers/ v ceste a filtruji se pryc).
    private val chapterHtml = """
        <html><body>
        <img alt="Secret Class" src="https://s1.manhwature.com/wp-content/uploads/secret-class-01.jpg">
        <div class="reading">
          <img src="/readerarea.svg" data-src="https://s1.manhwature.com/abc/secret-class-01/chapters/97765/001.jpg">
          <img src="/readerarea.svg" data-src="https://s1.manhwature.com/abc/secret-class-01/chapters/97765/002.jpg">
        </div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/popular?page=1" -> MockResponse().setBody(
                        cardsHtml("never-just-friends" to "Never Just Friends", "secret-class" to "Secret Class"),
                    )
                    path.startsWith("/search/secret") -> MockResponse().setBody(
                        cardsHtml("secret-class" to "Secret Class"),
                    )
                    path == "/read/secret-class/" -> MockResponse().setBody(detailHtml)
                    path == "/read/secret-class/chapter-310/" -> MockResponse().setBody(chapterHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ManhwaSusuSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular reads title from img alt and cover from data-src`() = runTest {
        val result = source.getPopular(1)
        assertEquals(2, result.size)
        assertEquals("Never Just Friends", result[0].title)
        assertEquals("https://manhwasusu.com/read/never-just-friends/", result[0].url)
        assertTrue(result[0].coverUrl!!.endsWith("never-just-friends-193x278.jpg"))
    }

    @Test
    fun `search reuses the same card parsing on the search route`() = runTest {
        val result = source.search("secret")
        assertEquals(1, result.size)
        assertEquals("Secret Class", result[0].title)
    }

    @Test
    fun `getMangaDetails reads the ComicSeries JSON-LD block and status label`() = runTest {
        val manga = source.getPopular(1).first { it.url.endsWith("secret-class/") }
        val details = source.getMangaDetails(manga)
        assertEquals("Secret Class", details.title)
        assertEquals("Dae Ho was adopted by his father's friend.", details.description)
        assertEquals(listOf("Adult", "Drama"), details.genres)
        assertEquals("on-going", details.status)
    }

    @Test
    fun `getChapterList ignores nav buttons and keeps only real chapter rows`() = runTest {
        val manga = source.getPopular(1).first { it.url.endsWith("secret-class/") }
        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(310f, chapters[0].chapterNumber)
        assertEquals(309.6f, chapters[1].chapterNumber)
    }

    @Test
    fun `getPageList scopes images to the current chapter folder`() = runTest {
        val manga = source.getPopular(1).first { it.url.endsWith("secret-class/") }
        val chapter = source.getChapterList(manga).first { it.chapterNumber == 310f }
        val pages = source.getPageList(chapter)
        assertEquals(2, pages.size)
        assertTrue(pages[0].url.endsWith("/chapters/97765/001.jpg"))
        assertTrue(pages[1].url.endsWith("/chapters/97765/002.jpg"))
    }

    @Test
    fun `malformed responses return empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not html")
        }
        server.start()
        val emptySource = ManhwaSusuSource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}

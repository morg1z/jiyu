package com.haise.jiyu.source.comickart

import com.haise.jiyu.source.MangaFilter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer testy [ComicKArtSource] - fixtury kopiruji realne tvary
 * odpovedi comick.art (overeno zive 2026-09-23): /api/search, /api/comics/top,
 * /api/chapters/latest, /api/comics/{slug}/chapter-list, /comic/{slug}
 * (#comic-data), /comic/{slug}/{hid}-chapter-{n}-{lang} (#sv-data), /api/metadata.
 */
class ComicKArtSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ComicKArtSource

    // >=10 polozek - mensi davka se bere jako konec (cursor se pak vynuluje),
    // realna stranka comick.art vraci ~20 vysledku.
    private val searchJson = """
        {"data":[
          {"title":"Solo Leveling","slug":"00-solo-leveling","country":"KR",
           "default_thumbnail":"https://cdn1.example/covers/sl.webp",
           "content_rating":"safe","bayesian_rating":"9.37","last_chapter":200.5},
          {"title":"Other Title","slug":"other","country":"JP","default_thumbnail":null},
          {"title":"F3","slug":"f3","country":"JP"},{"title":"F4","slug":"f4","country":"JP"},
          {"title":"F5","slug":"f5","country":"JP"},{"title":"F6","slug":"f6","country":"JP"},
          {"title":"F7","slug":"f7","country":"JP"},{"title":"F8","slug":"f8","country":"JP"},
          {"title":"F9","slug":"f9","country":"JP"},{"title":"F10","slug":"f10","country":"JP"}
        ],"next_cursor":"abc123","per_page":20}
    """.trimIndent()

    private val searchJsonLast = """
        {"data":[
          {"title":"Solo Leveling","slug":"00-solo-leveling","country":"KR",
           "default_thumbnail":"https://cdn1.example/covers/sl.webp","last_chapter":200.5}
        ],"next_cursor":null,"per_page":20}
    """.trimIndent()

    private val topJson = """
        {"data":[
          {"title":"Top One","slug":"top-one","country":"JP","default_thumbnail":"https://cdn1.example/t.webp"}
        ]}
    """.trimIndent()

    private val latestJson = """
        {"data":[
          {"slug":"dup","title":"Dup","default_thumbnail":"https://cdn1.example/d.webp","content_rating":"safe","is_ended":false},
          {"slug":"dup","title":"Dup","default_thumbnail":"https://cdn1.example/d.webp","content_rating":"safe","is_ended":false},
          {"slug":"other","title":"Other","default_thumbnail":null,"content_rating":"safe","is_ended":false}
        ]}
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <script id="comic-data">{
          "title":"Solo Leveling","desc":"<p>Popisek &amp; text.</p>","country":"KR",
          "status":2,"year":2018,"bayesian_rating":"9.37",
          "authors":[{"name":"Chugong"}],"artists":[{"name":"Dubu"}],
          "md_titles":[{"title":"Solo Leveling"},{"title":"Na Honjaman Level-Up"}],
          "md_comic_md_genres":[{"md_genres":{"name":"Action","slug":"action"}},
                                {"md_genres":{"name":"Fantasy","slug":"fantasy"}}],
          "content_rating":"safe","last_chapter":200.5,"chapter_count":4144,
          "demographic_name":null,"translation_completed":true,"has_anime":true,
          "final_chapter":null,"final_volume":null
        }</script>
        </body></html>
    """.trimIndent()

    private val chaptersJsonP1 = """
        {"data":[
          {"hid":"H200","chap":"200","vol":null,"title":null,"lang":"en",
           "group_name":["Asura"],"md_chapters_groups":[{"md_groups":{"title":"Asura","slug":"asura"}}],
           "up_count":50,"created_at":"2026-01-10T10:00:00.000000Z"},
          {"hid":"H200B","chap":"200","vol":null,"title":null,"lang":"en",
           "group_name":["B-Team"],"up_count":5,"created_at":"2026-01-09T10:00:00.000000Z"}
        ],"pagination":{"current_page":1,"per_page":60,"last_page":2}}
    """.trimIndent()

    private val chaptersJsonP2 = """
        {"data":[
          {"hid":"H1","chap":"1","vol":"1","title":"Start","lang":"en",
           "group_name":["Asura"],"up_count":100,"created_at":"2018-01-01T00:00:00.000000Z"}
        ],"pagination":{"current_page":2,"per_page":60,"last_page":2}}
    """.trimIndent()

    private val chapterHtml = """
        <html><body>
        <script id="sv-data">{
          "chapter":{"hid":"H200","chap":"200",
            "images":[
              {"url":"https://cdn1.example/0.jpg","w":1778,"h":1000},
              {"url":"https://cdn1.example/1.jpg","w":1778,"h":1000}
            ]}
        }</script>
        </body></html>
    """.trimIndent()

    private val metadataJson = """
        {"genres":[{"id":1,"name":"Action","slug":"action","group":"Genre"},
                  {"id":2,"name":"Fantasy","slug":"fantasy","group":"Genre"}],
         "tags":[{"id":9,"name":"Isekai","slug":"isekai","group":"Theme"}]}
    """.trimIndent()

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/comic/test-comic" -> MockResponse().setBody(detailHtml)
                    path == "/comic/test-comic/H200-chapter-200-en" -> MockResponse().setBody(chapterHtml)
                    path == "/api/comics/test-comic/chapter-list?page=1" -> MockResponse().setBody(chaptersJsonP1)
                    path == "/api/comics/test-comic/chapter-list?page=2" -> MockResponse().setBody(chaptersJsonP2)
                    path.startsWith("/api/chapters/latest") -> MockResponse().setBody(latestJson)
                    path.startsWith("/api/comics/top") -> MockResponse().setBody(topJson)
                    path.startsWith("/api/metadata") -> MockResponse().setBody(metadataJson)
                    // Cursor na strane 2 = druha davka
                    path.contains("/api/search") && path.contains("cursor=") -> MockResponse().setBody(searchJsonLast)
                    path.contains("/api/search") -> MockResponse().setBody(searchJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = ComicKArtSource(OkHttpClient(), server.url("").toString().removeSuffix("/"))
    }

    @After
    fun teardown() = server.shutdown()

    @Test
    fun `search hits api search with query and type`() = runTest {
        val r = source.search("solo leveling")
        assertEquals(10, r.size)
        assertEquals("Solo Leveling", r[0].title)
        assertEquals(server.url("/comic/00-solo-leveling").toString(), r[0].url)
        assertEquals("https://cdn1.example/covers/sl.webp", r[0].coverUrl)
        assertEquals("MANHWA", r[0].contentType) // country "KR" (uppercase) -> MANHWA
        assertEquals(200.5f, r[0].lastChapter)
        val req = server.takeRequest()
        assertTrue(req.path.orEmpty().contains("/api/search"))
        assertTrue(req.path.orEmpty().contains("type=comic"))
        assertTrue(req.path.orEmpty().contains("q=solo"))
    }

    @Test
    fun `search page 2 uses cursor`() = runTest {
        source.search("solo")            // strana 1 -> ulozi next_cursor=abc123
        val r = source.search("solo", 2) // strana 2 -> musi nest cursor
        assertEquals(1, r.size)
        val paths = listOf(server.takeRequest().path, server.takeRequest().path)
        assertTrue(paths[1].orEmpty().contains("cursor=abc123"))
    }

    @Test
    fun `popular without filter uses top feed`() = runTest {
        val r = source.getPopular(1)
        assertEquals("Top One", r[0].title)
        assertTrue(server.takeRequest().path.orEmpty().contains("/api/comics/top?days=7&type=follow"))
        // strana 7+ = konec (6 stranek mapovani)
        assertTrue(source.getPopular(7).isEmpty())
    }

    @Test
    fun `latest sort uses chapters latest and dedupes by slug`() = runTest {
        val r = source.getPopular(1, MangaFilter(sortBy = "latest"))
        assertEquals(2, r.size) // "dup" jen jednou
        assertTrue(server.takeRequest().path.orEmpty().contains("/api/chapters/latest"))
    }

    @Test
    fun `genre filter goes to api search`() = runTest {
        source.getPopular(1, MangaFilter(genres = listOf("action")))
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("/api/search"))
        assertTrue(path.contains("genres=action"))
    }

    @Test
    fun `details parses comic-data json`() = runTest {
        val m = source.getMangaDetails(
            com.haise.jiyu.source.SManga("comickart", server.url("/comic/test-comic").toString(), "X", null),
        )
        assertEquals("Solo Leveling", m.title)
        assertEquals("Popisek & text.", m.description)
        assertEquals("MANHWA", m.contentType)
        assertEquals("Dokončeno", m.status)
        assertEquals("Chugong", m.author)
        assertEquals(listOf("Action", "Fantasy"), m.genres)
        assertEquals(2018, m.year)
        assertEquals(9.37, m.rating!!, 0.01)
        assertEquals(true, m.translationCompleted)
        assertEquals(true, m.hasAnime)
        assertNull(m.finalChapter)
        assertTrue("Na Honjaman Level-Up" in m.alternateTitles)
        assertEquals(200.5f, m.lastChapter)
    }

    @Test
    fun `chapter list paginates and builds page urls`() = runTest {
        val chapters = source.getChapterList(
            com.haise.jiyu.source.SManga("comickart", server.url("/comic/test-comic").toString(), "X", null),
        )
        assertEquals(3, chapters.size) // 2 verze kap.200 + kap.1
        // Nejnovější napřed; u dvou verzí 200 dřív Asura (vice up_count)
        assertEquals(200f, chapters[0].chapterNumber)
        assertEquals("Asura", chapters[0].scanlationGroup)
        assertEquals(200f, chapters[1].chapterNumber)
        assertEquals("B-Team", chapters[1].scanlationGroup)
        assertEquals(1f, chapters[2].chapterNumber)
        assertEquals("Vol.1 Ch.1 – Start", chapters[2].name)
        // URL stranky kapitoly ve tvaru /comic/{slug}/{hid}-chapter-{chap}-{lang}
        assertTrue(chapters[0].url.endsWith("/comic/test-comic/H200-chapter-200-en"))
    }

    @Test
    fun `page list reads images from sv-data`() = runTest {
        val pages = source.getPageList(
            com.haise.jiyu.source.SChapter(
                sourceId = "comickart", mangaUrl = "", chapterNumber = 200f,
                url = server.url("/comic/test-comic/H200-chapter-200-en").toString(),
                name = "Ch.200", dateUpload = 0L,
            ),
        )
        assertEquals(2, pages.size)
        assertEquals("https://cdn1.example/0.jpg", pages[0].imageUrl)
        assertEquals("https://cdn1.example/0.jpg", source.getImageUrl(pages[0]))
    }

    @Test
    fun `available tags splits genres and tags by kind`() = runTest {
        val tags = source.getAvailableTags()
        assertEquals(setOf("action", "fantasy"), tags.filter { it.kind == "genre" }.map { it.id }.toSet())
        assertEquals(setOf("isekai"), tags.filter { it.kind == "tag" }.map { it.id }.toSet())
    }

    @Test
    fun `advanced filters map to site api params`() = runTest {
        source.getPopular(1, MangaFilter(
            genres = listOf("action"),
            excludeGenres = listOf("horror"),
            tags = listOf("webtoon"),
            excludeTags = listOf("tragedy"),
            demographic = listOf("1"),
            comicTypes = listOf("kr"),
            minChapters = 50,
            createdRangeDays = 30,
            status = "cancelled",
            sortAscending = true,
        ))
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("/api/search"))
        assertTrue(path.contains("genres=action"))
        assertTrue(path.contains("excludes=horror"))
        assertTrue(path.contains("tags=webtoon"))
        assertTrue(path.contains("excluded_tags=tragedy"))
        assertTrue(path.contains("demographic=1"))
        assertTrue(path.contains("country=kr"))
        assertTrue(path.contains("minimum=50"))
        assertTrue(path.contains("time=30"))
        assertTrue(path.contains("status=3"))
        assertTrue(path.contains("order_direction=asc"))
    }
}

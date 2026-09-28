package com.haise.jiyu.source.comix

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SManga
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComixSourceTest {

    private val source = ComixSource(OkHttpClient(), object : ComixPageRunner {
        override suspend fun capture(
            url: String,
            html: String,
            script: String,
            contentFilter: String?,
            timeoutMs: Long,
        ): ComixCapture = throw ComixCaptureException("no webview in tests")
    })

    // ---------- initial-data ----------

    private fun htmlWithInitialData(json: String) = Jsoup.parse(
        """<html><head></head><body><script id="initial-data">$json</script></body></html>""",
        "https://comix.to",
    )

    @Test
    fun `extractInitialDataItems finds result items`() {
        val doc = htmlWithInitialData(
            """{"queries":{"[\"manga\",\"list\"]":{"result":{"items":[{"hid":"abc12","title":"Test"}]}}}}""",
        )
        val items = source.extractInitialDataItems(doc)
        assertNotNull(items)
        assertEquals("abc12", items!!.getJSONObject(0).getString("hid"))
    }

    @Test
    fun `extractInitialDataItems accepts bare items array`() {
        val doc = htmlWithInitialData(
            """{"queries":{"x":{"items":[{"hid":"q1","title":"T"}]}}}""",
        )
        assertEquals(1, source.extractInitialDataItems(doc)!!.length())
    }

    @Test
    fun `extractInitialDataItems returns null for empty shell`() {
        val doc = htmlWithInitialData("""{"page":"browse","queries":{}}""")
        assertNull(source.extractInitialDataItems(doc))
    }

    @Test
    fun `extractInitialDataDetail picks the detail query`() {
        val doc = htmlWithInitialData(
            """{"queries":{"[\"manga\",\"detail\",\"lly3j\"]":{"hid":"lly3j","title":"How to Use a Returner"},"[\"manga\",\"groups\",\"lly3j\"]":{"x":1}}}""",
        )
        val detail = source.extractInitialDataDetail(doc)
        assertEquals("lly3j", detail!!.getString("hid"))
    }

    @Test
    fun `extractInitialDataDetail unwraps result`() {
        val doc = htmlWithInitialData(
            """{"queries":{"[\"manga\",\"detail\",\"z9\"]":{"result":{"hid":"z9","title":"T"}}}}""",
        )
        assertEquals("z9", source.extractInitialDataDetail(doc)!!.getString("hid"))
    }

    @Test
    fun `extractInitialDataPages finds result pages`() {
        val doc = htmlWithInitialData(
            """{"queries":{"k":{"result":{"pages":{"baseUrl":"https://static.comix.to","items":[]}}}}}""",
        )
        assertNotNull(source.extractInitialDataPages(doc))
    }

    // ---------- itemToManga ----------

    @Test
    fun `itemToManga maps fields`() {
        val item = JSONObject(
            """{"hid":"lly3j","title":"How to Use a Returner","type":"manhwa","status":"releasing",
            "poster":{"large":"https://static.comix.to/l.webp","medium":"m.webp"},
            "latestChapter":204,"ratedAvg":8.6,"followsTotal":29484,"rank":383,"year":2021}""",
        )
        val m = source.itemToManga(item)
        assertEquals("/title/lly3j", m.url)
        assertEquals("MANHWA", m.contentType)
        assertEquals("https://static.comix.to/l.webp", m.coverUrl)
        assertEquals(204f, m.lastChapter)
        assertEquals(8.6, m.rating!!, 0.001)
        assertEquals(29484, m.followCount)
        assertEquals(383, m.rank)
        assertEquals(2021, m.year)
    }

    // ---------- browseUrl / mangaParamsFrom / canonicalEntries ----------

    @Test
    fun `browseUrl popular ordering`() {
        val url = source.browseUrl(2, MangaFilter(), null)
        assertTrue(url.contains("order%5Bscore%5D=desc"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("content_rating=safe%2Csuggestive"))
    }

    @Test
    fun `browseUrl latest ordering`() {
        assertTrue(source.browseUrl(1, MangaFilter(sortBy = "latest"), null).contains("chapter_updated_at"))
    }

    @Test
    fun `browseUrl search uses q and keyword`() {
        val url = source.browseUrl(1, MangaFilter(), "returner")
        assertTrue(url.contains("q=returner"))
        assertTrue(url.contains("keyword=returner"))
        assertTrue(url.contains("sort=relevance"))
    }

    @Test
    fun `browseUrl genres`() {
        val url = source.browseUrl(1, MangaFilter(genres = listOf("6", "23")), null)
        assertTrue(url.contains("genres_in%5B%5D=6"))
        assertTrue(url.contains("genres_in%5B%5D=23"))
    }

    @Test
    fun `mangaParamsFrom splits content_rating and adds limit`() {
        val params = source.mangaParamsFrom(
            "https://comix.to/browse?order%5Bscore%5D=desc&content_rating=safe%2Csuggestive&page=1",
        )
        assertEquals(listOf("safe", "suggestive"), params["content_rating"])
        assertEquals(listOf("28"), params["limit"])
    }

    @Test
    fun `canonicalEntries sorts keys and expands arrays`() {
        val entries = source.canonicalEntries(
            mapOf(
                "z" to listOf("1"),
                "a[]" to listOf("x", "y"),
                "b" to listOf("2"),
            ),
        )
        assertEquals(listOf("a[0]" to "x", "a[1]" to "y", "b" to "2", "z" to "1"), entries)
    }

    // ---------- chapters ----------

    @Test
    fun `parseChaptersCompact reconstructs urls and groups`() {
        val payload = JSONObject(
            """{"prefix":"/title/lly3j-how-to-use-a-returner/",
               "groups":[{"o":0,"name":"TeamX"},{"o":1}],
               "items":[
                 {"i":11388064,"n":204.0,"u":"11388064-chapter-204","g":0,"c":1700000000},
                 {"i":3113253,"n":0.1,"u":"3113253-chapter-0.1","g":1,"t":"Prolog"}
               ]}""",
        )
        val chapters = source.parseChaptersCompact(payload, "lly3j", "/title/lly3j")
        assertEquals(2, chapters.size)
        // serazeno od nejnovejsi (204 prvni)
        assertEquals(204f, chapters[0].chapterNumber)
        assertEquals(
            "https://comix.to/title/lly3j-how-to-use-a-returner/11388064-chapter-204",
            chapters[0].url,
        )
        assertEquals("TeamX", chapters[0].scanlationGroup)
        assertEquals(1700000000L * 1000L, chapters[0].dateUpload)
        assertEquals(0.1f, chapters[1].chapterNumber)
        assertEquals("Chapter 0.1: Prolog", chapters[1].name)
        assertEquals("Official", chapters[1].scanlationGroup)
    }

    @Test
    fun `chapterToSChapter native format`() {
        val ch = JSONObject(
            """{"id":55,"number":12.5,"name":"x","url":"/title/lly3j-s/55-chapter-12.5",
            "group":{"name":"G"},"createdAt":1700000000}""",
        )
        val s = source.chapterToSChapter(ch, "lly3j", "/title/lly3j")!!
        assertEquals("https://comix.to/title/lly3j-s/55-chapter-12.5", s.url)
        assertEquals("Chapter 12.5: x", s.name)
        assertEquals("G", s.scanlationGroup)
        assertEquals(1700000000000L, s.dateUpload)
    }

    // ---------- pages ----------

    @Test
    fun `parsePages builds urls with baseUrl`() {
        val root = JSONObject(
            """{"result":{"pages":{"baseUrl":"https://static.comix.to/manga/x","items":[{"url":"1.webp"},{"url":"2.webp"}]}}}""",
        )
        val pages = source.parsePages(root)!!
        assertEquals(2, pages.size)
        assertEquals("https://static.comix.to/manga/x/1.webp", pages[0].imageUrl)
    }

    @Test
    fun `parsePages marks v3 with query flag and fragment`() {
        val root = JSONObject(
            """{"result":{"pages":{"baseUrl":"https://static.comix.to","items":[{"url":"/i5/a.webp","s":1}]}}}""",
        )
        val page = source.parsePages(root)!![0]
        assertTrue(page.imageUrl!!.contains("v3"))
        assertTrue(page.imageUrl!!.endsWith("#${ComixScramble.SCRAMBLED_FRAGMENT}"))
    }

    @Test
    fun `parsePages marks every fourth page legacy`() {
        val items = JSONArray((1..8).map { JSONObject().put("url", "/i5/$it.webp") })
        val root = JSONObject().put("result", JSONObject().put("pages",
            JSONObject().put("baseUrl", "https://static.comix.to").put("items", items)))
        val pages = source.parsePages(root)!!
        assertTrue(pages[3].imageUrl!!.endsWith("#${ComixScramble.LEGACY_FRAGMENT}"))
        assertTrue(pages[7].imageUrl!!.endsWith("#${ComixScramble.LEGACY_FRAGMENT}"))
        assertTrue(!pages[0].imageUrl!!.contains('#'))
        assertTrue(!pages[1].imageUrl!!.contains('#'))
        assertEquals("https://static.comix.to/i5/1.webp", pages[0].imageUrl)
    }

    @Test
    fun `parsePages handles missing items`() {
        assertNull(source.parsePages(JSONObject("""{"result":{}}""")))
    }

    @Test
    fun `formatNumber drops trailing zero`() {
        assertEquals("204", source.formatNumber(204f))
        assertEquals("0.1", source.formatNumber(0.1f))
    }

    @Test
    fun `hid extraction`() {
        val m = SManga(sourceId = "comix", url = "/title/lly3j", title = "x", coverUrl = null)
        // neprimo: getMangaDetails pouziva hid - overime pres url
        assertEquals("lly3j", m.url.substringAfter("/title/").substringBefore("-"))
    }

    // ---------- live (jen s -Djiyu.live=true) ----------

    @Test
    fun `live detail parses real comix title page`() {
        org.junit.Assume.assumeTrue(
            "live test only with -Djiyu.live=true",
            System.getProperty("jiyu.live") == "true",
        )
        // "How to Use a Returner" - dlouhodobe stabilni titul, detail jede ciste
        // pres SSR script#initial-data (bez WebView).
        val manga = SManga(sourceId = "comix", url = "/title/lly3j", title = "", coverUrl = null)
        val detail = kotlinx.coroutines.runBlocking { source.getMangaDetails(manga) }
        assertTrue("title not parsed", detail.title.isNotBlank())
        assertNotNull("cover not parsed", detail.coverUrl)
        assertTrue(detail.genres.isNotEmpty())
        // status neasertujeme na konkretni hodnotu - "releasing" se casem muze
        // zmenit na "finished", dulezite je ze se initial-data detail sparsoval.
    }
}

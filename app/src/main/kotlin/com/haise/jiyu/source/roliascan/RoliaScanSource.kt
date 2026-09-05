package com.haise.jiyu.source.roliascan

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * roliascan.com - bespoke WordPress ("mangapeak" motiv) s vlastnim REST API,
 * ne Madara/MangaThemesia. Vypis (i hledani, i zanrovy filtr) jede pres JEDEN
 * spolecny endpoint POST "/wp-json/manga/v1/load" s JSON telem
 * {page, search, years:"[]", genres:"[ID,...]", types:"[]", statuses:"[]",
 * sort, genreMatchMode:"any"} - "genres"/"years"/atd. jsou pole ZAKODOVANA
 * JAKO STRING (ne vnorene JSON pole), overeno zive (funkcni s genres:"[5]",
 * vraci jinou sadu nez bez filtru). Zadny nonce netreba (overeno zive -
 * endpoint funguje i bez nej, i kdyz stranka jeden do HTML embedduje pro JS
 * pouzivane jinde). Platne "sort" hodnoty rozlousknute z mangalist.js:
 * "post_desc" (nejnovejsi, vychozi), "popular_desc" (popularni) - overeno
 * zive, ze davaji odlisne sady i strankovani (strana 2 != strana 1).
 * Fulltextove hledani je server-side funkcni pres "search" pole primo v tomhle
 * tele (overeno zive).
 *
 * Detail mangy je staticky HTML (JSON-LD "ComicSeries" blok da spolehlive
 * typ/stav/autora - pozor, JSON-LD pole se jmenuje "genre", ale nese typ
 * obsahu Manga/Manhwa/Manhua/Novel, ne zanry - skutecne zanry jsou zvlast
 * v `a[href*="/tag/"]`), manga ID je v `body[data-manga-id]`.
 *
 * Seznam KAPITOL ale neni v static HTML - dotahuje se JS-em z GET
 * "/auth/manga-chapters?manga_id=...&_t=...&_ts=..." - "_t" je tzv.
 * "anti-scraping token", ale je to jen deterministicky
 * md5(timestamp + "mng_ch_" + aktualniHodinaUTC[YYYYMMDDHH]).substring(0,16)
 * (rozlousknuto primo z verejne dostupneho mangapeak/assets/js/manga.js),
 * zadny skutecny server-side secret navic netreba - overeno zive, endpoint
 * s takhle spocitanym tokenem funguje. Stranky kapitoly jsou pak GET
 * "/auth/chapter-content?chapter_id=..." UPLNE BEZ tokenu (overeno zive),
 * vraci primo JSON pole plnych CDN URL obrazku bez hotlink-protection.
 * "chapter_id" se ziska z konce URL kapitoly (posledni "-cislo" pred
 * koncovym lomitkem, napr. ".../ch173-280174/" -> "280174").
 */
@Singleton
class RoliaScanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "roliascan"
    override val name = "Rolia Scan"
    override val homepageUrl get() = base
    private val base = "https://roliascan.com"

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/browse/"))
            val tags = doc.select("button.genre-btn[data-value]").mapNotNull { btn ->
                val idVal = btn.attr("data-value").trim().ifBlank { return@mapNotNull null }
                val label = btn.selectFirst("div")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = idVal, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun loadApi(page: Int, search: String, genreId: String?, sort: String): String {
        val genresParam = if (genreId != null) "[$genreId]" else "[]"
        val json = JSONObject()
            .put("page", page)
            .put("search", search)
            .put("years", "[]")
            .put("genres", genresParam)
            .put("types", "[]")
            .put("statuses", "[]")
            .put("sort", sort)
            .put("genreMatchMode", "any")
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/wp-json/manga/v1/load")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .post(body)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow("$base/wp-json/manga/v1/load") }
    }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manhwa" -> "MANHWA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANGA"
    }

    private fun parseListJson(json: String): List<SManga> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").ifBlank { return@mapNotNull null }
            val title = o.optString("title").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id, url = url, title = title,
                coverUrl = o.optString("cover").ifBlank { null },
                description = o.optString("description").ifBlank { null },
                status = o.optString("status").ifBlank { null },
                year = o.optString("year").toIntOrNull(),
                contentType = normalizeContentType(o.optString("type")),
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val sort = if (filter.sortBy == "latest") "post_desc" else "popular_desc"
            val genre = filter.genres.firstOrNull()
            parseListJson(loadApi(page, "", genre, sort))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            val sort = if (filter.sortBy == "latest") "post_desc" else "popular_desc"
            parseListJson(loadApi(page, query.trim(), genre, sort))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val ld = doc.select("script[type=application/ld+json]").map { it.data() }
                .firstOrNull { it.contains("\"ComicSeries\"") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val genres = doc.select("a[href*=\"/tag/\"]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("#description-content-tab")?.text()?.trim()?.ifBlank { null }
                    ?: ld?.optString("description")?.ifBlank { null } ?: manga.description,
                genres = genres.ifEmpty { manga.genres },
                author = ld?.optJSONObject("author")?.optString("name")?.ifBlank { null } ?: manga.author,
                status = ld?.optString("status")?.ifBlank { null } ?: manga.status,
                contentType = normalizeContentType(ld?.optString("genre") ?: manga.contentType),
            )
        } catch (_: Exception) { manga }
    }

    private fun mangaId(doc: Document): String? = doc.selectFirst("body")?.attr("data-manga-id")?.ifBlank { null }

    /** Rozlousknuto z verejneho mangapeak/assets/js/manga.js ("anti-scraping token"
     * generateToken()) - deterministicky, zadny skutecny server-side secret. */
    private fun antiScrapingToken(timestampSeconds: Long): String {
        val hourFmt = SimpleDateFormat("yyyyMMddHH", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val hour = hourFmt.format(Date(timestampSeconds * 1000))
        val secret = "mng_ch_$hour"
        val digest = MessageDigest.getInstance("MD5").digest((timestampSeconds.toString() + secret).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val mangaId = mangaId(doc) ?: return@withContext emptyList()
            val chapters = mutableListOf<SChapter>()
            var offset = 0
            while (true) {
                val ts = System.currentTimeMillis() / 1000
                val token = antiScrapingToken(ts)
                val url = "$base/auth/manga-chapters?manga_id=$mangaId&offset=$offset&limit=500&order=DESC&_t=$token&_ts=$ts"
                val json = JSONObject(get(url))
                if (!json.optBoolean("success")) break
                val arr = json.optJSONArray("chapters") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val chUrl = c.optString("url").ifBlank { continue }
                    val numText = c.optString("chapter")
                    val num = numText.toFloatOrNull() ?: continue
                    chapters += SChapter(
                        sourceId = id, mangaUrl = manga.url, url = chUrl,
                        name = "Chapter $numText",
                        chapterNumber = num,
                        dateUpload = 0L,
                        scanlationGroup = c.optString("group_name").ifBlank { null },
                    )
                }
                offset += arr.length()
                if (!json.optBoolean("has_more")) break
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    private val chapterIdRegex = Regex("""-(\d+)/?$""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterId = chapterIdRegex.find(chapter.url.trimEnd('/'))?.groupValues?.get(1) ?: return@withContext emptyList()
            val json = JSONObject(get("$base/auth/chapter-content?chapter_id=$chapterId"))
            if (!json.optBoolean("success")) return@withContext emptyList()
            val images = json.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapIndexedNotNull { i, _ ->
                val url = images.optString(i)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

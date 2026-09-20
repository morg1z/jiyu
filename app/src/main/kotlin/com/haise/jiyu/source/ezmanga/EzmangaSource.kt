package com.haise.jiyu.source.ezmanga

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.normalizeContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ezmanga.org - bespoke Angular (SSR/Angular Universal) web, NE Madara/WordPress
 * (zadny generator meta tag, `<app-root ng-server-context="ssr">`). Frontend
 * pouziva pri hydrataci verejne pristupne JSON REST API na subdomene
 * "vapi.ezmanga.org" (overeno zive - stejne URL jsou videt primo v embeddovanem
 * Angular TransferState blobu v HTML), takze appka jde primo na API misto
 * HTML scrapovani (Vzor A jako MangaDexSource) - zadna autentizace/token
 * netreba, funguje s prostym GET.
 *
 * Vypis "GET /api/v1/series?page=N" (20/stranku, `totalPages` v odpovedi),
 * volitelny "&genre={slug}" filtr FUNKCNI (overeno zive - `totalItems` klesne
 * z 756 na 587 pro "romance"), "&sort=popular" FUNKCNI (jine poradi nez
 * vychozi/"latest", overeno zive) - zadny "&q="/"&search=" na tomhle endpointu
 * (400 "property q should not exist"). Fulltextove hledani ma VLASTNI endpoint
 * "GET /api/v1/series/search?q=..." (bez genre parametru - i ten vraci 400,
 * proto se pri hledani zanrovy filtr ignoruje), stejne strankovani jako vypis.
 * Zoznam zanru "GET /api/v1/series/genres" -> [{id,name,slug}], pouzitelne
 * primo jako tag filter.
 *
 * Detail "GET /api/v1/series/{slug}" vraci popis jako HTML (Jsoup na text),
 * autora/artistu jako prosty text, "type" (pozorovano jen "MANHWA", ale
 * normalizovano defenzivne i pro MANGA/MANHUA/NOVEL), "genres":[{slug,name}].
 *
 * Seznam kapitol "GET /api/v2/series/{slug}/chapters?limit=100&cursor=..."
 * (cursor strankovani, `hasMore`/`nextCursor` v odpovedi, vychozi razeni
 * nejnovejsi->nejstarsi, presne jak appka chce) - "limit" max 100 (overeno
 * zive, 200 vraci 400). Stranky kapitoly "GET /api/v1/series/{slug}/chapters/
 * {chapterSlug}" -> "images":[{url,order}]. Nektere kapitoly jsou zamknute za
 * mincemi ("requiresPurchase":true, "price":100) - takove maji "images":[]
 * (overeno zive na chapter-61), getPageList pak vrati prazdny seznam bez pádu.
 */
@Singleton
class EzmangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "ezmanga"
    override val name = "Ezmanga"
    override val contentType = "MANHWA"
    override val homepageUrl get() = "https://ezmanga.org"
    private val api = "https://vapi.ezmanga.org/api"

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val arr = JSONArray(get("$api/v1/series/genres"))
            val tags = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug").ifBlank { return@mapNotNull null }
                val label = o.optString("name").trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListJson(json: String): List<SManga> {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("publishStatus") != "PUBLIC") return@mapNotNull null
            val slug = o.optString("slug").ifBlank { return@mapNotNull null }
            val title = o.optString("title").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id, url = "/series/$slug", title = title,
                coverUrl = o.optString("cover").ifBlank { null },
                status = o.optString("status").ifBlank { null }?.lowercase(),
                contentType = normalizeContentType(o.optString("type"), default = "MANHWA"),
                rating = if (o.has("avgRating")) o.optDouble("avgRating").takeIf { !it.isNaN() } else null,
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            val sort = if (filter.sortBy == "popular") "&sort=popular" else ""
            val genreParam = if (genre != null) "&genre=${URLEncoder.encode(genre, "UTF-8")}" else ""
            parseListJson(get("$api/v1/series?page=$page$sort$genreParam"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // "/v1/series/search" nepodporuje "genre" parametr (400) - pri fulltextovem
    // hledani se proto zanrovy filtr ignoruje, stejne jako radici parametr.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseListJson(get("$api/v1/series/search?q=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun slugFromMangaUrl(mangaUrl: String): String = mangaUrl.removePrefix("/series/")

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val slug = slugFromMangaUrl(manga.url)
            val o = JSONObject(get("$api/v1/series/$slug"))
            val genres = o.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.trim()?.ifBlank { null } }
            } ?: manga.genres
            val descriptionHtml = o.optString("description")
            manga.copy(
                title = o.optString("title").ifBlank { manga.title },
                coverUrl = o.optString("cover").ifBlank { null } ?: manga.coverUrl,
                description = if (descriptionHtml.isBlank()) null else Jsoup.parse(descriptionHtml).text().trim().ifBlank { null },
                status = o.optString("status").ifBlank { null }?.lowercase() ?: manga.status,
                author = o.optString("author").ifBlank { null },
                artist = o.optString("artist").ifBlank { null },
                genres = genres,
                contentType = normalizeContentType(o.optString("type").ifBlank { null } ?: manga.contentType, default = "MANHWA"),
                alternateTitles = o.optString("alternativeTitles").ifBlank { null }?.let { listOf(it) } ?: manga.alternateTitles,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun parseIsoDate(iso: String): Long = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugFromMangaUrl(manga.url)
            val chapters = mutableListOf<SChapter>()
            var cursor: String? = null
            while (true) {
                val cursorParam = if (cursor != null) "&cursor=${URLEncoder.encode(cursor, "UTF-8")}" else ""
                val o = JSONObject(get("$api/v2/series/$slug/chapters?limit=100$cursorParam"))
                val arr = o.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val chapterSlug = c.optString("slug").ifBlank { continue }
                    val num = c.optDouble("number").takeIf { !it.isNaN() }?.toFloat() ?: continue
                    val title = c.optString("title").ifBlank { null }
                    chapters += SChapter(
                        sourceId = id, mangaUrl = manga.url, url = "/series/$slug/$chapterSlug",
                        name = title?.let { "Chapter $num - $it" } ?: "Chapter $num",
                        chapterNumber = num,
                        dateUpload = parseIsoDate(c.optString("createdAt")),
                    )
                }
                if (!o.optBoolean("hasMore")) break
                cursor = o.optString("nextCursor").ifBlank { break }
            }
            chapters.distinctBy { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private val chapterPathRegex = Regex("""^/series/([a-zA-Z0-9-]+)/([a-zA-Z0-9-]+)$""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val (slug, chapterSlug) = chapterPathRegex.find(chapter.url)?.destructured ?: return@withContext emptyList()
            val o = JSONObject(get("$api/v1/series/$slug/chapters/$chapterSlug"))
            val images = o.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapIndexedNotNull { i, _ ->
                val url = images.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

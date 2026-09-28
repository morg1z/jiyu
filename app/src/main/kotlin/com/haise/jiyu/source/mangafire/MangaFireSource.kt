package com.haise.jiyu.source.mangafire

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangafire.to je cisty klientsky (Vite/rolldown) SPA shell - `<div
 * id="app-root"></div>`, zadny obsah v HTML. Skutecna data se dotahuji az
 * po hydrataci z verejneho JSON API (zadne specialni hlavicky/auth), ktere
 * se nepodarilo najit statickou analyzou stazenych JS bundlu (hlavni
 * bundle obsahuje jen auth endpointy, zbytek je v lazy-loaded chunku) -
 * teprve inspekce network requestu v realnem prohlizeci ho odhalila:
 *  - listing/search: /api/titles?keyword=...&content_rating[]=safe&...
 *  - detail: /api/titles/{hid}  (hid = kratky kod pred prvni pomlckou v URL slugu)
 *  - kapitoly: /api/titles/{hid}/chapters?language=en&sort=number&order=desc
 *  - stranky kapitoly: /api/chapters/{chapterId}
 */
@Singleton
class MangaFireSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangafire"
    override val name = "MangaFire"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base

    private val base = "https://mangafire.to"
    private val apiBase = "$base/api"

    // Zjisteno staticky analyzou minifikovaneho JS bundlu appky (main-*.js,
    // funkce `fs(e)`/`ps(e)` a `/filter-options` handler J()) - zive overit
    // JSON strukturu pres curl nejde (API vraci 403 "Missing token" bez
    // cf_clearance cookie, viz CloudflareInterceptor), appka v beh
    // ale cookie ziska pres WebView a stejnou cestu jako getPopular/search uz
    // pouziva. `/api/filter-options` vraci mj. pole "genres": [{id, name}, ...]
    // (stejna struktura jako "themes"/"demographics") a listing endpoint
    // `/api/titles` prijima opakovany parametr `genres_in[]=<id>` (axios
    // serializuje pole stejnym zpusobem jako uz pouzite `content_rating[]`).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val data = JSONObject(get("$apiBase/filter-options")).optJSONObject("data") ?: return@withContext emptyList()
            val genres = data.optJSONArray("genres") ?: return@withContext emptyList()
            val tags = (0 until genres.length()).mapNotNull { i ->
                val g = genres.getJSONObject(i)
                val gid = g.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val gname = g.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FilterTag(id = gid.toString(), label = gname)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // /api/titles prijima dale types[]= (manga|manhwa|manhua|other), statuses[]=
    // (releasing|finished|on_hiatus|discontinued|not_yet_released),
    // demographics[]= (<id> z filter-options) a order[<pole>]=asc|desc
    // (title|score|created_at|chapter_updated_at|year) - vse overeno zive
    // probe testem (kazdy parametr vraci vyrazne jinou sadu).
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus", "cancelled")
    override val availableComicTypes: List<FilterTag> get() = listOf(
        FilterTag(id = "manga", label = "Manga"),
        FilterTag(id = "manhwa", label = "Manhwa"),
        FilterTag(id = "manhua", label = "Manhua"),
        FilterTag(id = "other", label = "Other"),
    )
    override val availableDemographics: List<FilterTag> get() = listOf(
        FilterTag(id = "268918", label = "Shounen"),
        FilterTag(id = "268917", label = "Shoujo"),
        FilterTag(id = "268920", label = "Seinen"),
        FilterTag(id = "268919", label = "Josei"),
    )
    override val supportsSortDirection: Boolean get() = true
    override val availableSorts: Set<String> get() = setOf("popular", "latest", "rating", "title")

    private val statusValues = mapOf(
        "ongoing" to "releasing", "completed" to "finished",
        "hiatus" to "on_hiatus", "cancelled" to "discontinued",
    )
    private val siteTypes = setOf("manga", "manhwa", "manhua", "other")
    private val siteDemos = setOf("268917", "268918", "268919", "268920")

    /** UI sortBy id -> order[] pole v API (dir z filter.sortAscending). */
    private val sortFields = mapOf(
        "popular" to "score", "latest" to "chapter_updated_at",
        "rating" to "score", "title" to "title",
    )

    private fun StringBuilder.appendGenreFilter(filter: MangaFilter) {
        filter.genres.forEach { append("&genres_in[]=$it") }
        statusValues[filter.status]?.let { append("&statuses[]=").append(it) }
        filter.comicTypes.firstOrNull()?.takeIf { it in siteTypes }
            ?.let { append("&types[]=").append(it) }
        filter.demographic.firstOrNull()?.takeIf { it in siteDemos }
            ?.let { append("&demographics[]=").append(it) }
    }

    private val vrfSigner = MangaFireVrfSigner()

    private fun get(url: String): String {
        // API vyzaduje "vrf" podpis query (jinak 403 "Missing token") - viz
        // MangaFireVrfSigner. Podepisuje se path + serazene query parametry.
        val builder = Request.Builder()
        url.toHttpUrlOrNull()?.let { builder.url(vrfSigner.sign(it)) } ?: builder.url(url)
        val req = builder
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", "$base/")
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun itemToSManga(o: JSONObject): SManga {
        val poster = o.optJSONObject("poster")
        val cover = poster?.optString("large")?.takeIf { it.isNotBlank() }
            ?: poster?.optString("medium")?.takeIf { it.isNotBlank() }
        return SManga(
            sourceId = id,
            url = o.optString("url"),
            title = o.optString("title"),
            coverUrl = cover,
        )
    }

    private fun parseList(body: String): List<SManga> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { itemToSManga(items.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Vychozi "popular" vypis = puvodni hot feed (trending + posledni
            // aktualizace); pri jinem razeni nebo aktivnich filtrech presne
            // order[<pole>]=<smer> na plnem katalogu.
            val defaultFeed = filter.status == null && filter.comicTypes.isEmpty() &&
                filter.demographic.isEmpty() && filter.genres.isEmpty() &&
                filter.sortBy == "popular" && !filter.sortAscending
            val orderField = if (defaultFeed) "chapter_updated_at" else sortFields[filter.sortBy] ?: "score"
            val orderDir = if (filter.sortAscending) "asc" else "desc"
            val hot = if (defaultFeed) "&hot=1" else ""
            val url = buildString {
                append("$apiBase/titles?content_rating[]=safe&content_rating[]=suggestive&order[$orderField]=$orderDir&page=$page&limit=30$hot")
                appendGenreFilter(filter)
            }
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            // /api/titles kombinuje keyword= s order[] i ostatnimi filtry
            // (stejny endpoint jako listing) - parita s getPopular.
            val orderField = sortFields[filter.sortBy] ?: "score"
            val orderDir = if (filter.sortAscending) "asc" else "desc"
            val url = buildString {
                append("$apiBase/titles?keyword=$q&content_rating[]=safe&content_rating[]=suggestive&order[$orderField]=$orderDir&page=$page&limit=30")
                appendGenreFilter(filter)
            }
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** hid je kratky kod na zacatku URL slugu, napr. "/title/3x369-dragon-fragment" -> "3x369". */
    private fun hidOf(manga: SManga) = manga.url.substringAfterLast("/").substringBefore("-")

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val data = JSONObject(get("$apiBase/titles/${hidOf(manga)}")).optJSONObject("data") ?: return@withContext manga
            val poster = data.optJSONObject("poster")
            val cover = poster?.optString("large")?.takeIf { it.isNotBlank() } ?: manga.coverUrl
            val genres = data.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("title") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val author = data.optJSONArray("authors")?.let { arr ->
                if (arr.length() > 0) arr.getJSONObject(0).optString("title").takeIf { it.isNotBlank() } else null
            }
            val description = data.optString("synopsisHtml").takeIf { it.isNotBlank() }
                ?.let { Jsoup.parse(it).text() }
            manga.copy(
                title = data.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
                coverUrl = cover,
                description = description,
                genres = genres,
                author = author,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val hid = hidOf(manga)
            // API odmita limit > 200 (422 Unprocessable Entity); u serii s vice
            // nez 200 kapitolami by chybely ty nejstarsi (zadna dalsi strankovaci
            // logika zatim neni implementovana).
            val json = JSONObject(get("$apiBase/titles/$hid/chapters?language=en&sort=number&order=desc&page=1&limit=200"))
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).map { i ->
                val c = items.getJSONObject(i)
                val num = c.optDouble("number", 0.0).toFloat()
                val name = c.optString("name").takeIf { it.isNotBlank() } ?: "Chapter $num"
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = c.optLong("id").toString(),
                    name = name,
                    chapterNumber = num,
                    dateUpload = c.optLong("createdAt") * 1000L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val data = JSONObject(get("$apiBase/chapters/${chapter.url}")).optJSONObject("data") ?: return@withContext emptyList()
            val pages = data.optJSONArray("pages") ?: return@withContext emptyList()
            (0 until pages.length()).map { i ->
                val url = pages.getJSONObject(i).optString("url")
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

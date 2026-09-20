package com.haise.jiyu.source.omegascans

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * omegascans.org - korejske 18+ manhwa preklady. Sajta samotna je cisty
 * Next.js App Router shell (zadny __NEXT_DATA__/RSC v HTML se nepodarilo
 * najit), ale `api.omegascans.org` je verejne pristupne REST JSON API bez
 * hlavicek/auth navic:
 *  - listing/hledani: /query?query_string=...&page=N&perPage=20
 *  - detail: /series/{slug} (obsahuje ciselne "id", potrebne pro kapitoly)
 *  - kapitoly: /chapter/query?series_id={id}&page=1&perPage=999
 *  - stranky kapitoly: /chapter/{series_slug}/{chapter_slug}
 *
 * Nejnovejsi kapitoly umi byt placene predem ("price">0, "free_at" v
 * budoucnu) - takove /chapter/{slug}/{slug} vraci `"paywall":true` bez
 * obrazku, getPageList to detekuje a vrati prazdny seznam.
 */
@Singleton
class OmegaScansSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "omegascans"
    override val name = "OmegaScans"
    override val contentType = "MANHWA"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://omegascans.org"
    private val apiBase = "https://api.omegascans.org"

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun slugOf(manga: SManga) = manga.url.substringAfterLast("/")

    private fun parseList(body: String): List<SManga> {
        val items = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val slug = o.optString("series_slug").ifBlank { return@mapNotNull null }
            val title = o.optString("title").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = "$base/series/$slug",
                title = title,
                coverUrl = o.optString("thumbnail").ifBlank { null },
                contentType = "MANHWA",
            )
        }
    }

    // /tags vraci kompletni ciselnik zanru webu (id + name). Listovaci /query
    // endpoint prijima "tags_ids=[id1,id2]" (JSON pole, URL-enkodovane) a filtruje
    // AND zpusobem (kombinace vice tagu total dale zuzuje) - overeno zive:
    // tags_ids=[8] (Harem) total=65 vs bez filtru total=286, tags_ids=[8,3] total=28,
    // a polozka #2 vysledku se s/bez filtru lisi.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val arr = JSONArray(get("$apiBase/tags"))
            val tags = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val tagId = o.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val name = o.optString("name").ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = tagId.toString(), label = name)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun tagsParam(ids: List<String>): String =
        "&tags_ids=" + URLEncoder.encode("[" + ids.joinToString(",") + "]", "UTF-8")

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            // Bez orderBy razeni API vraci podle total_views (nejpopularnejsi) - pro
            // "Nejnovejsi" zalozku overeno zivě, ze orderBy=updated_at seradi podle
            // skutecneho casu posledni aktualizace (sestupne).
            val order = if (filter.sortBy == "latest") "&orderBy=updated_at" else ""
            val tags = if (filter.genres.isNotEmpty()) tagsParam(filter.genres) else ""
            try { parseList(get("$apiBase/query?page=$page&perPage=20$order$tags")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val tags = if (filter.genres.isNotEmpty()) tagsParam(filter.genres) else ""
                parseList(get("$apiBase/query?query_string=$q&page=$page&perPage=20$tags"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(get("$apiBase/series/${slugOf(manga)}"))
                val description = json.optString("description").ifBlank { null }
                    ?.let { Jsoup.parse(it).text().ifBlank { null } }
                val author = json.optString("author").trim().ifBlank { null }?.takeIf { it != "-" }
                val status = json.optString("status").ifBlank { null }
                val tagsArr = json.optJSONArray("tags")
                val genres = if (tagsArr != null) (0 until tagsArr.length()).mapNotNull { i ->
                    tagsArr.optJSONObject(i)?.optString("name")?.ifBlank { null }
                } else emptyList()

                manga.copy(
                    description = description,
                    status = status,
                    author = author,
                    genres = genres,
                    coverUrl = json.optString("thumbnail").ifBlank { null } ?: manga.coverUrl,
                )
            } catch (e: Exception) { e.rethrowIfControl(); manga }
        }

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            try {
                val seriesSlug = slugOf(manga)
                val seriesId = JSONObject(get("$apiBase/series/$seriesSlug")).optInt("id", -1)
                if (seriesId < 0) return@withContext emptyList()

                val json = JSONObject(get("$apiBase/chapter/query?series_id=$seriesId&page=1&perPage=999"))
                val items = json.optJSONArray("data") ?: return@withContext emptyList()
                (0 until items.length()).mapNotNull { i ->
                    val c = items.getJSONObject(i)
                    val chapterSlug = c.optString("chapter_slug").ifBlank { return@mapNotNull null }
                    val name = c.optString("chapter_name").ifBlank { chapterSlug }
                    val chapterNumber = c.optString("index").toFloatOrNull()
                        ?: parseChapterNumber(name) ?: 0f
                    SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = "$apiBase/chapter/$seriesSlug/$chapterSlug",
                        name = name,
                        chapterNumber = chapterNumber,
                        dateUpload = parseIso(c.optString("created_at")),
                    )
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun parseIso(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl();
        System.currentTimeMillis()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(get(chapter.url))
                val chapterObj = json.optJSONObject("chapter") ?: return@withContext emptyList()
                if (chapterObj.optBoolean("paywall", false) || json.optBoolean("paywall", false)) return@withContext emptyList()
                val images = chapterObj.optJSONObject("chapter_data")?.optJSONArray("images") ?: return@withContext emptyList()
                (0 until images.length()).map { i ->
                    val url = images.getString(i)
                    Page(index = i, url = url, imageUrl = url)
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
}

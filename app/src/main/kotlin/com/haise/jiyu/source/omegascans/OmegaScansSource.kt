package com.haise.jiyu.source.omegascans

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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
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

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            // Bez orderBy razeni API vraci podle total_views (nejpopularnejsi) - pro
            // "Nejnovejsi" zalozku overeno zivě, ze orderBy=updated_at seradi podle
            // skutecneho casu posledni aktualizace (sestupne).
            val order = if (filter.sortBy == "latest") "&orderBy=updated_at" else ""
            try { parseList(get("$apiBase/query?page=$page&perPage=20$order")) }
            catch (_: Exception) { emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                parseList(get("$apiBase/query?query_string=$q&page=$page&perPage=20"))
            } catch (_: Exception) { emptyList() }
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
            } catch (_: Exception) { manga }
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
                        ?: Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                    SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = "$apiBase/chapter/$seriesSlug/$chapterSlug",
                        name = name,
                        chapterNumber = chapterNumber,
                        dateUpload = parseIso(c.optString("created_at")),
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

    private fun parseIso(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
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
            } catch (_: Exception) { emptyList() }
        }
}

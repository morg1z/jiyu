package com.haise.jiyu.source.novelbuddy

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
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
 * Seznam kapitol se u dlouhých sérií (1000+ kapitol) nedotahuje ze
 * server-renderované detail stránky (ta obsahuje jen posledních ~50), ale
 * z interního REST API `api.novelbuddy.me/titles/{id}/chapters` - endpoint
 * objeven přes Chrome DevTools Network tab (curl/statická analýza JS
 * bundlu to nešly najít, protože klientský kód je v lazy-loaded chunku).
 * Titulní "id" (krátký hash typu "VYPGVZ8z") appka nemá kde jinde vzít
 * než z API samotného, proto se ukládá zakódovaný v SManga.url za "::".
 */
@Singleton
class NovelBuddySource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "novelbuddy"
    override val name = "NovelBuddy"
    override val contentType = "NOVEL"
    private val base = "https://novelbuddy.me"
    private val api = "https://api.novelbuddy.me"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun encodeUrl(path: String, titleId: String) = "$path::$titleId"
    private fun pathOf(mangaUrl: String) = mangaUrl.substringBefore("::")
    private fun titleIdOf(mangaUrl: String) = mangaUrl.substringAfter("::", "")

    private fun parseItems(json: String): List<SManga> {
        val items = JSONObject(json).optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val titleId = o.optString("id").ifBlank { return@mapNotNull null }
            val path = o.optString("url").ifBlank { return@mapNotNull null }
            val name = o.optString("name").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = encodeUrl(path, titleId),
                title = name,
                coverUrl = o.optString("cover").ifBlank { null },
                description = o.optString("summary").ifBlank { null },
                status = mapStatus(o.optString("status")),
                genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("name") } } ?: emptyList(),
                contentType = "NOVEL",
            )
        }
    }

    private fun mapStatus(raw: String?): String? = when (raw?.lowercase()) {
        "ongoing" -> "Ongoing"
        "completed" -> "Completed"
        null, "" -> null
        else -> raw
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseItems(get("$api/titles/search?page=$page&limit=24")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseItems(get("$api/titles/search?page=$page&limit=24&q=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val titleId = titleIdOf(manga.url).ifBlank { return@withContext manga }
            val t = JSONObject(get("$api/titles/$titleId")).optJSONObject("data")?.optJSONObject("title") ?: return@withContext manga
            manga.copy(
                title = t.optString("name").ifBlank { manga.title },
                coverUrl = t.optString("cover").ifBlank { manga.coverUrl },
                description = t.optString("summary").ifBlank { null }?.let { Jsoup.parse(it).text() },
                status = mapStatus(t.optString("status")) ?: manga.status,
                genres = t.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("name") } } ?: manga.genres,
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val titleId = titleIdOf(manga.url).ifBlank { return@withContext emptyList() }
            val chapters = JSONObject(get("$api/titles/$titleId/chapters"))
                .optJSONObject("data")?.optJSONArray("chapters") ?: JSONArray()
            (0 until chapters.length()).map { i ->
                val c = chapters.getJSONObject(i)
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = c.optString("url"),
                    name = c.optString("name").ifBlank { "Chapter" },
                    chapterNumber = c.optDouble("number", 0.0).toFloat(),
                    dateUpload = parseDate(c.optString("updated_at")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${pathOf(chapter.url)}"))
            val text = doc.select("div.novel-tts-content p:not([class])").joinToString("\n\n") { it.text() }
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

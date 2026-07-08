package com.haise.jiyu.source.reaperscans

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaperScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "reaperscans"
    override val name = "Reaperscans"
    private val api = "https://api.reaperscans.com"
    private val base = "https://reaperscans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseComicArray(json: String): List<SManga> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val slug = obj.optString("series_slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = "/series/$slug",
                    title = obj.optString("title", slug),
                    coverUrl = obj.optString("thumbnail"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val json = get("$api/series?page=$page&order_by=popularity&series_type=Comic")
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: JSONArray(json)
            parseComicArray(data.toString())
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = get("$api/series?page=1&title=$q")
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: JSONArray(json)
            parseComicArray(data.toString())
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val slug = manga.url.substringAfterLast("/")
            val json = get("$api/series/$slug")
            val obj = JSONObject(json)
            manga.copy(
                title = obj.optString("title", manga.title),
                coverUrl = obj.optString("thumbnail").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = obj.optString("description").takeIf { it.isNotBlank() },
                author = obj.optString("author").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = manga.url.substringAfterLast("/")
            val json = get("$api/chapter/query?series_slug=$slug&page=1&perPage=9999")
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).map { i ->
                val ch = data.getJSONObject(i)
                val chSlug = ch.optString("chapter_slug", "")
                val chName = ch.optString("chapter_name", "")
                val num = Regex("""(\d+(?:\.\d+)?)""").find(chName)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (data.length() - i).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/series/$slug/$chSlug",
                    name = chName.ifBlank { "Chapter $num" },
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val slug = chapter.url.substringAfterLast("/")
            val seriesSlug = chapter.mangaUrl.substringAfterLast("/")
            val json = get("$api/chapter/$seriesSlug/$slug")
            val obj = JSONObject(json)
            val content = obj.optJSONArray("content") ?: return@withContext emptyList()
            (0 until content.length()).map { i ->
                val url = content.getString(i)
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

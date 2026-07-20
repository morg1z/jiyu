package com.haise.jiyu.source.mangadotnet

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
 * Mangadotnet (mangadot.net) - React/Remix aplikace. Vypis popularnich/
 * nejnovejsich titulu je server-rendered HTML, ale vyhledavani, detail
 * mangy, seznam kapitol i seznam stranek bezi pres verejne JSON API
 * (zjisteno pres DevTools Network, viz komentare u jednotlivych funkci).
 */
@Singleton
class MangaDotNetSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangadotnet"
    override val name = "Mangadotnet"
    override val homepageUrl get() = base
    private val base = "https://mangadot.net"

    private fun getHtml(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun getJsonObject(url: String): JSONObject = JSONObject(getHtml(url))

    private fun absCover(photo: String?): String? {
        if (photo.isNullOrBlank()) return null
        return if (photo.startsWith("http")) photo else "$base$photo"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(getHtml("$base/view-all/most-tracked?page=$page"))
            doc.select("a.group[href^=/manga/]").mapNotNull { el ->
                val href = el.attr("href").ifBlank { return@mapNotNull null }
                val title = el.select("div.line-clamp-2").lastOrNull()?.text()?.trim()
                    ?.ifBlank { null } ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = href,
                    title = title,
                    coverUrl = absCover(el.selectFirst("img")?.attr("src")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // Vyhledavani bezi pres /api/search?q=..., ktere vraci primo cely
    // manga_list vcetne popisu/zanru - stranky (page) API nepodporuje.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = getJsonObject("$base/api/search?q=$q")
            val arr = json.optJSONArray("manga_list") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.getJSONObject(i)
                val mangaId = m.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val title = m.optString("title").ifBlank { return@mapNotNull null }
                SManga(
                    sourceId = id,
                    url = "/manga/$mangaId",
                    title = title,
                    coverUrl = absCover(m.optString("photo")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    // Detail mangy je take pres API - /api/manga/{id}. authors/artists jsou
    // v odpovedi ulozene jako string obsahujici serializovane JSON pole
    // (napr. "[\"Singsyong\",\"UMI\"]"), ne jako skutecne pole.
    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val mangaId = manga.url.substringAfterLast("/")
            val json = getJsonObject("$base/api/manga/$mangaId")
            val m = json.getJSONObject("manga")
            val genres = m.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val authors = parseStringArray(m.optString("authors"))

            manga.copy(
                title = m.optString("title").ifBlank { manga.title },
                coverUrl = absCover(m.optString("photo")) ?: manga.coverUrl,
                description = m.optString("description").ifBlank { null },
                author = authors.joinToString().ifBlank { null },
                genres = genres,
                status = m.optString("status").ifBlank { null },
            )
        } catch (_: Exception) { manga }
    }

    // Seznam kapitol je JSON pole primo, ne obalene v objektu:
    // /api/manga/{id}/chapters/list -> [{id, chapter_number, chapter_title, date_added, source}, ...]
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val mangaId = manga.url.substringAfterLast("/")
            val arr = JSONArray(getHtml("$base/api/manga/$mangaId/chapters/list"))
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val chapterId = c.getInt("id")
                val source = c.optString("source", "user")
                val num = c.optDouble("chapter_number", 0.0).toFloat()
                val chTitle = c.optString("chapter_title").ifBlank { null }
                val name = buildString {
                    append("Chapter ").append(if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString())
                    if (chTitle != null) append(": ").append(chTitle)
                }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/chapter/$chapterId?source=$source",
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseDate(c.optString("date_added")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val cleaned = text.substringBefore("+").trim()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(cleaned)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { System.currentTimeMillis() }
    }

    // Stranky kapitoly: /api/uploads/{chapterId}/images -> {images: [{url}, ...]},
    // obrazky jsou verejne dostupne bez tokenu i kdyz stranka nacita i
    // /api/token/generate - ten se pouziva jen pro upload flow, ne pro cteni.
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterId = chapter.url.substringAfter("/chapter/").substringBefore("?")
            val json = getJsonObject("$base/api/uploads/$chapterId/images")
            val images = json.optJSONArray("images") ?: JSONArray()
            (0 until images.length()).mapNotNull { i ->
                val url = images.getJSONObject(i).optString("url").ifBlank { return@mapNotNull null }
                val full = if (url.startsWith("http")) url else "$base$url"
                Page(i, full, full)
            }
        } catch (_: Exception) { emptyList() }
    }
}

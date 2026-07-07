package com.haise.jiyu.source.nhentai

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
class NhentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "nhentai"
    override val name = "nhentai"

    private val apiBase  = "https://nhentai.net/api"
    private val imgBase  = "https://i.nhentai.net/galleries"
    private val thumbBase = "https://t.nhentai.net/galleries"

    private fun extFromType(t: String) = when (t) { "p" -> "png"; "g" -> "gif"; else -> "jpg" }

    private fun fetch(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://nhentai.net")
            .build()
        return JSONObject(client.newCall(req).execute().use { it.body?.string() ?: "{}" })
    }

    private fun toSManga(obj: JSONObject): SManga {
        val mediaId  = obj.optString("media_id")
        val images   = obj.optJSONObject("images")
        val coverExt = extFromType(images?.optJSONObject("cover")?.optString("t", "j") ?: "j")
        val cover    = "$thumbBase/$mediaId/cover.$coverExt"

        val titleObj = obj.optJSONObject("title")
        val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("pretty")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("japanese")?.takeIf { it.isNotBlank() }
            ?: "ID: ${obj.optInt("id")}"

        val tagsArr  = obj.optJSONArray("tags") ?: JSONArray()
        val tagObjs  = (0 until tagsArr.length()).map { tagsArr.getJSONObject(it) }
        val tagNames = tagObjs.map { it.optString("name") }.filter { it.isNotBlank() }
        val artist   = tagObjs.firstOrNull { it.optString("type") == "artist" }?.optString("name")

        return SManga(
            sourceId    = id,
            url         = "/gallery/${obj.optInt("id")}",
            title       = title,
            coverUrl    = cover,
            author      = artist,
            genres      = tagNames.take(15),
            contentType = "MANGA",
        )
    }

    private fun parseList(json: JSONObject): List<SManga> {
        val result = json.optJSONArray("result") ?: return emptyList()
        return (0 until result.length()).map { toSManga(result.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(fetch("$apiBase/galleries/all?page=$page&sort=popular")) }
        catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseList(fetch("$apiBase/galleries/search?query=$q&page=$page&sort=popular"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val galleryId = manga.url.substringAfterLast("/")
            val json = fetch("$apiBase/gallery/$galleryId")
            val base = toSManga(json)

            val tagsArr  = json.optJSONArray("tags") ?: JSONArray()
            val tagObjs  = (0 until tagsArr.length()).map { tagsArr.getJSONObject(it) }
            val byType   = tagObjs.groupBy { it.optString("type") }

            val desc = buildString {
                byType["parody"]?.let    { append("Parody: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["character"]?.let { append("Characters: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["language"]?.let  { append("Language: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["category"]?.let  { append("Category: ${it.joinToString { t -> t.optString("name") }}\n") }
                append("Pages: ${json.optInt("num_pages")}")
            }.trim()

            base.copy(description = desc)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId        = id,
                mangaUrl        = manga.url,
                url             = manga.url,
                name            = manga.title,
                chapterNumber   = 1f,
                dateUpload      = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val galleryId = chapter.url.substringAfterLast("/")
            val json      = fetch("$apiBase/gallery/$galleryId")
            val mediaId   = json.optString("media_id")
            val pages     = json.optJSONObject("images")?.optJSONArray("pages") ?: return@withContext emptyList()
            (0 until pages.length()).map { i ->
                val ext = extFromType(pages.getJSONObject(i).optString("t", "j"))
                val url = "$imgBase/$mediaId/${i + 1}.$ext"
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

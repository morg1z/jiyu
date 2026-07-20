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

/**
 * nhentai vyřadilo starou API (`/api/galleries/...`, `/api/gallery/{id}`) ve
 * prospěch v2 (`/api/v2/...`, viz https://nhentai.net/api/v2/docs). Rozdíl
 * oproti staré verzi:
 *  - listing endpointy (popular/search) vrací "ploché" pole bez title objektu
 *    (jen english_title/japanese_title) a jen tag_ids (ne celé tag objekty)
 *  - detail endpoint (/galleries/{id}) pořád vrací bohatou strukturu
 *    (title.english/japanese/pretty, plné tag objekty, "pages" pole s
 *    hotovou cestou k souboru včetně přípony - žádné mapování "t" typu na
 *    příponu jako dřív)
 *  - obálky/thumbnaily fungují jen na t.nhentai.net, i.nhentai.net je jen
 *    pro plné stránky galerie
 */
@Singleton
class NhentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "nhentai"
    override val name = "nhentai"
    override val homepageUrl get() = "https://nhentai.net"

    private val apiBase   = "https://nhentai.net/api/v2"
    private val imgBase   = "https://i.nhentai.net"
    private val thumbBase = "https://t.nhentai.net"

    private fun fetch(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://nhentai.net")
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "{}" }
        // popular/search vraci bud primo pole, nebo {"result": [...]}; zabalime pole do objektu.
        return if (body.trimStart().startsWith("[")) JSONObject().put("result", JSONArray(body))
        else JSONObject(body)
    }

    /** Listing (popular/search) - jen ploche pole, bez plnych tag objektu. */
    private fun listItemToSManga(obj: JSONObject): SManga {
        val thumb = obj.optString("thumbnail").ifBlank { null }
        val title = obj.optString("english_title").ifBlank { null }
            ?: obj.optString("japanese_title").ifBlank { null }
            ?: "ID: ${obj.optInt("id")}"
        return SManga(
            sourceId    = id,
            url         = "/gallery/${obj.optInt("id")}",
            title       = title,
            coverUrl    = thumb?.let { "$thumbBase/$it" },
            contentType = "MANGA",
        )
    }

    private fun parseList(json: JSONObject): List<SManga> {
        val result = json.optJSONArray("result") ?: return emptyList()
        return (0 until result.length()).map { listItemToSManga(result.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(fetch("$apiBase/galleries/popular?page=$page")) }
        catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseList(fetch("$apiBase/search?query=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val galleryId = manga.url.substringAfterLast("/")
            val json = fetch("$apiBase/galleries/$galleryId")

            val titleObj = json.optJSONObject("title")
            val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("pretty")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("japanese")?.takeIf { it.isNotBlank() }
                ?: manga.title
            val cover = json.optJSONObject("cover")?.optString("path")?.takeIf { it.isNotBlank() }
                ?.let { "$thumbBase/$it" } ?: manga.coverUrl

            val tagsArr = json.optJSONArray("tags") ?: JSONArray()
            val tagObjs = (0 until tagsArr.length()).map { tagsArr.getJSONObject(it) }
            val byType  = tagObjs.groupBy { it.optString("type") }
            val artist  = byType["artist"]?.firstOrNull()?.optString("name")
            val genres  = byType["tag"]?.map { it.optString("name") }?.filter { it.isNotBlank() } ?: emptyList()

            val desc = buildString {
                byType["parody"]?.let    { append("Parody: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["character"]?.let { append("Characters: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["language"]?.let  { append("Language: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["category"]?.let  { append("Category: ${it.joinToString { t -> t.optString("name") }}\n") }
                append("Pages: ${json.optInt("num_pages")}")
            }.trim()

            manga.copy(title = title, coverUrl = cover, description = desc, author = artist, genres = genres.take(15))
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
            val json = fetch("$apiBase/galleries/$galleryId")
            val pages = json.optJSONArray("pages") ?: return@withContext emptyList()
            (0 until pages.length()).map { i ->
                val path = pages.getJSONObject(i).optString("path")
                val url = "$imgBase/$path"
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

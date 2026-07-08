package com.haise.jiyu.source.mangapark

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
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaParkSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangapark"
    override val name = "MangaPark"

    private val api = "https://mangapark.net/apo/"

    private fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(api).post(body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Content-Type", "application/json")
            .header("Referer", "https://mangapark.net/")
            .build()
        return JSONObject(client.newCall(req).execute().use { it.body?.string() ?: "{}" })
            .optJSONObject("data") ?: JSONObject()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val vars = JSONObject().put("select", JSONObject().put("page", page).put("sortby", "view_count"))
            val data = gql("""query(${'$'}select: ComicSearchSelect) { searchComics(select: ${'$'}select) {
                items { data { id name urlPath imageCoverUrl } } } }""", vars)
            val items = data.optJSONObject("searchComics")?.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                val d = items.getJSONObject(i).optJSONObject("data") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = d.optString("urlPath", ""),
                    title = d.optString("name", ""),
                    coverUrl = d.optString("imageCoverUrl").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val vars = JSONObject().put("select", JSONObject().put("page", page).put("word", query))
            val data = gql("""query(${'$'}select: ComicSearchSelect) { searchComics(select: ${'$'}select) {
                items { data { id name urlPath imageCoverUrl } } } }""", vars)
            val items = data.optJSONObject("searchComics")?.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                val d = items.getJSONObject(i).optJSONObject("data") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = d.optString("urlPath", ""),
                    title = d.optString("name", ""),
                    coverUrl = d.optString("imageCoverUrl").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val escaped = manga.url.replace("\"", "\\\"")
            val data = gql("""{ comicByUrlPath(urlPath: "$escaped") { data {
                name imageCoverUrl summary genres { name } artists { name } } } }""")
            val d = data.optJSONObject("comicByUrlPath")?.optJSONObject("data") ?: return@withContext manga
            val genres = d.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val author = d.optJSONArray("artists")?.let { arr ->
                if (arr.length() > 0) arr.getJSONObject(0).optString("name").takeIf { it.isNotBlank() } else null
            }
            manga.copy(
                title = d.optString("name").takeIf { it.isNotBlank() } ?: manga.title,
                coverUrl = d.optString("imageCoverUrl").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = d.optString("summary").takeIf { it.isNotBlank() },
                genres = genres,
                author = author,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val escaped = manga.url.replace("\"", "\\\"")
            val data = gql("""{ comicByUrlPath(urlPath: "$escaped") { data {
                chapterNodes { data { id dname urlPath numberFloat } } } } }""")
            val nodes = data.optJSONObject("comicByUrlPath")?.optJSONObject("data")?.optJSONArray("chapterNodes")
                ?: return@withContext emptyList()
            (0 until nodes.length()).mapNotNull { i ->
                val d = nodes.getJSONObject(i).optJSONObject("data") ?: return@mapNotNull null
                val num = d.optDouble("numberFloat", 0.0).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = d.optString("urlPath", ""),
                    name = d.optString("dname", "Chapter $num"),
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val escaped = chapter.url.replace("\"", "\\\"")
            val data = gql("""{ chapterByUrlPath(urlPath: "$escaped") { data { imageFile { urlList } } } }""")
            val urls = data.optJSONObject("chapterByUrlPath")?.optJSONObject("data")
                ?.optJSONObject("imageFile")?.optJSONArray("urlList")
                ?: return@withContext emptyList()
            (0 until urls.length()).map { i -> Page(i, urls.getString(i), urls.getString(i)) }
        } catch (_: Exception) { emptyList() }
    }
}

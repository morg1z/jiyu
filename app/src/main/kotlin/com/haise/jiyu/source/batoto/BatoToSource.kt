package com.haise.jiyu.source.batoto

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
class BatoToSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "batoto"
    override val name = "Bato.to"
    override val homepageUrl get() = "https://bato.to"

    private val api = "https://bato.to/apo/"

    private fun gql(query: String): JSONObject {
        val body = JSONObject().put("query", query).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(api).post(body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Content-Type", "application/json")
            .build()
        return JSONObject(client.newCall(req).execute().use { it.body?.string() ?: "{}" })
            .optJSONObject("data") ?: JSONObject()
    }

    private fun comicToSManga(obj: JSONObject) = SManga(
        sourceId = id,
        url = obj.optString("urlPath"),
        title = obj.optString("name"),
        coverUrl = obj.optString("urlCoverOri").takeIf { it.isNotBlank() },
    )

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val data = gql("""{ getComics(select: {sort: "update", page: $page}) { comics { id name urlPath urlCoverOri } } }""")
            val arr = data.optJSONObject("getComics")?.optJSONArray("comics") ?: return@withContext emptyList()
            (0 until arr.length()).map { comicToSManga(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
            val data = gql("""{ searchComics(select: {word: "$escaped", sort: "update", page: $page}) { comics { id name urlPath urlCoverOri } } }""")
            val arr = data.optJSONObject("searchComics")?.optJSONArray("comics") ?: return@withContext emptyList()
            (0 until arr.length()).map { comicToSManga(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val escaped = manga.url.replace("\"", "\\\"")
            val data = gql("""{ getComic(comicPath: "$escaped") { name urlCoverOri desc genres { name } authors { name } } }""")
            val comic = data.optJSONObject("getComic") ?: return@withContext manga
            val genres = comic.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val author = comic.optJSONArray("authors")?.let { arr ->
                if (arr.length() > 0) arr.getJSONObject(0).optString("name").takeIf { it.isNotBlank() } else null
            }
            manga.copy(
                title = comic.optString("name").takeIf { it.isNotBlank() } ?: manga.title,
                coverUrl = comic.optString("urlCoverOri").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = comic.optString("desc").takeIf { it.isNotBlank() },
                genres = genres,
                author = author,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val escaped = manga.url.replace("\"", "\\\"")
            val data = gql("""{ getComic(comicPath: "$escaped") { chapters { id title urlPath chapterNum volNum } } }""")
            val arr = data.optJSONObject("getComic")?.optJSONArray("chapters") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val ch = arr.getJSONObject(i)
                val num = ch.optDouble("chapterNum", 0.0).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = ch.optString("urlPath"),
                    name = ch.optString("title").takeIf { it.isNotBlank() } ?: "Chapter $num",
                    chapterNumber = num,
                    dateUpload = 0L,
                    volume = ch.optString("volNum").takeIf { it.isNotBlank() },
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val escaped = chapter.url.replace("\"", "\\\"")
            val data = gql("""{ getChapter(chapterPath: "$escaped") { images } }""")
            val images = data.optJSONObject("getChapter")?.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).map { i ->
                val url = images.getString(i)
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

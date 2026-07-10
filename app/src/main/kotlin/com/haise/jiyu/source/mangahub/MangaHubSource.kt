package com.haise.jiyu.source.mangahub

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
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaHubSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id   = "mangahub"
    override val name = "MangaHub"
    private val base  = "https://mangahub.io"
    private val api   = "https://api.mghubcdn.com/graphql"
    private val cdn   = "https://img.mghubcdn.com/file/imghub"
    private val json  = "application/json".toMediaType()

    private fun gql(query: String): JSONObject = try {
        val body = JSONObject().put("query", query).toString().toRequestBody(json)
        val req = Request.Builder().url(api).post(body)
            .header("Content-Type", "application/json")
            .header("Origin", base)
            .header("Referer", "$base/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        client.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
    } catch (_: Exception) { JSONObject() }

    private fun coverUrl(path: String?) =
        if (path.isNullOrBlank()) null
        else if (path.startsWith("http")) path
        else "$cdn/$path"

    private fun parseRows(data: JSONObject): List<SManga> {
        val rows = data.optJSONObject("data")
            ?.optJSONObject("search")
            ?.optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val m = rows.optJSONObject(i) ?: return@mapNotNull null
            val slug = m.optString("slug").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url      = "/manga/$slug",
                title    = m.optString("title").ifBlank { return@mapNotNull null },
                coverUrl = coverUrl(m.optString("image").ifBlank { null }),
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * 30
        parseRows(gql("""{ search(x:"",q:"",genre:"all",order:POPULAR,moderationMode:false,count:true,offset:$offset){ rows{ id title slug image } } }"""))
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val q      = query.replace("\"", "\\\"")
        val offset = (page - 1) * 30
        parseRows(gql("""{ search(x:"",q:"$q",genre:"all",order:POPULAR,moderationMode:false,count:true,offset:$offset){ rows{ id title slug image } } }"""))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val slug = manga.url.substringAfterLast("/")
        val data = gql("""{ manga(x:"",slug:"$slug"){ id title image description genres status author } }""")
        val m = data.optJSONObject("data")?.optJSONObject("manga") ?: return@withContext manga
        val genres = m.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        manga.copy(
            coverUrl    = coverUrl(m.optString("image").ifBlank { null }) ?: manga.coverUrl,
            description = m.optString("description").ifBlank { null },
            genres      = genres,
            author      = m.optString("author").ifBlank { null },
            status      = m.optString("status").ifBlank { null },
        )
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val slug   = manga.url.substringAfterLast("/")
        val idData = gql("""{ manga(x:"",slug:"$slug"){ id } }""")
        val mangaId = idData.optJSONObject("data")?.optJSONObject("manga")?.optInt("id", -1)
            ?.takeIf { it > 0 } ?: return@withContext emptyList()

        val data     = gql("""{ chapters(x:"",mangaID:$mangaId){ id number title date } }""")
        val chapters = data.optJSONObject("data")?.optJSONArray("chapters") ?: return@withContext emptyList()
        (0 until chapters.length()).mapNotNull { i ->
            val c   = chapters.optJSONObject(i) ?: return@mapNotNull null
            val num = c.optDouble("number", 0.0).toFloat()
            SChapter(
                sourceId      = id,
                mangaUrl      = manga.url,
                url           = "/chapter/$slug/chapter-$num",
                name          = c.optString("title").ifBlank { "Chapter ${num.toInt()}" },
                chapterNumber = num,
                dateUpload    = 0L,
            )
        }.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        // URL format: /chapter/SLUG/chapter-NUM
        val parts  = chapter.url.split("/").filter { it.isNotBlank() }
        val slug   = parts.getOrNull(1) ?: return@withContext emptyList()
        val num    = parts.lastOrNull()?.removePrefix("chapter-")?.toDoubleOrNull()
            ?: return@withContext emptyList()

        val data       = gql("""{ chapter(x:"",slug:"$slug",number:$num){ id images } }""")
        val imagesRaw  = data.optJSONObject("data")?.optJSONObject("chapter")
            ?.optString("images").orEmpty()
        if (imagesRaw.isBlank()) return@withContext emptyList()

        val urls = try {
            val arr = JSONArray(imagesRaw)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) {
            imagesRaw.trim().split("\n").filter { it.isNotBlank() }
        }

        urls.mapIndexedNotNull { i, url ->
            val imageUrl = when {
                url.startsWith("http") -> url
                url.startsWith("/")    -> "$cdn$url"
                else                   -> "$cdn/$url"
            }
            Page(i, imageUrl, imageUrl)
        }
    }
}

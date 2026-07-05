package com.haise.jiyu.source.mangadex

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zdroj napojený na veřejné REST API MangaDexu (https://api.mangadex.org).
 * Slouží jako referenční příklad zdroje - žádný scraping HTML, čistě JSON API.
 *
 * Další zdroje (jiné scan stránky) se dělají stejným způsobem: implementuješ
 * MangaSource a místo JSON API tam bude typicky Jsoup parsování HTML.
 */
@Singleton
class MangaDexSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangadex"
    override val name = "MangaDex"

    private val apiBase = "https://api.mangadex.org"
    private val coverBase = "https://uploads.mangadex.org/covers"

    override suspend fun search(query: String, page: Int): List<SManga> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * 20
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiBase/manga?title=$encodedQuery&limit=20&offset=$offset&includes[]=cover_art"
        parseMangaList(get(url))
    }

    override suspend fun getPopular(page: Int): List<SManga> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * 20
        val url = "$apiBase/manga?order[followedCount]=desc&limit=20&offset=$offset&includes[]=cover_art"
        parseMangaList(get(url))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val mangaId = manga.url.substringAfterLast("/")
        val json = get("$apiBase/manga/$mangaId?includes[]=cover_art")
        val data = json.getJSONObject("data")
        mangaFromData(data) ?: manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val mangaId = manga.url.substringAfterLast("/")
        val url = "$apiBase/manga/$mangaId/feed" +
            "?translatedLanguage[]=en&order[chapter]=desc&limit=100" +
            "&contentRating[]=safe&contentRating[]=suggestive"
        val json = get(url)
        val results = json.getJSONArray("data")
        (0 until results.length()).mapNotNull { i ->
            chapterFromData(results.getJSONObject(i), manga.url)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val chapterId = chapter.url.substringAfterLast("/")
        val json = get("$apiBase/at-home/server/$chapterId")
        val baseUrl = json.getString("baseUrl")
        val chapterData = json.getJSONObject("chapter")
        val hash = chapterData.getString("hash")
        val files = chapterData.getJSONArray("data")
        (0 until files.length()).map { i ->
            val filename = files.getString(i)
            val imageUrl = "$baseUrl/data/$hash/$filename"
            Page(index = i, url = imageUrl, imageUrl = imageUrl)
        }
    }

    // -- Pomocné funkce ----------------------------------------------------

    private fun get(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("MangaDex API chyba ${response.code}: $url")
            }
            return JSONObject(body)
        }
    }

    private fun parseMangaList(json: JSONObject): List<SManga> {
        val results: JSONArray = json.getJSONArray("data")
        return (0 until results.length()).mapNotNull { i ->
            mangaFromData(results.getJSONObject(i))
        }
    }

    private fun mangaFromData(data: JSONObject): SManga? {
        val mangaId = data.getString("id")
        val attributes = data.getJSONObject("attributes")
        val titleObj = attributes.getJSONObject("title")
        val title = titleObj.optString("en", titleObj.keys().asSequence().firstOrNull()?.let { titleObj.getString(it) } ?: "Bez názvu")
        val descriptionObj = attributes.optJSONObject("description")
        val description = descriptionObj?.optString("en")
        val status = attributes.optString("status")

        var coverFileName: String? = null
        val relationships = data.optJSONArray("relationships")
        if (relationships != null) {
            for (i in 0 until relationships.length()) {
                val rel = relationships.getJSONObject(i)
                if (rel.optString("type") == "cover_art") {
                    coverFileName = rel.optJSONObject("attributes")?.optString("fileName")
                }
            }
        }
        val coverUrl = coverFileName?.let { "$coverBase/$mangaId/$it.256.jpg" }

        return SManga(
            sourceId = id,
            url = "$apiBase/manga/$mangaId",
            title = title,
            coverUrl = coverUrl,
            description = description,
            status = status,
        )
    }

    private fun chapterFromData(data: JSONObject, mangaUrl: String): SChapter? {
        val chapterId = data.getString("id")
        val attributes = data.getJSONObject("attributes")
        val chapterNumber = attributes.optString("chapter", "0").toFloatOrNull() ?: 0f
        val title = attributes.optString("title").ifBlank { "Kapitola $chapterNumber" }
        val dateUpload = attributes.optString("publishAt")

        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = "$apiBase/chapter/$chapterId",
            name = title,
            chapterNumber = chapterNumber,
            dateUpload = parseIsoDateToMillis(dateUpload),
        )
    }

    private fun parseIsoDateToMillis(iso: String): Long {
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

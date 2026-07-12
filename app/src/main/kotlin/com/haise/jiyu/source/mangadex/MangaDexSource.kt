package com.haise.jiyu.source.mangadex

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.LanguageMap
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * org.json vraci pro JSON `null` hodnoty z optString() doslovny retezec "null"
 * (JSONObject.NULL.toString()), ne prazdny string ani Kotlin null - MangaDex
 * API vraci "title": null u vetsiny kapitol bez vlastniho nazvu, takze naivni
 * optString(...).ifBlank{} tenhle pripad nikdy nezachyti.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

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
    private val settings: SettingsRepository,
) : MangaSource {

    override val id = "mangadex"
    override val name = "MangaDex"

    private val apiBase = "https://api.mangadex.org"
    private val coverBase = "https://uploads.mangadex.org/covers"

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * 20
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildString {
            append("$apiBase/manga?title=$encodedQuery&limit=20&offset=$offset&includes[]=cover_art")
            filter.status?.let { append("&status[]=$it") }
            filter.year?.takeIf { it > 0 }?.let { append("&year=$it") }
            appendSortParam(filter.sortBy)
        }
        parseMangaList(get(url))
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * 20
        val url = buildString {
            append("$apiBase/manga?limit=20&offset=$offset&includes[]=cover_art")
            append("&contentRating[]=safe&contentRating[]=suggestive")
            filter.status?.let { append("&status[]=$it") }
            filter.year?.takeIf { it > 0 }?.let { append("&year=$it") }
            appendSortParam(filter.sortBy)
        }
        parseMangaList(get(url))
    }

    private fun StringBuilder.appendSortParam(sortBy: String) {
        when (sortBy) {
            "latest"  -> append("&order[updatedAt]=desc")
            "rating"  -> append("&order[rating]=desc")
            "title"   -> append("&order[title]=asc")
            else      -> append("&order[followedCount]=desc")
        }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val mangaId = manga.url.substringAfterLast("/")
        val json = get("$apiBase/manga/$mangaId?includes[]=cover_art&includes[]=author&includes[]=artist")
        val data = json.getJSONObject("data")
        mangaFromData(data) ?: manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val mangaId = manga.url.substringAfterLast("/")
        val allChapters = mutableListOf<SChapter>()
        var offset = 0
        val limit = 100
        val langCode = LanguageMap.toMangaDexCode(settings.sourceLanguage.first())
        while (true) {
            val url = "$apiBase/manga/$mangaId/feed" +
                "?translatedLanguage[]=$langCode&order[chapter]=desc&limit=$limit&offset=$offset" +
                "&contentRating[]=safe&contentRating[]=suggestive&includes[]=scanlation_group"
            val json = get(url)
            val results = json.optJSONArray("data") ?: break
            val total = json.optInt("total", 0)
            val batch = (0 until results.length()).mapNotNull { i ->
                chapterFromData(results.getJSONObject(i), manga.url)
            }
            allChapters.addAll(batch)
            offset += limit
            if (allChapters.size >= total || results.length() < limit) break
        }
        allChapters
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
        val results = json.optJSONArray("data") ?: return emptyList()
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
        val year = attributes.optInt("year", 0).takeIf { it > 0 }
        val originalLanguage = attributes.optString("originalLanguage", "")
        val contentType = when (originalLanguage) {
            "ko" -> "MANHWA"
            "zh", "zh-hk" -> "MANHUA"
            else -> "MANGA"
        }

        val genres = mutableListOf<String>()
        val tagsArray = attributes.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tag = tagsArray.getJSONObject(i)
                val group = tag.optJSONObject("attributes")?.optString("group")
                if (group == "genre") {
                    tag.optJSONObject("attributes")?.optJSONObject("name")?.optString("en")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { genres.add(it) }
                }
            }
        }

        var coverFileName: String? = null
        var author: String? = null
        var artist: String? = null
        val relationships = data.optJSONArray("relationships")
        if (relationships != null) {
            for (i in 0 until relationships.length()) {
                val rel = relationships.getJSONObject(i)
                when (rel.optString("type")) {
                    "cover_art"  -> coverFileName = rel.optJSONObject("attributes")?.optStringOrNull("fileName")
                    "author"     -> if (author == null) author = rel.optJSONObject("attributes")?.optStringOrNull("name")
                    "artist"     -> if (artist == null) artist = rel.optJSONObject("attributes")?.optStringOrNull("name")
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
            author = author,
            artist = artist,
            genres = genres,
            year = year,
            contentType = contentType,
        )
    }

    private fun chapterFromData(data: JSONObject, mangaUrl: String): SChapter? {
        val chapterId = data.getString("id")
        val attributes = data.getJSONObject("attributes")
        val chapterNumber = attributes.optString("chapter", "0").toFloatOrNull() ?: 0f
        val title = attributes.optStringOrNull("title") ?: "Kapitola $chapterNumber"
        val dateUpload = attributes.optString("publishAt")
        val volume = attributes.optString("volume").ifBlank { null }

        var scanlationGroup: String? = null
        val relationships = data.optJSONArray("relationships")
        if (relationships != null) {
            for (i in 0 until relationships.length()) {
                val rel = relationships.getJSONObject(i)
                if (rel.optString("type") == "scanlation_group") {
                    scanlationGroup = rel.optJSONObject("attributes")?.optStringOrNull("name")
                    break
                }
            }
        }

        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = "$apiBase/chapter/$chapterId",
            name = title,
            chapterNumber = chapterNumber,
            dateUpload = parseIsoDateToMillis(dateUpload),
            scanlationGroup = scanlationGroup,
            volume = volume,
        )
    }

    suspend fun getRelatedManga(mangaId: String): List<SManga> = withContext(Dispatchers.IO) {
        val relJson = try { get("$apiBase/manga/$mangaId/relation") } catch (_: Exception) { return@withContext emptyList() }
        val relData = relJson.optJSONArray("data") ?: return@withContext emptyList()
        val ids = (0 until relData.length()).mapNotNull { i ->
            relData.getJSONObject(i)
                .optJSONArray("relationships")
                ?.let { rels ->
                    (0 until rels.length()).firstNotNullOfOrNull { j ->
                        val r = rels.getJSONObject(j)
                        if (r.optString("type") == "manga") r.optString("id").ifBlank { null } else null
                    }
                }
        }.take(10)
        if (ids.isEmpty()) return@withContext emptyList()
        val idsParam = ids.joinToString("&") { "ids[]=$it" }
        try { parseMangaList(get("$apiBase/manga?$idsParam&limit=10&includes[]=cover_art")) } catch (_: Exception) { emptyList() }
    }

    private fun parseIsoDateToMillis(iso: String): Long {
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

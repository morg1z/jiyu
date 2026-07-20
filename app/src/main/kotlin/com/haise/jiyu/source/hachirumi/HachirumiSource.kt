package com.haise.jiyu.source.hachirumi

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hachirumi běží na Guya (open-source manga reader, https://guya.moe) -
 * stejný engine sdílí i další scan skupiny. Kompletní data série (titul,
 * popis, autor, seznam kapitol i s obrázky) jsou v jednom REST volání
 * `/api/series/{slug}/` - žádné scrapování HTML detailu není potřeba.
 * Web nemá vyhledávací API ani stránkování katalogu (malá scan skupina,
 * vše na jedné stránce), takže getPopular/search jen filtrují seznam
 * z homepage podle titulu.
 */
@Singleton
class HachirumiSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "hachirumi"
    override val name = "Hachirumi"
    override val homepageUrl get() = base
    private val base = "https://hachirumi.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.card").mapNotNull { card ->
            val titleLink = card.selectFirst("h7.card-title a") ?: return@mapNotNull null
            val href = titleLink.attr("href")
            val title = titleLink.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val coverSrc = card.selectFirst("img.card-img-top")?.attr("data-src")
            val cover = coverSrc?.let { if (it.startsWith("http")) it else "$base$it" }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try { parseList(get(base)) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try { parseList(get(base)).filter { it.title.contains(query, ignoreCase = true) } } catch (_: Exception) { emptyList() }
    }

    private fun slugOf(mangaUrl: String) = mangaUrl.removePrefix("/read/manga/").trim('/')

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$base/api/series/${slugOf(manga.url)}/"))
            val artist = json.optString("artist").ifBlank { null }
            val author = json.optString("author").ifBlank { null }
            manga.copy(
                title = json.optString("title").ifBlank { manga.title },
                coverUrl = json.optString("cover").ifBlank { null }?.let { if (it.startsWith("http")) it else "$base$it" } ?: manga.coverUrl,
                description = json.optString("description").ifBlank { null }?.let { Jsoup.parse(it).text() },
                author = author ?: artist,
                artist = artist,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$base/api/series/${slugOf(manga.url)}/"))
            val chapters = json.optJSONObject("chapters") ?: return@withContext emptyList()
            chapters.keys().asSequence().mapNotNull { chNum ->
                val ch = chapters.optJSONObject(chNum) ?: return@mapNotNull null
                val groups = ch.optJSONObject("groups") ?: return@mapNotNull null
                val groupId = groups.keys().asSequence().firstOrNull() ?: return@mapNotNull null
                val releaseDate = ch.optJSONObject("release_date")?.optLong(groupId, 0L) ?: 0L
                val title = ch.optString("title").ifBlank { null }
                val name = if (title != null) "Chapter $chNum: $title" else "Chapter $chNum"
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/read/manga/${slugOf(manga.url)}/$chNum/1/",
                    name = name,
                    chapterNumber = chNum.toFloatOrNull() ?: 0f,
                    dateUpload = releaseDate * 1000,
                )
            }.sortedByDescending { it.chapterNumber }.toList()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val slug = chapter.url.removePrefix("/read/manga/").substringBefore("/")
            val chNum = Regex("""/read/manga/[^/]+/([^/]+)/""").find(chapter.url)?.groupValues?.get(1) ?: return@withContext emptyList()
            val json = JSONObject(get("$base/api/series/$slug/"))
            val ch = json.optJSONObject("chapters")?.optJSONObject(chNum) ?: return@withContext emptyList()
            val folder = ch.optString("folder").ifBlank { return@withContext emptyList() }
            val groups = ch.optJSONObject("groups") ?: return@withContext emptyList()
            val groupId = groups.keys().asSequence().firstOrNull() ?: return@withContext emptyList()
            val fileNames = groups.optJSONArray(groupId) ?: return@withContext emptyList()
            (0 until fileNames.length()).map { i ->
                val fn = fileNames.getString(i)
                val url = "$base/media/manga/$slug/chapters/$folder/$groupId/$fn"
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

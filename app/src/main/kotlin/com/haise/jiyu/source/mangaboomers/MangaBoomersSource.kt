package com.haise.jiyu.source.mangaboomers

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
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
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaBoomersSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangaboomers"
    override val name = "Manga Boomers"
    override val homepageUrl get() = base
    private val base = "https://manga-boomers.cz"

    // Vlastni pocet polozek na "stranku" pri tagovem filtru - /api/searchManga
    // zadnou paginaci nema (vraci vsechny sedici tituly najednou), stranka se
    // proto simuluje az v Kotlinu.
    private val genrePageSize = 20

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // /api/searchManga cte tělo requestu jako JSON, i kdyz Content-Type hlavicka
    // (stejne jako u vsech ostatnich /api/* volani teto SPA) hlasi urlencoded -
    // overeno zive, urlencoded serializace parametru byla serverem tise ignorovana
    // a vratila neprefiltrovany seznam, zatimco JSON telo filtr skutecne aplikuje.
    private fun postJson(url: String, jsonBody: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(jsonBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val json = JSONObject(postJson("$base/api/optionsForSearch", "{}"))
            val arr = json.optJSONArray("tags") ?: return@withContext emptyList()
            val tags = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val tagId = o.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val label = o.optString("name").ifBlank { return@mapNotNull null }
                FilterTag(id = tagId.toString(), label = label)
            }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchByGenre(genreId: String, page: Int): List<SManga> {
        val gid = genreId.toIntOrNull() ?: return emptyList()
        val json = JSONObject(postJson("$base/api/searchManga", "{\"genres\":[{\"id\":$gid,\"state\":1}]}"))
        val arr = json.optJSONArray("mangas") ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val mangaId = o.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { return@mapNotNull null }
            val thumb = o.optString("thumbnail").takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else "$base$it" }
            SManga(sourceId = id, url = "/manga/$mangaId", title = name, coverUrl = thumb)
        }
        return all.drop((page - 1) * genrePageSize).take(genrePageSize)
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return (
            doc.select(".manga-item, .post-item, .page-item-detail, .c-tabs-item__content")
                .takeIf { it.isNotEmpty() }
                ?: doc.select("article, .entry, .manga")
        ).mapNotNull { el ->
            val link = el.selectFirst("a[href*='manga-boomers'], a[href^='/']") ?: return@mapNotNull null
            val href = link.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
            val title = (el.selectFirst("h3, h2, .title, .manga-name, .post-title")?.text()
                ?: link.attr("title")
                ?: link.text()).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { fetchByGenre(filter.genres.first(), page) } catch (_: Exception) { emptyList() }
        }
        try { parseList(get("$base/?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { fetchByGenre(filter.genres.first(), page) } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?s=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h1, .manga-title, .post-title h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".summary_image img, .manga-cover img, .thumb img")?.let {
                    it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                } ?: manga.coverUrl,
                description = doc.selectFirst(".summary__content p, .manga-summary p, .description")?.text(),
                genres = doc.select(".genres-content a, .manga-genres a").map { it.text() },
                author = doc.selectFirst(".author-content a, .manga-author a")?.text(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val chapters = doc.select(".wp-manga-chapter a, .chapter-list a, li.chapter a")
            chapters.mapIndexed { i, a ->
                SChapter(
                    sourceId = id, mangaUrl = manga.url,
                    url = a.attr("href").removePrefix(base),
                    name = a.text().trim().takeIf { it.isNotBlank() } ?: "Kapitola ${i + 1}",
                    chapterNumber = (chapters.size - i).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select(".reading-content img, .page-break img, .chapter-content img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

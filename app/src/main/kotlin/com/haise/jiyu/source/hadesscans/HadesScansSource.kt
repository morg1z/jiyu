package com.haise.jiyu.source.hadesscans

import com.haise.jiyu.source.bodyOrThrow

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
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * hadesscans.com - WordPress (Rank Math SEO plugin jako Eva Scans/Scythe Scans), ale
 * NENI Madara ani MangaThemesia - vlastni komercni motiv s "cx-" prefixovanymi tridami
 * (napr. "cx-poster-card", "cx-chapter-item"), overeno zive.
 *
 * Seznam kapitol i detail se renderuji server-side normalne, ALE stranky kapitoly
 * (`#readerarea`) jsou PRAZDNE - obrazky se dodavaji az JS pres WordPress REST API
 * (`/wp-json/wp/v2/posts?slug=...`), kde `content.rendered` obsahuje hotove `<img>` tagy
 * v poradi stranek. Misto renderovani JS appka zavola stejny REST endpoint primo.
 */
@Singleton
class HadesScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "hadesscans"
    override val name = "Hades Scans"
    override val homepageUrl get() = base
    private val base = "https://hadesscans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manhwa" -> "MANHWA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANGA"
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.cx-poster-card").mapNotNull { card ->
            val titleEl = card.selectFirst("h3.cx-poster-card__title") ?: return@mapNotNull null
            val title = titleEl.text().trim().ifBlank { return@mapNotNull null }
            val href = card.selectFirst("a.cx-poster-card__body-link, a.cx-poster-card__cover-link")
                ?.absUrl("href")?.ifBlank { null } ?: return@mapNotNull null
            val cover = card.selectFirst(".cx-poster-card__cover img")?.attr("src")?.trim()?.ifBlank { null }
            val type = card.selectFirst("span.cx-poster-card__badge--type")?.text()?.trim()
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = normalizeContentType(type))
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/manga/" else "$base/manga/page/$page/"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val description = doc.selectFirst("div.cx-single-synopsis__body")?.text()?.trim()?.ifBlank { null }
            val type = doc.selectFirst("span.cx-single-hero__badge")?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1.cx-single-hero__title")?.text()?.trim() ?: manga.title,
                description = description,
                genres = doc.select("a.cx-genre-chip").map { it.text().trim() }.filter { it.isNotBlank() },
                contentType = normalizeContentType(type),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("a.cx-chapter-item[href]").mapNotNull { row ->
                val href = row.absUrl("href").ifBlank { return@mapNotNull null }
                val num = row.attr("data-cx-chapter-title").toFloatOrNull()
                    ?: Regex("""[\d.]+""").find(row.selectFirst("span.cx-chapter-item__title")?.text().orEmpty())?.value?.toFloatOrNull()
                    ?: return@mapNotNull null
                val name = row.selectFirst("span.cx-chapter-item__title")?.text()?.trim() ?: "Chapter $num"
                val dateAttr = row.selectFirst("time.cx-chapter-item__date")?.attr("datetime")
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseIsoDate(dateAttr),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseIsoDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // Stranka kapitoly ma prazdny #readerarea (obrazky dodava az JS) - misto renderovani
    // se zavola primo WordPress REST API, ktere vraci hotove HTML s <img> tagy.
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val slug = chapter.url.trimEnd('/').substringAfterLast('/')
            val apiUrl = "$base/wp-json/wp/v2/posts?slug=$slug"
            val json = JSONArray(get(apiUrl))
            if (json.length() == 0) return@withContext emptyList()
            val contentHtml = json.getJSONObject(0).getJSONObject("content").getString("rendered")
            Jsoup.parse(contentHtml).select("img").mapIndexedNotNull { i, img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }.trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (_: Exception) { emptyList() }
    }
}

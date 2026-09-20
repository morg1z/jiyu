package com.haise.jiyu.source.hadesscans

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.normalizeContentType
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
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
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

    // "/genres/" ma seznam vsech zanru (cx-genre-pill) s vlastni archivni strankou
    // "/genres/{slug}/page/{page}/" - stejna struktura vypisu (article.cx-poster-card)
    // jako obycejny katalog, jen jina sada titulu (overeno zive: Action str.1 vs str.2).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/genres/"))
            val tags = doc.select("a.cx-genre-pill").mapNotNull { a ->
                val slug = a.absUrl("href").trimEnd('/').substringAfterLast("/genres/").ifBlank { null } ?: return@mapNotNull null
                val label = a.selectFirst("span.cx-genre-pill__name")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreUrl(slug: String, page: Int) =
        if (page <= 1) "$base/genres/$slug/" else "$base/genres/$slug/page/$page/"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Genre archiv nema vlastni razeni - pri vybranem tagu se filter.sortBy
            // ignoruje, kombinace vice zanru najednou neni podporovana (jen prvni vybrany).
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(genreUrl(filter.genres.first(), page)))
            }
            val url = if (page <= 1) "$base/manga/" else "$base/manga/page/$page/"
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(genreUrl(filter.genres.first(), page)))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
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
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("a.cx-chapter-item[href]").mapNotNull { row ->
                val href = row.absUrl("href").ifBlank { return@mapNotNull null }
                val num = row.attr("data-cx-chapter-title").toFloatOrNull()
                    ?: parseChapterNumber(row.selectFirst("span.cx-chapter-item__title")?.text().orEmpty())
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
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseIsoDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


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
                val src = img.lazySrc().orEmpty().trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

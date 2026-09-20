package com.haise.jiyu.source.spiderscans

import com.haise.jiyu.source.SourceHttp
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * spiderscans.xyz - bespoke ("wp-theme-spiderscans") web, NE Madara/MangaThemesia
 * (zadny standardni selektor nesedi, overeno zive). Vypis "/manga/" pouziva vlastni
 * karty `div.manga-card` (`a[href]` + `img.manga-card-img` + `div.manga-card-title`),
 * zanrovy filtr pres "?genre={slug}&sort=latest" (chipy s `data-genre`, overeno zive:
 * action vs comedy vraci odlisne sady), razeni pres "?sort=latest|popular|rating|new|az"
 * a fulltextove hledani funkcni pres "?search=..." (overeno zive - "vampire" vraci presne
 * 1 vysledek misto vsech 5). POZOR: cely katalog ma v soucasnosti jen 5 titulu (overeno
 * zive - "?page=2" vraci bajtove identickou sadu jako strana 1, zadne dalsi stranky).
 *
 * Detail mangy je staticky HTML - `div.detail-meta-item` dvojice (Author/Status/Type/
 * Released/Updated/Age Rating), zanry v `a.detail-tag`, popis v `div.detail-synopsis`.
 * Seznam kapitol `a.chapter-item[data-chapter]` s `div.chapter-num`/`chapter-title-text`/
 * `chapter-date`. Stranky kapitoly jsou primo `img.reader-page-img` se skutecnou `src`
 * (Cloudflare R2 CDN URL) - zadny lazy-load trik, overeno zive.
 */
@Singleton
class SpiderScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "spiderscans"
    override val name = "SpiderScans"
    override val homepageUrl get() = base
    private val base = "https://spiderscans.xyz"

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/manga/"))
            val tags = doc.select("a.genre-filter-chip[data-genre]").mapNotNull { a ->
                val slug = a.attr("data-genre").trim().ifBlank { return@mapNotNull null }
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.manga-card").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = card.selectFirst("div.manga-card-title")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = card.selectFirst("img.manga-card-img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            val sort = if (filter.sortBy == "latest") "latest" else "popular"
            val genreParam = if (genre != null) "&genre=${URLEncoder.encode(genre, "UTF-8")}" else ""
            parseList(get("$base/manga/?sort=$sort&page=$page$genreParam"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            val genreParam = if (genre != null) "&genre=${URLEncoder.encode(genre, "UTF-8")}" else ""
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/manga/?search=$q&page=$page$genreParam"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun statValue(doc: Document, label: String): String? =
        doc.select("div.detail-meta-item").firstOrNull {
            it.selectFirst("span.detail-meta-label")?.text()?.trim().equals(label, ignoreCase = true)
        }?.selectFirst("span.detail-meta-value")?.text()?.trim()?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1.detail-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.detail-synopsis")?.text()?.trim()?.ifBlank { null },
                genres = doc.select("a.detail-tag").map { it.text().trim() }.filter { it.isNotBlank() },
                author = statValue(doc, "Author"),
                status = statValue(doc, "Status")?.lowercase(),
                year = statValue(doc, "Released")?.toIntOrNull(),
                contentType = normalizeContentType(statValue(doc, "Type")),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("a.chapter-item[data-chapter]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = a.attr("data-chapter").toFloatOrNull() ?: return@mapNotNull null
                val name = a.selectFirst("div.chapter-title-text")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                    ?.ifBlank { null } ?: "Chapter $num"
                val dateText = a.selectFirst("div.chapter-date")?.text()?.trim()
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = num, dateUpload = parseChapterDate(dateText),
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseChapterDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select("img.reader-page-img").mapIndexedNotNull { i, img ->
                val src = img.attr("src").trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

package com.haise.jiyu.source.mangamikan

import com.haise.jiyu.util.resolveSourceUrl
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangamikan.com - vlastni sablona, plne server-rendered vcetne cteni.
 *
 * 2026-10 redesign: listing karty jsou `article.collection-book`
 * (a.collection-book__cover + img[src] + h3>a), sort hodnoty se zmenily
 * (views7->popular, latest->updated), detail ma .collection-synopsis /
 * .collection-creator / .collection-book-tags, kapitoly jsou
 * a[data-chapter-row] a stranky primo img[data-page] s nesou Signed
 * URL /mangas/{id}/pNNNN_*.webp (drive podepsane /i.php uz neni).
 */
@Singleton
class MangaMikanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangamikan"
    override val name = "MangaMikan"
    override val homepageUrl get() = base
    private val base = "https://mangamikan.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(article: Element): SManga? {
        val a = article.selectFirst("a.collection-book__cover") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = article.selectFirst("h3 a")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()?.removePrefix("Cover of ")
            ?: return null
        if (title.isBlank()) return null
        val raw = a.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
        val cover = when {
            raw == null -> null
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> "$base$raw"
            else -> null
        }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override val supportsTagFilter: Boolean get() = true

    // Select "genre" na /browse je server-rendered staticky seznam (~40 polozek),
    // ktery se pri behu appky nemeni - stacit ho dotahnout jednou a v pameti sdilet
    // mezi vsemi otevrenimi Filtru.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/browse"))
            val tags = doc.select("select[name=genre] option[value]").mapNotNull { opt ->
                val value = opt.attr("value").trim().ifBlank { return@mapNotNull null }
                val label = opt.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = value, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Formular na /browse filtruje status= ongoing|completed|hiatus a sort=
    // updated|added|popular|chapters|title|title_desc (stejny engine jako
    // mangacherri, overeno zive na obou webech).
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus")
    override val availableSorts: Set<String> get() = setOf("popular", "latest", "title")

    private val sortValues = mapOf(
        "latest" to "updated", "popular" to "popular", "title" to "title",
    )
    private val siteStatuses = setOf("ongoing", "completed", "hiatus")

    private fun browseUrl(page: Int, filter: MangaFilter, query: String? = null): String {
        val sort = sortValues[filter.sortBy] ?: "popular"
        val sb = StringBuilder("$base/browse?sort=$sort&page=$page")
        filter.genres.firstOrNull()?.let { sb.append("&genre=").append(it) }
        filter.status?.takeIf { it in siteStatuses }?.let { sb.append("&status=").append(it) }
        query?.let { sb.append("&q=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(browseUrl(page, filter)))
            doc.select("article.collection-book").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val doc = Jsoup.parse(get(browseUrl(page, filter, query)))
            doc.select("article.collection-book").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                description = doc.selectFirst("p.collection-synopsis")?.text()?.trim()?.ifBlank { null },
                genres = doc.select(".collection-book-tags a").map { it.text().trim() }.filter { it.isNotBlank() },
                author = doc.selectFirst("p.collection-creator")?.text()?.trim()
                    ?.removePrefix("By ")?.trim()?.ifBlank { null },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("a[data-chapter-row]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.selectFirst(".chapter-arsenal__name strong")?.text()
                    ?.replace(Regex("\\s*NEW CHAPTER\\s*$"), "")?.trim()
                    ?: a.text().trim().ifBlank { return@mapNotNull null }
                val num = parseChapterNumber(name) ?: 0f
                // data-created-at je unix timestamp v sekundach.
                val date = a.attr("data-created-at").toLongOrNull()?.times(1000) ?: 0L
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = date)
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[data-page]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val abs = if (url.startsWith("http")) url else "$base$url"
                Page(i, abs, abs)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

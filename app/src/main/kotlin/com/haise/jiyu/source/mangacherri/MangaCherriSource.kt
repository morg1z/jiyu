package com.haise.jiyu.source.mangacherri

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangacherri.com - plne server-rendered.
 *
 * 2026-10 redesign: web bezel na stejnem enginu jako mangamikan.com
 * (article.cherri-list-card, /title/{slug}, /read/{id}, /genre/{slug},
 * img[data-page] v ctecce). Puvodni routy home.php/new-chapters.php/
 * genre.php/search.php byly odstraneny (302 na homepage). Listing:
 * /manga?sort=popular|updated&page=N&genre={slug}&q={query} - vsechny
 * parametry funguji server-side (overeno zive).
 */
@Singleton
class MangaCherriSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangacherri"
    override val name = "MangaCherri"
    override val homepageUrl get() = base
    private val base = "https://mangacherri.com"

    private companion object {
        val SITE_STATUSES = setOf("ongoing", "completed", "hiatus")
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseDoc(url: String) = Jsoup.parse(get(url), url)

    private fun parseCard(article: Element): SManga? {
        val a = article.selectFirst("a.cherri-list-card__cover") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = article.selectFirst("h2 a")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()?.removePrefix("Cover of ")
            ?: return null
        if (title.isBlank()) return null
        val cover = a.selectFirst("img")?.attr("src")?.trim()
            ?.let { absoluteMediaUrl(base, it) }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    // ─── Filtrovani podle zanru ──────────────────────────────────────────────
    // /manga?genre={slug} filtruje server-side (overeno: drama -> 194 stories).
    // Hodnoty zanru jsou slugy z <select name="genre"> na /manga.

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = parseDoc("$base/manga")
            val tags = doc.select("select[name=genre] option[value]").mapNotNull { opt ->
                val value = opt.attr("value").trim().ifBlank { return@mapNotNull null }
                val label = opt.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = value, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("article.cherri-list-card").mapNotNull(::parseCard).distinctBy { it.url }

    // Web filtruje status= ongoing|completed|hiatus i sort= (overeno zive -
    // ruzne sady karet pro ongoing/completed i sort=title vs updated).
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus")
    override val availableSorts: Set<String> get() = setOf("popular", "latest", "title")

    private val sortValues = mapOf(
        "latest" to "updated", "popular" to "popular", "title" to "title",
    )

    private fun listingUrl(page: Int, filter: MangaFilter, query: String? = null): String {
        val sort = sortValues[filter.sortBy] ?: "popular"
        val sb = StringBuilder("$base/manga?sort=$sort&page=$page")
        filter.genres.firstOrNull()?.let { sb.append("&genre=").append(URLEncoder.encode(it, "UTF-8")) }
        filter.status?.takeIf { it in SITE_STATUSES }?.let { sb.append("&status=").append(it) }
        query?.let { sb.append("&q=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(parseDoc(listingUrl(page, filter))) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try { parseList(parseDoc(listingUrl(page, filter, query))) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = parseDoc(resolveSourceUrl(base, manga.url))
            manga.copy(
                description = doc.selectFirst(".cherri-detail__description")
                    ?.text()?.trim()?.ifBlank { null },
                genres = doc.select(".cherri-detail__chips a[href*=/genre/]")
                    .map { it.text().trim() }.filter { it.isNotBlank() },
                // Autor je obycejny <p><strong>jmeno</strong></p> za description blokem.
                author = doc.selectFirst(".cherri-detail__description ~ p strong")
                    ?.text()?.trim()?.ifBlank { null },
                // "ROSE LIBRARY . ONGOING" - status je za oddelovacem.
                status = doc.selectFirst(".cherri-detail__copy .eyebrow")?.text()
                    ?.substringAfter('·')?.trim()?.lowercase()?.ifBlank { null },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = parseDoc(resolveSourceUrl(base, manga.url))
            doc.select("a.cherri-chapter-row[data-chapter-row]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                val name = a.selectFirst("strong")?.text()
                    ?.replace(Regex("\\s*NEW\\s*$"), "")?.trim()
                    ?: a.text().trim().ifBlank { return@mapNotNull null }
                val num = parseChapterNumber(name) ?: 0f
                val date = a.attr("data-created-at").toLongOrNull()?.times(1000) ?: 0L
                SChapter(sourceId = id, mangaUrl = manga.url, url = toSourcePath(base, href), name = name, chapterNumber = num, dateUpload = date)
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[data-page]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

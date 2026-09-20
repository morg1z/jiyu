package com.haise.jiyu.source.royalroad

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoyalRoadSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "royalroad"
    override val name = "Royal Road"
    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://www.royalroad.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select(".fiction-list-item").mapNotNull { el ->
            val link = el.selectFirst(".fiction-title a, h2 a, h3 a") ?: return@mapNotNull null
            val href = link.attr("href").let {
                resolveSourceUrl(base, it)
            }
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = link.text().trim(),
                coverUrl = el.selectFirst("img")?.let { img ->
                    img.attr("src").takeIf { s -> s.isNotBlank() }
                }?.let { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "$base$it" else it },
                contentType = "NOVEL",
            )
        }
    }

    // "/fictions/search" ma DLE filter formular s tlacitky zanru (data-tag="..."),
    // jejichz hodnoty jde poslat jako "tagsAdd" query parametr na tu samou stranku -
    // overeno zive: tagsAdd=horror str.1 vs str.2 - jine tituly, i oproti
    // nefiltrovanemu "/fictions/best-rated" vypisu.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/fictions/search"), base)
            val tags = doc.select("button.search-tag[data-tag]").mapNotNull { btn ->
                val slug = btn.attr("data-tag").trim().ifBlank { null } ?: return@mapNotNull null
                val label = btn.attr("data-label").trim().ifBlank { slug }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                val tag = URLEncoder.encode(filter.genres.first(), "UTF-8")
                return@withContext parseList(get("$base/fictions/search?tagsAdd=$tag&page=$page"))
            }
            val path = if (filter.sortBy == "latest") "latest-updates" else "best-rated"
            parseList(get("$base/fictions/$path?page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                val tag = URLEncoder.encode(filter.genres.first(), "UTF-8")
                return@withContext parseList(get("$base/fictions/search?tagsAdd=$tag&page=$page"))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/fictions/search?title=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base)
            manga.copy(
                title = doc.selectFirst("h1.font-white, .fiction-name")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".thumbnail img, .fiction-image img")?.attr("src")
                    ?.let { if (it.startsWith("/")) "$base$it" else it } ?: manga.coverUrl,
                description = doc.selectFirst(".description .hidden-content, .description")
                    ?.text()?.trim(),
                genres = doc.select(".tags a, .fiction-tags a").map { it.text().trim() }
                    .filter { it.isNotBlank() },
                author = doc.selectFirst(".author a, [property='author'] a")?.text()?.trim(),
                status = if (doc.selectFirst(".label-success, .ongoing") != null) "Ongoing"
                         else if (doc.selectFirst(".label-default, .completed") != null) "Completed"
                         else null,
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base)
            val df = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            doc.select("table#chapters tbody tr, #chapters-list tbody tr").mapIndexedNotNull { i, row ->
                val link = row.selectFirst("td a[href*='/chapter/']") ?: return@mapIndexedNotNull null
                val href = link.attr("href").let {
                    toSourcePath(base, it)
                }
                val name = link.text().trim().ifBlank { "Chapter ${i + 1}" }
                val dateStr = row.selectFirst("td[data-content], time")
                    ?.attr("data-content")?.takeIf { it.isNotBlank() }
                    ?: row.selectFirst("time")?.attr("datetime")?.substringBefore("T")
                val date = dateStr?.let {
                    try { df.parse(it)?.time } catch (e: Exception) { e.rethrowIfControl(); null }
                } ?: 0L
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = date,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)), base)
            val content = doc.selectFirst(".chapter-inner .chapter-content, .chapter-content .inner-chapter, .chapter-page")
                ?: return@withContext emptyList()
            content.select("script, style, .ads-holder, .portlet-body .hidden").remove()
            val text = content.text().trim()
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

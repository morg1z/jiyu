package com.haise.jiyu.source.royalroad

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
    private val base = "https://www.royalroad.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select(".fiction-list-item").mapNotNull { el ->
            val link = el.selectFirst(".fiction-title a, h2 a, h3 a") ?: return@mapNotNull null
            val href = link.attr("href").let {
                if (it.startsWith("http")) it else "$base$it"
            }
            SManga(
                sourceId = id,
                url = href.removePrefix(base),
                title = link.text().trim(),
                coverUrl = el.selectFirst("img")?.let { img ->
                    img.attr("src").takeIf { s -> s.isNotBlank() }
                }?.let { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "$base$it" else it },
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get("$base/fictions/best-rated?page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/fictions/search?title=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
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
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
            val df = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            doc.select("table#chapters tbody tr, #chapters-list tbody tr").mapIndexedNotNull { i, row ->
                val link = row.selectFirst("td a[href*='/chapter/']") ?: return@mapIndexedNotNull null
                val href = link.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(base) else it
                }
                val name = link.text().trim().ifBlank { "Chapter ${i + 1}" }
                val dateStr = row.selectFirst("td[data-content], time")
                    ?.attr("data-content")?.takeIf { it.isNotBlank() }
                    ?: row.selectFirst("time")?.attr("datetime")?.substringBefore("T")
                val date = dateStr?.let {
                    try { df.parse(it)?.time } catch (_: Exception) { null }
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
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"), base)
            val content = doc.selectFirst(".chapter-inner .chapter-content, .chapter-content .inner-chapter, .chapter-page")
                ?: return@withContext emptyList()
            content.select("script, style, .ads-holder, .portlet-body .hidden").remove()
            val text = content.text().trim()
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.mangapill

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Obrázky se servírují z cdn.readdetectiveconan.com a vyžadují Referer:
 * mangapill.com (403 bez něj) - viz hotlinkReferers v AppModule.kt.
 */
@Singleton
class MangapillSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangapill"
    override val name = "Mangapill"
    override val homepageUrl get() = base
    private val base = "https://mangapill.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        val covers = doc.select("a.relative.block[href^=/manga/]")
            .associate { it.attr("href") to it.selectFirst("img")?.attr("data-src") }
        return doc.select("a.mb-2[href^=/manga/]").mapNotNull { titleLink ->
            val href = titleLink.attr("href")
            val title = titleLink.selectFirst("div")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SManga(sourceId = id, url = href, title = title, coverUrl = covers[href])
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/search?q=&type=manga&status=&page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?q=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusText = doc.selectFirst("label:contains(Status)")?.nextElementSibling()?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("p.text-sm")?.text()?.trim(),
                genres = doc.select("a[href^=/search?genre=]").map { it.text() },
                status = when {
                    statusText?.contains("publish", ignoreCase = true) == true -> "Ongoing"
                    statusText?.contains("finish", ignoreCase = true) == true -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val links = doc.select("a[href^=/chapters/]")
            links.mapIndexedNotNull { i, a ->
                val href = a.attr("href")
                val text = a.text().trim()
                val num = Regex("""Chapter\s+([\d.]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (links.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img.js-page[data-src]").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

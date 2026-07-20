package com.haise.jiyu.source.nihonkuni

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
 * Detail stránka schovává hlavní <h1> titul za base64 "data-enc" atribut
 * (jednoduchý anti-scraping trik), proto se titul bere ze seznamu
 * (a.manga-title) a na detailu se nepřepisuje. Obrázky nevyžadují Referer
 * (`referrerpolicy="no-referrer"` přímo na <img> tagu na zdrojovém webu).
 */
@Singleton
class NihonKuniSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "nihonkuni"
    override val name = "NihonKuni (Raw)"
    override val language = "ja"
    override val homepageUrl get() = base
    private val base = "https://nihonkuni.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun normalizeHref(href: String) = if (href.startsWith("/")) href else "/$href"

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.manga-card").mapNotNull { card ->
            val titleLink = card.selectFirst("a.manga-title") ?: return@mapNotNull null
            val href = normalizeHref(titleLink.attr("href"))
            val title = titleLink.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val style = card.selectFirst("a.manga-cover")?.attr("style").orEmpty()
            val cover = Regex("""url\('([^']+)'""").find(style)?.groupValues?.get(1)
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/manga-list.html?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/manga-list.html?name=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    private fun fieldValue(doc: org.jsoup.nodes.Document, label: String) =
        doc.selectFirst("div.info-field-label:contains($label)")
            ?.nextElementSibling()
            ?.takeIf { it.hasClass("info-field-value") }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusText = fieldValue(doc, "Status")?.text()?.trim()
            manga.copy(
                description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null },
                author = fieldValue(doc, "Author(s)")?.selectFirst("a")?.text()?.trim(),
                genres = fieldValue(doc, "Genre(s)")?.select("a")?.map { it.text() } ?: emptyList(),
                status = when {
                    statusText == null -> null
                    statusText.contains("going", ignoreCase = true) -> "Ongoing"
                    statusText.contains("complet", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val links = doc.select("div.chapters-list-wrapper a[href]")
            links.mapIndexedNotNull { i, a ->
                val href = normalizeHref(a.attr("href"))
                val text = a.selectFirst(".chapter-name")?.text()?.trim()
                    ?: a.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                val num = Regex("""chapter-(\d+(?:\.\d+)?)""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (links.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img.chapter-img[src]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

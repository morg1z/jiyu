package com.haise.jiyu.source.asurascans

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

@Singleton
class AsuraScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "asurascans"
    override val name = "Asura Scans"
    private val base = "https://asuracomic.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.grid > a[href], .series-card, .item-summary").mapNotNull { el ->
            val link = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").let { if (it.startsWith(base)) it.removePrefix(base) else it }
            val title = (el.selectFirst("span.block, .series-title, h3, h2")?.text()
                ?: link.attr("title")
                ?: link.text()).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
            }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/series?page=$page&order=rating")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/series?page=1&name=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("span.text-xl, h1, .series-title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("img.rounded")?.let {
                    it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
                } ?: manga.coverUrl,
                description = doc.selectFirst("span.font-medium.text-sm")?.text()?.trim(),
                genres = doc.select("div.flex a[href*=genre]").map { it.text().trim() },
                author = doc.selectFirst("div:contains(Author) span.font-medium")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val chapters = doc.select("div.scrollbar-thumb-themecolor a[href*=chapter]")
                .ifEmpty { doc.select("a[href*=chapter]").filter { el -> el.text().contains("chapter", ignoreCase = true) } }
            chapters.mapIndexed { i, a ->
                val href = a.attr("href").let { if (it.startsWith(base)) it.removePrefix(base) else it }
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (chapters.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val doc = Jsoup.parse(html)
            val imgs = doc.select("#readerarea img, .reading-content img, .chapter-content img")
            if (imgs.isNotEmpty()) {
                imgs.mapIndexedNotNull { i, img ->
                    val url = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    Page(i, url, url)
                }
            } else {
                val match = Regex("""ts_reader\.run\(\{"sources":\[.*?"images":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(html)
                match?.groupValues?.get(1)
                    ?.split(",")
                    ?.map { it.trim().trim('"') }
                    ?.filter { it.isNotBlank() }
                    ?.mapIndexed { i, url -> Page(i, url, url) }
                    ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }
}

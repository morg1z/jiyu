package com.haise.jiyu.source.mangareader

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
class MangaReaderSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangareader"
    override val name = "MangaReader"

    private val base = "https://mangareader.to"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseCardList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".manga-list .manga-item, .item-thumb a.manga-thumb, .content-list .manga-item").mapNotNull { el ->
            val link = el.selectFirst("a") ?: el.takeIf { it.tagName() == "a" } ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = link.attr("title").takeIf { it.isNotBlank() }
                ?: el.selectFirst(".manga-name, .title, h3")?.text()?.trim()
                ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }?.let { if (it.startsWith("//")) "https:$it" else it }
            SManga(sourceId = id, url = if (href.startsWith("http")) href.removePrefix(base) else href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseCardList(get("$base/home?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseCardList(get("$base/search?keyword=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val title = doc.selectFirst(".manga-name, h2.manga-name, h1.manga-name")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".manga-poster img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }?.let { if (it.startsWith("//")) "https:$it" else it } ?: manga.coverUrl
            val desc = doc.selectFirst(".description, .sort-desc")?.text()?.trim()
            val genres = doc.select(".item-list a[href*='genre']").map { it.text().trim() }.filter { it.isNotBlank() }
            val author = doc.selectFirst(".item-head:contains(Author) + .item-content, .author a")?.text()?.trim()
            manga.copy(title = title, coverUrl = cover, description = desc, genres = genres, author = author)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val mangaId = manga.url.substringAfterLast("/").substringBefore("?")
            val html = get("$base/ajax/manga/reading-list/$mangaId?readType=1&chapterPage=1")
            val doc = Jsoup.parse(org.json.JSONObject(html).optString("html", ""))
            doc.select("li a[href*='/read/']").mapIndexed { i, el ->
                val href = el.attr("href").removePrefix(base)
                val name = el.selectFirst(".name")?.text()?.trim()
                    ?: el.text().trim().takeIf { it.isNotBlank() }
                    ?: "Chapter ${i + 1}"
                val num = Regex("""chapter[-/](\d+(?:\.\d+)?)""").find(href)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select(".iv-card img, #main-wrapper img[src*='cdn'], .reading-content img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

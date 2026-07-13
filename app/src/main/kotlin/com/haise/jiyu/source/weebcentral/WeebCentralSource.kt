package com.haise.jiyu.source.weebcentral

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
 * weebcentral.com je htmx/Alpine.js aplikace - vetsina obsahu se dotahuje
 * pres samostatne endpointy misto klasickeho server-rendered HTML na jedne
 * strance:
 *  - listing/vyhledavani: /search/data?...&text={query}&page={n} (vyzaduje
 *    kompletni sadu query parametru, jinak vraci 307 na /400)
 *  - kompletni seznam kapitol: /series/{id}/full-chapter-list (detailni
 *    stranka sama o sobe ukazuje jen zlomek)
 *  - stranky kapitoly: /chapters/{id}/images?is_prev=False&current_page=1&
 *    reading_style=long_strip (bez reading_style parametru vraci 400)
 */
@Singleton
class WeebCentralSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "weebcentral"
    override val name = "Weeb Central"
    private val base = "https://weebcentral.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.bg-base-300").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/series/]") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix(base)
            val title = card.selectFirst("a.link-hover")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    private fun searchData(query: String, page: Int, sort: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val s = URLEncoder.encode(sort, "UTF-8")
        return "$base/search/data?sort=$s&order=Descending&official=Any&anime=Any&adult=Any&text=$q&page=$page&display_mode=Full%20Display"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get(searchData("", page, "Popularity"))) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try { parseList(get(searchData(query, page, "Best Match"))) } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: manga.coverUrl,
                description = doc.selectFirst("p.whitespace-pre-wrap")?.text()?.trim(),
                genres = doc.select("a[href*=included_tag=]").map { it.text().trim() }.filter { it.isNotBlank() },
                author = doc.selectFirst("a[href*=\"search?author=\"]")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val id2 = manga.url.substringAfter("/series/").substringBefore("/")
            val doc = Jsoup.parse(get("$base/series/$id2/full-chapter-list"))
            val chapters = doc.select("a[href*=/chapters/]")
            chapters.mapIndexed { i, a ->
                val href = a.attr("href").removePrefix(base)
                val text = a.selectFirst("span.grow span")?.text()?.trim()?.ifBlank { null } ?: a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (chapters.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}/images?is_prev=False&current_page=1&reading_style=long_strip"))
            doc.select("img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

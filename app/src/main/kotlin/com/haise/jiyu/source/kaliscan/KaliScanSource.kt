package com.haise.jiyu.source.kaliscan

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
 * KaliScan (kaliscan.io) - klasicky server-rendered web (stary "1st Kiss
 * Manga" rodinny klon, sdili CDN domenu 1stmangago.com/1stmggv7.xyz).
 * Kapitoly i jejich seznam jsou primo v HTML, obrazky stranky jsou sice
 * vykreslovany JS-em az pri scrollu (lazy-load), ale kompletni seznam
 * podepsanych URL (s ?acc=...&expires=... tokenem) uz je soucasti
 * puvodni odpovedi serveru v `var chapImages = "url1,url2,...";`,
 * takze zadne dalsi API volani ani WebView neni potreba.
 */
@Singleton
class KaliScanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "kaliscan"
    override val name = "KaliScan"
    override val homepageUrl get() = base
    private val base = "https://kaliscan.io"

    private fun getHtml(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseBookList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".book-item").mapNotNull { item ->
            val link = item.selectFirst(".title h3 a") ?: item.selectFirst(".thumb a")
                ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim().ifBlank { link.attr("title").trim() }
                .ifBlank { return@mapNotNull null }
            val cover = item.selectFirst(".thumb img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseBookList(getHtml("$base/popular?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseBookList(getHtml("$base/search?keyword=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(getHtml("$base${manga.url}"))
            var author: String? = null
            var status: String? = null
            var genres: List<String> = emptyList()
            doc.select("div.detail div.meta p").forEach { p ->
                val label = p.text()
                when {
                    label.contains("Authors", ignoreCase = true) ->
                        author = p.select("a").joinToString(", ") { it.text().trim() }.ifBlank { null }
                    label.contains("Status", ignoreCase = true) ->
                        status = p.selectFirst("a span")?.text()?.trim()
                    label.contains("Genres", ignoreCase = true) ->
                        genres = p.select("a").map { it.text().trim().removeSuffix(",").trim() }
                }
            }
            manga.copy(
                title = doc.selectFirst("div.detail div.name h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.summary p.content")?.text()?.trim(),
                author = author,
                status = status,
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(getHtml("$base${manga.url}"))
            doc.select("ul#chapter-list li a").mapIndexed { i, a ->
                chapterFromRow(a, manga.url, i)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun chapterFromRow(a: Element, mangaUrl: String, index: Int): SChapter {
        val href = a.attr("href")
        val name = a.selectFirst("strong.chapter-title")?.text()?.trim()
            ?: a.text().trim().ifBlank { "Chapter ${index + 1}" }
        val chapterNumber = Regex("""chapter-([\d.]+)""").find(href)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
        val dateText = a.selectFirst("time.chapter-update")?.text()?.trim()
        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = href,
            name = name,
            chapterNumber = chapterNumber,
            dateUpload = parseRelativeDate(dateText),
        )
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val m = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(text)
            ?: return System.currentTimeMillis()
        val value = m.groupValues[1].toLongOrNull() ?: 1L
        val deltaMs = when (m.groupValues[2].lowercase()) {
            "second" -> value * 1_000L
            "minute" -> value * 60_000L
            "hour"   -> value * 3_600_000L
            "day"    -> value * 86_400_000L
            "week"   -> value * 7 * 86_400_000L
            "month"  -> value * 30 * 86_400_000L
            "year"   -> value * 365 * 86_400_000L
            else     -> 0L
        }
        return System.currentTimeMillis() - deltaMs
    }

    // Kompletni, uz podepsany seznam obrazku je vlozen primo v HTML jako
    // `var chapImages = "url1,url2,...";` - JS na strance ho jen pouziva
    // pro lazy-load pri scrollovani, takze zadny dalsi request neni potreba.
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = getHtml("$base${chapter.url}")
            val raw = Regex("""var\s+chapImages\s*=\s*"([^"]+)"""").find(html)
                ?.groupValues?.get(1) ?: return@withContext emptyList()
            raw.split(",").filter { it.isNotBlank() }.mapIndexed { i, url ->
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

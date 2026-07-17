package com.haise.jiyu.source.novelcool

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
 * Novel Cool (novelcool.com) - server-rendered custom web hostujici
 * jak manga (obrazky), tak skutecne textove novely. Listing pouziva
 * infinite-scroll bez odhalitelneho stránkovaciho parametru, takze
 * getPopular podporuje jen strankuA=1 (prvnich ~50 polozek).
 *
 * Text kapitoly nema stabilni staticky wrapper element - motiv ho
 * vklada pres `document.write(...)` uvnitr <script>, ktere Jsoup
 * nevykonava. Odstavce (<p>) proto konci jako obycejni sourozenci na
 * urovni <script> tagu, ohranicene realnymi znackami
 * `p.chapter-start-mark` a `p.chapter-end-mark`.
 */
@Singleton
class NovelCoolSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelcool"
    override val name = "Novel Cool"
    override val contentType: String get() = "NOVEL"
    private val base = "https://www.novelcool.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.book-item").mapNotNull { el ->
            val link = el.selectFirst("div.book-info a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("div.book-name")?.text()?.trim() ?: return@mapNotNull null
            val cover = el.selectFirst("div.book-pic img")?.attr("src")?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try { parseList(get("$base/category/popular.html")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search/?keywords=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val genres = doc.select("div.bookinfo-category-list div.bk-cate-item").mapNotNull { cate ->
                if (cate.className().contains("bk-going")) null else cate.selectFirst("a")?.text()?.trim()
            }
            manga.copy(
                title = doc.selectFirst("h1.bookinfo-title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("div.bookinfo-author span[itemprop=creator]")?.text()?.trim(),
                description = doc.selectFirst("div.bk-summary-txt")?.text()?.trim(),
                genres = genres,
                status = doc.selectFirst("div.bk-status div.bk-status-item a")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("div.chp-item a[href]").mapIndexed { i, a ->
                val href = a.attr("href")
                val title = a.selectFirst("span.chapter-item-headtitle")?.text()?.trim()
                    ?.ifBlank { null } ?: "Chapter ${i + 1}"
                val num = Regex("""\d+(?:\.\d+)?""").find(title)?.value?.toFloatOrNull() ?: (i + 1).toFloat()
                val dateText = a.selectFirst("span.chapter-item-time")?.text()?.trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = title,
                    chapterNumber = num,
                    dateUpload = parseDate(dateText),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long = try {
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.ENGLISH).parse(text.orEmpty())?.time ?: 0L
    } catch (_: Exception) { 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            val startMark = doc.selectFirst("p.chapter-start-mark")
            val endMark = doc.selectFirst("p.chapter-end-mark")
            val parent = startMark?.parent()
            if (parent == null || endMark == null) return@withContext emptyList()

            val siblings = parent.children()
            val startIdx = siblings.indexOf(startMark)
            val endIdx = siblings.indexOf(endMark)
            if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) return@withContext emptyList()

            val text = siblings.subList(startIdx + 1, endIdx)
                .filter { it.tagName() == "p" }
                .joinToString("\n\n") { it.text().trim() }
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

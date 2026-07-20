package com.haise.jiyu.source.galaxymanga

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
 * Galaxy Manga (galaxymanga.io) - WordPress motiv rodiny "Mangastream"
 * (stejne selektory jako [com.haise.jiyu.source.rawkuma.RawKumaSource],
 * ale jina domena - proto vlastni trida, ne Madara). Seznam stranek
 * kapitoly neni server-rendered (#readerarea je prazdny div, JS ho plni
 * z `ts_reader.run({...,"images":[...]})` vlozeneho v <script>).
 */
@Singleton
class GalaxyMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "galaxymanga"
    override val name = "Galaxy Manga"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://galaxymanga.io"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".bsx").mapNotNull { el ->
            val link = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { el.selectFirst(".tt")?.text().orEmpty() }
                .trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = link.attr("href"), title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/manga/?page=$page&order=popular")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val status = doc.select("div.imptdt").firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.selectFirst("i")?.text()?.trim()

            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst(".entry-content-single")?.text()?.trim(),
                genres = doc.select(".mgen a").map { it.text().trim() },
                status = status,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("div.eplister li a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.selectFirst("span.chapternum")?.text()?.trim()
                    ?: a.text().trim().ifBlank { "Chapter ${i + 1}" }
                val num = Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                val dateText = a.selectFirst("span.chapterdate")?.text()?.trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseDate(dateText),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val raw = Regex(""""images":\[(.*?)\]""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            Regex(""""([^"]+)"""").findAll(raw)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.isNotBlank() }
                .mapIndexed { i, url -> Page(i, url, url) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

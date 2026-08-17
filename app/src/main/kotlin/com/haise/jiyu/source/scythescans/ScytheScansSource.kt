package com.haise.jiyu.source.scythescans

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * scythescans.com - genuine (nezmeneny) MangaThemesia WordPress motiv, identicka
 * struktura jako ThunderscansSource (bsx karty, imptdt info-karty, chapterlist,
 * ts_reader.run JS blob se strankami) - jen jina domena a archiv na "/manga/"
 * misto "/comics/". Overeno zive (PowerShell), search (?s=) funguje primo na
 * hlavni domene.
 */
@Singleton
class ScytheScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "scythescans"
    override val name = "Scythe Scans"
    override val homepageUrl get() = base
    private val base = "https://scythescans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.bsx").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.attr("title").ifBlank { link.text() }.trim().ifBlank { return@mapNotNull null }
            val cover = link.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val orderby = when (filter.sortBy) {
                "latest" -> "update"
                "title"  -> "title"
                else     -> "popular"
            }
            val url = if (page <= 1) "$base/manga/?order=$orderby" else "$base/manga/page/$page/?order=$orderby"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    /** Info karty ("Type", "Status", "Author") maji tvar: <div class="imptdt"><h1>Label</h1> <i>Hodnota</i></div>. */
    private fun infoCard(doc: Document, label: String): String? =
        doc.select("div.imptdt").firstOrNull { it.selectFirst("h1")?.text()?.trim().equals(label, ignoreCase = true) }
            ?.selectFirst("i")?.text()?.trim()?.ifBlank { null }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manhwa" -> "MANHWA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANGA"
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("div.thumb img")?.attr("src")?.trim()?.ifBlank { null } ?: manga.coverUrl,
                description = doc.selectFirst("div.entry-content.entry-content-single")?.text()?.trim(),
                genres = doc.select("span.mgen a").map { it.text().trim() }.filter { it.isNotBlank() },
                author = infoCard(doc, "Author"),
                artist = infoCard(doc, "Artist"),
                status = infoCard(doc, "Status")?.lowercase(),
                contentType = normalizeContentType(infoCard(doc, "Type")),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("#chapterlist li[data-num]").mapNotNull { li ->
                val link = li.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val num = li.attr("data-num").toFloatOrNull() ?: return@mapNotNull null
                val name = link.selectFirst("span.chapternum")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                    ?: "Chapter $num"
                val dateText = link.selectFirst("span.chapterdate")?.text()?.trim()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = num, dateUpload = parseRelativeOrAbsoluteDate(dateText))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseRelativeOrAbsoluteDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val relativeMatch = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(text)
        if (relativeMatch != null) {
            val value = relativeMatch.groupValues[1].toLongOrNull() ?: 1L
            val unit = relativeMatch.groupValues[2].lowercase()
            val deltaMs = when (unit) {
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
        return try {
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val json = Regex("""ts_reader\.run\((\{.*?})\);""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val sources = JSONObject(json).optJSONArray("sources") ?: return@withContext emptyList()
            val images = sources.optJSONObject(0)?.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).map { i ->
                val url = images.getString(i)
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

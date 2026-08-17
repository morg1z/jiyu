package com.haise.jiyu.source.evascans

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * evascans.org - MangaThemesia (WordPress) motiv jako ThunderscansSource, ale silně
 * přeskinovaný ("premium" luxury téma) - vychozi Madara/MangaThemesia selektory (bsx,
 * imptdt) na tomto webu buď chybí, nebo se nepoužívají konzistentně napříč typy stránek:
 *
 * - archiv (/series/page/N/) i výsledky hledání (/?s=...) používají stejnou kartu
 *   `div/article.manga-card-v` s `h3.card-v-title a` (titulek+odkaz) a `img.wp-post-image`
 *   (obálka) - ověřeno živě, "bsx" třída existuje jen ve starém CSS, v markupu se nepoužívá.
 * - detail mangy NEPOUŽÍVÁ `div.imptdt` (na rozdíl od Thunderscans) - Type/Status/Rating/
 *   Views jsou v `div.stat-v-box` dvojicích (`span.stat-v-label` + `span.stat-v-value`).
 * - seznam kapitol je ale identický s Thunderscans (`#chapterlist li[data-num]`,
 *   `span.chapternum`, `span.chapterdate`).
 * - stránky kapitoly NEJSOU v JS blobu (`ts_reader.run`) jako u Thunderscans, ale rovnou
 *   server-rendered `<img class="legendary-page">` se skutečnou `src` (ne data-src trik).
 */
@Singleton
class EvaScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "evascans"
    override val name = "Eva Scans"
    override val homepageUrl get() = base
    private val base = "https://evascans.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".manga-card-v").mapNotNull { card ->
            val titleLink = card.selectFirst("h3.card-v-title a") ?: return@mapNotNull null
            val title = titleLink.text().trim().ifBlank { return@mapNotNull null }
            val href = titleLink.attr("href").ifBlank { return@mapNotNull null }
            val cover = card.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/series/" else "$base/series/page/$page/"
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

    /** Dvojice "Label" / "Hodnota" v `div.stat-v-box` (Rating, Type, Status, Views). */
    private fun statValue(doc: Document, label: String): String? =
        doc.select("div.stat-v-box").firstOrNull {
            it.selectFirst("span.stat-v-label")?.text()?.trim().equals(label, ignoreCase = true)
        }?.selectFirst("span.stat-v-value")?.text()?.trim()?.ifBlank { null }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manhwa" -> "MANHWA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANGA"
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            // Kratsi "synopsis-short" je vzdy viditelny, delsi "synopsis-full" je v markupu
            // schovany za "Cist vice" (display:none) - kdyz existuje, ma prednost.
            val description = doc.selectFirst("div.synopsis-full")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("div.synopsis-short")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("div.entry-content")?.text()?.trim()?.ifBlank { null }
            manga.copy(
                title = doc.selectFirst("h1.series-title-main")?.text()?.trim() ?: manga.title,
                description = description,
                genres = doc.select("a.gen-tag").map { it.text().trim() }.filter { it.isNotBlank() },
                status = statValue(doc, "Status")?.lowercase(),
                contentType = normalizeContentType(statValue(doc, "Type")),
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
            val doc = Jsoup.parse(get(chapter.url))
            doc.select("img.legendary-page").mapIndexedNotNull { i, img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }.trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (_: Exception) { emptyList() }
    }
}

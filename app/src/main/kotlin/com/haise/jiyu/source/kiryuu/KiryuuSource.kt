package com.haise.jiyu.source.kiryuu

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kiryuu (indonéský portál manga/manhwa/manhua, "5000+ judul"). Domény kiryuu.id/.co/.io
 * jsou jen anti-adblock "brána" (stejny obsah na vsech - jen JS tlacitko pro pokracovani,
 * zadny skutecny obsah ve staticky stazenem HTML) s odkazem na aktualni "zrcadlo" pres
 * `/domain` stranku - overeno zive, aktualni je `v7.kiryuu.to` (WordPress, vlastni motiv,
 * NE Madara - "wp-manga"/"page-item-detail" chybi). Doména se muze v budoucnu zmenit na
 * v8/v9 apod. (typicky vzorec pro indonesky manga web pod tlakem blokovani).
 *
 * Detail mangy je cisty `<script type="application/ld+json">` typu `["Book","ComicSeries"]`
 * (Yoast) - normalni nezanorene JSON, zadne RSC escapovani. Seznam kapitol je
 * `div#chapter-list div[data-chapter-number] > a[href]` (cislo primo v atributu).
 * Stranky kapitoly jsou na `yuucdn.com/wp-content/uploads/imgsc/...` (obrazkovy CDN),
 * nazvy souboru nejsou zero-padded ("1.jpg", "10.jpg", ...) - potreba numericke
 * (ne textove) razeni.
 */
@Singleton
class KiryuuSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "kiryuu"
    override val name = "Kiryuu"
    override val homepageUrl get() = base
    private val base = "https://v7.kiryuu.to"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("#search-results a[href*=/manga/]").mapNotNull { a ->
            val img = a.selectFirst("img.wp-post-image") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
            val cover = img.attr("src").trim().ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/manga/" else "$base/manga/page/$page/"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/manga/?search_term=$q" else "$base/manga/page/$page/?search_term=$q"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    private fun ldJsonHasType(o: JSONObject, type: String): Boolean = when (val t = o.opt("@type")) {
        is String -> t == type
        is JSONArray -> (0 until t.length()).any { t.optString(it) == type }
        else -> false
    }

    private fun extractLdJsonBlocks(html: String): List<String> {
        val marker = "application/ld+json"
        val result = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val mi = html.indexOf(marker, searchFrom)
            if (mi < 0) break
            val s = html.indexOf('>', mi) + 1
            val e = html.indexOf("</script>", s)
            if (s <= 0 || e < 0) break
            result += html.substring(s, e)
            searchFrom = e + 1
        }
        return result
    }

    private fun normalizeStatus(status: String?): String? = when (status?.trim()?.lowercase()) {
        "ongoing" -> "ongoing"
        "completed" -> "completed"
        "hiatus" -> "hiatus"
        "cancelled", "canceled" -> "cancelled"
        else -> status
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val series = extractLdJsonBlocks(html)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { ldJsonHasType(it, "ComicSeries") } ?: return@withContext manga
            val genres = series.optJSONArray("genre")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?.ifEmpty { null } ?: manga.genres
            val description = series.optString("description").ifBlank { null }
                ?.let { Parser.unescapeEntities(it, false) }
            manga.copy(
                title = series.optString("name").ifBlank { manga.title },
                description = description,
                genres = genres,
                author = series.optJSONObject("author")?.optString("name")?.ifBlank { null } ?: manga.author,
                status = normalizeStatus(series.optString("creativeWorkStatus").ifBlank { null }) ?: manga.status,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            doc.select("div#chapter-list div[data-chapter-number] > a[href]").mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                val num = a.parent()?.attr("data-chapter-number")?.toFloatOrNull() ?: return@mapNotNull null
                val name = a.selectFirst("span")?.text()?.trim()?.ifBlank { null } ?: "Chapter $num"
                val dateText = a.selectFirst("time")?.attr("datetime")?.trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseIsoDate(dateText),
                )
            }.distinctBy { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseIsoDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try { OffsetDateTime.parse(text).toInstant().toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
    }

    private val pageImageRegex = Regex("""https://yuucdn\.com/[^"'\s]+/(\d+)\.(?:jpg|jpeg|png|webp)""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            pageImageRegex.findAll(html)
                .map { it.value to (it.groupValues[1].toIntOrNull() ?: 0) }
                .distinctBy { it.first }
                .sortedBy { it.second }
                .mapIndexed { i, (url, _) -> Page(i, url, url) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

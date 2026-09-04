package com.haise.jiyu.source.manga18club

import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.source.FilterTag
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
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * manga18.club - vlastni PHP sablona (NENI Madara). Vypis "Manhwa Update"
 * (pouzity pro getPopular) je strankovany klasicky přes `/latest-release/{page}`
 * a hledani ma cisty JSON endpoint `/search?search=...`. Nejneobvyklejsi cast
 * je samotna ctecka kapitoly: stranky NEJSOU v HTML jako <img> tagy, ale jako
 * pole base64-zakodovanych URL v inline <script> (`var slides_p_path = [...]`),
 * ktere si web na klientovi postupne dekoduje a vklada do DOM po jedne (kvuli
 * "ochrane" pred hotlinkovanim/scrapovanim) - getPageList proto misto Jsoup
 * selektoru pouziva regex primo na syrove HTML telo a rucni Base64 dekodovani.
 */
@Singleton
class Manga18ClubSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "manga18club"
    override val name = "Manga18.club"
    override val homepageUrl get() = base
    override val isAdult = true
    override val contentType = "MANHWA"

    private val base = "https://manga18.club"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("div.story_item").mapNotNull { item ->
            val link = item.selectFirst(".mg_info .mg_name a")
                ?: item.selectFirst("a[href*=/manhwa/]")
                ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val img = item.selectFirst(".story_images img")
            val cover = img?.attr("data-src")?.trim()?.ifBlank { img.attr("src").trim() }?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = contentType)
        }.distinctBy { it.url }

    // Zanry jsou vypsane v hlavnim menu ("Genres" rozbalovaci sub-menu na homepage)
    // jako odkazy na `/manga-list/{slug}` - stejna archivni stranka jako karta
    // "Categories" v detailu mangy. Overeno zive: /manga-list/romance vraci jina
    // data nez /manga-list/romance/2 (druha stranka) - genuinne filtrovany vypis.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get(base), base)
            val tags = doc.select("div.sub-menu a[href*=manga-list/]").mapNotNull { a ->
                val slug = a.absUrl("href").trimEnd('/').substringAfterLast("/manga-list/").ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun genreUrl(slug: String, page: Int) =
        if (page <= 1) "$base/manga-list/$slug" else "$base/manga-list/$slug/$page"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Genre archiv nema vlastni razeni v getPopular a nekombinuje vice zanru -
            // pouzije se jen prvni vybrany tag (stejny vzor jako HadesScans/Madara).
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page)), base))
            }
            parseListing(Jsoup.parse(get("$base/latest-release/$page"), base))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page)), base))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(get("$base/search?search=$q"))
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug").ifBlank { return@mapNotNull null }
                val title = o.optString("name").ifBlank { return@mapNotNull null }
                SManga(
                    sourceId = id,
                    url = "$base/manhwa/$slug",
                    title = title,
                    coverUrl = o.optString("cover_url").takeIf { it.isNotBlank() },
                    contentType = contentType,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ─── Detail mangy ────────────────────────────────────────────────────────

    private fun infoItem(doc: Document, label: String) =
        doc.select(".detail_listInfo .item").firstOrNull {
            it.selectFirst(".info_label")?.text()?.trim().equals(label, ignoreCase = true)
        }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            val title = doc.selectFirst(".detail_name h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val cover = doc.selectFirst(".detail_avatar img")?.attr("src")?.trim()?.ifBlank { null } ?: manga.coverUrl
            val description = doc.selectFirst(".detail_reviewContent")?.text()?.trim()?.ifBlank { null }
            val status = infoItem(doc, "Status")?.selectFirst(".info_value")?.text()?.trim()?.ifBlank { null }
            val author = infoItem(doc, "Author")?.selectFirst(".info_value")?.text()?.trim()?.ifBlank { null }
            val artist = infoItem(doc, "Artist")?.selectFirst(".info_value")?.text()?.trim()?.ifBlank { null }
            val genres = infoItem(doc, "Categories")?.select(".info_value a")?.map { it.text().trim() }.orEmpty()
            manga.copy(
                title = title, coverUrl = cover, description = description,
                status = status, author = author, artist = artist, genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            doc.select(".chapter_box li .item").mapNotNull { item ->
                val link = item.selectFirst("a.chapter_num") ?: return@mapNotNull null
                val url = link.absUrl("href").ifBlank { return@mapNotNull null }
                val name = link.text().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                val dateText = item.select("p.chapter_info").firstOrNull()?.text()?.trim()
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = url, name = name,
                    chapterNumber = num, dateUpload = parseDate(dateText),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Datum kapitoly je ve formatu "07-08-2026" (DD-MM-YYYY). */
    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val arrayBody = Regex("""var slides_p_path\s*=\s*\[(.*?)]\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            Regex("\"([^\"]*)\"").findAll(arrayBody)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .mapIndexedNotNull { i, b64 ->
                    val decoded = try {
                        String(Base64.getDecoder().decode(b64))
                    } catch (_: Exception) { null }
                    decoded?.takeIf { it.startsWith("http") }?.let { Page(i, it, it) }
                }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

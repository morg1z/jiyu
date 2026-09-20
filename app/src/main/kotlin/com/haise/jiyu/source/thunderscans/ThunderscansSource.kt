package com.haise.jiyu.source.thunderscans

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.normalizeContentType
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
 * Puvodni domena thunderscans.com je K DATU PSANI HIJACKNUTA - misto obsahu prekladatelske
 * skupiny servíruje JS-fingerprinting redirect na cizi domenu (canvas/webdriver detekce,
 * presmerovani na "hagne-puo.com/zokredirect") - overeno zive (PowerShell Invoke-WebRequest,
 * curl na tuhle domenu appka NEPOUZIVA. Skutecny/anglicky mirror skupiny je en-thunderscans.com
 * (WordPress "mangareader"/MangaThemesia tema - jine nez Madara, proto vlastni trida misto
 * MadaraSource).
 *
 * Seznam kapitol je server-rendered primo v detailu mangy (#chapterlist li[data-num]) - zadny
 * AJAX navic. Obrazky stranek NEJSOU v HTML (#readerarea je prazdne), ale v JS blobu
 * `ts_reader.run({...})` na strance kapitoly - parsovano jako JSON, ne markup.
 */
@Singleton
class ThunderscansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "thunderscans"
    override val name = "Thunderscans"
    override val homepageUrl get() = base
    override val supportsChapterComments: Boolean get() = true
    private val base = "https://en-thunderscans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
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

    // Archivni stranka "/comics/" ma sidebar s genre[] checkboxy (standardni MangaThemesia
    // "genrez" panel, ciselne id) - stejny endpoint prijima "?genre[]=<id>" jako extra filtr,
    // overeno zive (17 vs 30 titulu pro genre "Academy" vs bez filtru).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/comics/"))
            val tags = doc.select("input.genre-item[name=genre[]]").mapNotNull { input ->
                val value = input.attr("value").ifBlank { null } ?: return@mapNotNull null
                val label = doc.selectFirst("label[for=${input.attr("id")}]")?.text()?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null
                FilterTag(id = value, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun archiveUrl(page: Int, orderby: String, genreId: String?): String {
        val base0 = if (page <= 1) "$base/comics/?order=$orderby" else "$base/comics/page/$page/?order=$orderby"
        return if (genreId != null) "$base0&genre%5B%5D=$genreId" else base0
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val orderby = when (filter.sortBy) {
                "latest" -> "update"
                "title"  -> "title"
                else     -> "popular"
            }
            val url = archiveUrl(page, orderby, filter.genres.firstOrNull())
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(archiveUrl(page, "popular", filter.genres.first())))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Info karty ("Type", "Status", "Author") maji tvar: <div class="imptdt"><h1>Label</h1> <i>Hodnota</i></div>. */
    private fun infoCard(doc: Document, label: String): String? =
        doc.select("div.imptdt").firstOrNull { it.selectFirst("h1")?.text()?.trim().equals(label, ignoreCase = true) }
            ?.selectFirst("i")?.text()?.trim()?.ifBlank { null }

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
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("#chapterlist li[data-num]").mapNotNull { li ->
                val link = li.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val num = li.attr("data-num").toFloatOrNull() ?: return@mapNotNull null
                // "Chapter\n\t\t\t\t\t\t\t17" - whitespace/newline mezi "Chapter" a cislem z puvodniho markupu.
                val name = link.selectFirst("span.chapternum")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                    ?: "Chapter $num"
                val dateText = link.selectFirst("span.chapterdate")?.text()?.trim()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = num, dateUpload = parseRelativeOrAbsoluteDate(dateText))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseRelativeOrAbsoluteDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            // Obrazky nejsou v markupu (#readerarea je prazdne) - dodava je JS blob
            // `ts_reader.run({"sources":[{"images":[...]}], ...});` na strance kapitoly.
            val json = Regex("""ts_reader\.run\((\{.*?\})\);""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val sources = JSONObject(json).optJSONArray("sources") ?: return@withContext emptyList()
            val images = sources.optJSONObject(0)?.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).map { i ->
                val url = images.getString(i)
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                com.haise.jiyu.source.comments.parseWpDiscuzComments(Jsoup.parse(get(chapter.url)))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
}

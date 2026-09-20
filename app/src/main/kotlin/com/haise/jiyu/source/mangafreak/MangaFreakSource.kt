package com.haise.jiyu.source.mangafreak

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
import com.haise.jiyu.util.rethrowIfControl
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
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaFreakSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id   = "mangafreak"
    override val name = "MangaFreak"
    override val homepageUrl get() = base
    // mangafreak.net dnes jen přesměrovává (301) na tuhle doménu; cesty i značkování jsou od té doby jiné.
    private val base  = "https://ww3.mangafreak.me"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    /** "/manga_images/{slug malými}.jpg" je plnohodnotný obal (mini_images ve výpisech mají jen 55-100 px). */
    private fun coverForPath(path: String): String =
        "https://images.mangafreak.me/manga_images/${path.substringAfterLast('/').lowercase()}.jpg"

    private fun mangaPath(href: String): String = if (href.startsWith(base)) toSourcePath(base, href) else href

    /** "/Mangalist/All/{page}" - abecední seznam všeho (web nemá řazení podle popularity). */
    internal fun parseMangaList(html: String): List<SManga> =
        Jsoup.parse(html, base).select("div.list_item").mapNotNull { item ->
            val link = item.selectFirst(".list_item_info h3 a[href^=/Manga/]") ?: return@mapNotNull null
            val path = mangaPath(link.attr("href"))
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            SManga(sourceId = id, url = path, title = title, coverUrl = coverForPath(path))
        }

    /** "/Latest_Releases/{page}". */
    internal fun parseLatestList(html: String): List<SManga> =
        Jsoup.parse(html, base).select("div.latest_releases_item").mapNotNull { item ->
            val link = item.selectFirst(".latest_releases_info a[href^=/Manga/]") ?: return@mapNotNull null
            val path = mangaPath(link.attr("href"))
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            SManga(sourceId = id, url = path, title = title, coverUrl = coverForPath(path))
        }

    /** "/Find/{dotaz}?page=N" - výsledky hledání. */
    internal fun parseSearchList(html: String): List<SManga> =
        Jsoup.parse(html, base).select("div.manga_search_item").mapNotNull { item ->
            val link = item.selectFirst("h3 a[href^=/Manga/]") ?: return@mapNotNull null
            val path = mangaPath(link.attr("href"))
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val cover = item.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null } ?: coverForPath(path)
            SManga(sourceId = id, url = path, title = title, coverUrl = cover)
        }

    // Genre archiv ("/Genre/{slug}/{page}") ma jinou strukturu karty ("div.ranking_item")
    // nez popular/search vypis ("manga_poster" atd.) - overeno zive.
    private fun parseGenreList(html: String): List<SManga> =
        Jsoup.parse(html, base).select("div.ranking_item").mapNotNull { item ->
            val link = item.selectFirst("a[href^=/Manga/]") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.selectFirst("h3.title")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val cover = item.selectFirst(".ranking_item_image img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }

    override val supportsTagFilter: Boolean get() = true

    // "/Genre" vypisuje kompletni seznam zanru (~55 polozek) v "div.genre_list a" -
    // staticky seznam, staci nacist jednou a sdilet mezi vsemi otevrenimi Filtry.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/Genre"), base)
            val tags = doc.select("div.genre_list a[href^=/Genre/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/Genre/").trim().ifBlank { null } ?: return@mapNotNull null
                if (slug.equals("All", ignoreCase = true)) return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreList(get("$base/Genre/${filter.genres.first()}/$page"))
            }
            if (filter.sortBy == "latest") {
                parseLatestList(get("$base/Latest_Releases${if (page > 1) "/$page" else ""}"))
            } else {
                parseMangaList(get("$base/Mangalist/All/$page"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreList(get("$base/Genre/${filter.genres.first()}/$page"))
            }
            // Web hledá přes "/Find/{dotaz malými písmeny}"; mezera v CESTĚ je %20 (URLEncoder dává "+",
            // které by se v cestě četlo jako doslovné plus). Stránkuje se query parametrem ?page=N.
            val q = URLEncoder.encode(query.trim().lowercase(), "UTF-8").replace("+", "%20")
            parseSearchList(get("$base/Find/$q?page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            parseDetails(Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base), manga)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    internal fun parseDetails(doc: org.jsoup.nodes.Document, manga: SManga): SManga {
        val info = doc.select(".manga_series_data > div")
        fun field(label: String): String? = info.firstOrNull { it.ownText().trim().startsWith(label, ignoreCase = true) }
            ?.ownText()?.substringAfter(':')?.trim()?.ifBlank { null }
        val statusLine = info.firstOrNull { it.ownText().contains("series", ignoreCase = true) }?.ownText().orEmpty()
        return manga.copy(
            title       = doc.selectFirst(".manga_series_data h1")?.text()?.trim()?.ifBlank { null } ?: manga.title,
            coverUrl    = doc.selectFirst(".manga_series_image img")?.attr("src")?.trim()?.ifBlank { null } ?: manga.coverUrl,
            description = doc.selectFirst(".manga_series_description p")?.text()?.trim()?.ifBlank { null },
            genres      = doc.select(".series_sub_genre_list a").map { it.text().trim() }.filter { it.isNotBlank() },
            author      = field("Written By"),
            artist      = field("Illustrated By"),
            status      = when {
                statusLine.contains("ON-GOING", ignoreCase = true) -> "ongoing"
                statusLine.contains("COMPLETE", ignoreCase = true) -> "completed"
                else -> null
            },
        )
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            parseChapters(Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base), manga)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    internal fun parseChapters(doc: org.jsoup.nodes.Document, manga: SManga): List<SChapter> {
        val rows = doc.select(".manga_series_list tr:has(a.chapter-link)")
        return rows.mapIndexedNotNull { i, row ->
            val a = row.selectFirst("a.chapter-link") ?: return@mapIndexedNotNull null
            val href = mangaPath(a.attr("href")).ifBlank { return@mapIndexedNotNull null }
            val name = a.text().trim().ifBlank { "Chapter ${i + 1}" }
            SChapter(
                sourceId      = id,
                mangaUrl      = manga.url,
                url           = href,
                name          = name,
                // Číslo z názvu ("Chapter 12 - ..."); tabulka je vzestupně (nejstarší první), takže
                // pořadí řádku je jen krajní záloha.
                chapterNumber = parseChapterNumber(name) ?: (i + 1).toFloat(),
                dateUpload    = parseDate(row.select("td").getOrNull(1)?.text()),
            )
        }
    }

    /** Web uvádí datum jako "2009/07/06". */
    private fun parseDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            parsePages(Jsoup.parse(get(resolveSourceUrl(base, chapter.url)), base))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    internal fun parsePages(doc: org.jsoup.nodes.Document): List<Page> =
        doc.select("img#gohere, .mySlides img").mapNotNull { img ->
            img.absUrl("src").ifBlank { null } ?: img.attr("data-src").let { absoluteMediaUrl(base, it) }
        }.distinct().mapIndexed { i, url -> Page(i, url, url) }
}

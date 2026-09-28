package com.haise.jiyu.source.ranobescom

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.PageBatch
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.fetchPagesBatched
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.novelText
import com.haise.jiyu.util.parseChapterDate
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

/**
 * Zdroj pro ranobes.com (DLE engine, jiný markup než ranobes.net - žádný `__DATA__`).
 * Archiv `/ranobe/page/{N}/` s kartami `article.story`, kapitoly na `/chapters/{slug}/`
 * (slug = slug novely bez číselného id prefixu), text kapitoly `#arrticle`.
 * Žánry `/cloud/genre/{název}/` (cyrilice), search POST `index.php?do=search`.
 */
class RanobesComSource(
    override val id: String = "ext:ranobescom",
    override val name: String = "Ranobes.com",
    private val baseUrl: String = "https://ranobes.com",
    private val client: OkHttpClient,
    private val inGlobalSearch: Boolean = false,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')

    override val contentType = "NOVEL"
    override val homepageUrl get() = baseUrl
    override val language get() = "ru"
    override val includeInGlobalSearch get() = inGlobalSearch
    override val supportsTagFilter get() = true
    override val supportsSortOrder get() = false

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("article.story").mapNotNull { item ->
            val link = item.selectFirst("h2.title a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            // Cover je jako background-image na figure.cover
            val styleCover = item.selectFirst("figure.cover")?.attr("style")
                ?.substringAfter("url(", "")?.substringBefore(")")?.trim('"', '\'', ' ')
            SManga(
                sourceId = id,
                url = href,
                title = link.text().trim(),
                coverUrl = styleCover?.takeIf { it.isNotBlank() }?.let { resolveSourceUrl(baseUrl, it) },
                contentType = "NOVEL",
            )
        }.distinctBy { it.url }

    private fun genreUrl(name: String, page: Int): String {
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "$root/cloud/genre/$enc/" + if (page > 1) "page/$page/" else ""
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (filter.genres.isNotEmpty()) genreUrl(filter.genres.first(), page)
                else "$root/ranobe/" + if (page > 1) "page/$page/" else ""
            parseListing(Jsoup.parse(get(url)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            // DLE fulltext search - POST index.php?do=search
            val body = FormBody.Builder()
                .add("do", "search").add("subaction", "search")
                .add("story", query)
                .add("search_start", ((page - 1).coerceAtLeast(0)).toString())
                .add("full_search", "0").add("result_from", "1")
                .build()
            val req = Request.Builder().url("$root/index.php?do=search").post(body)
                .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                .build()
            val html = client.newCall(req).execute().use { it.bodyOrThrow("$root/index.php?do=search") }
            parseListing(Jsoup.parse(html))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            // Katalog žánrů /cloud/genre/ - odkazy /cloud/genre/{název}/ (id = název pro URL).
            val doc = Jsoup.parse(get("$root/cloud/genre/"))
            val tags = doc.select("a[href*='/cloud/genre/']").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/cloud/genre/").trim('/')
                    .ifBlank { null } ?: return@mapNotNull null
                // Nazev zanru je v ".title" - a.text() by tahal i popisek
                // v "span.small" a label by byl nekolik vet (audit).
                val label = a.selectFirst(".title, h3, h5")?.text()?.trim()?.ifBlank { null }
                    ?: a.text().trim().ifBlank { slug }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }.sortedBy { it.label }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Slug novely z URL titulu - "/ranobe/640842-sevens.html" → "sevens". */
    private fun titleSlug(mangaUrl: String): String? =
        Regex("/ranobe/\\d+-([a-z0-9-]+)\\.html").find(mangaUrl)?.groupValues?.get(1)

    /**
     * Slug kapitoloveho archivu. Web slugy usekava na ~48 znaku, takze
     * "/chapters/{plny-slug}/" pro dlouhe nazvy vraci 404 (audit: 0 kapitol).
     * Spravny (zkraceny) slug vytahneme z odkazu na kapitoly na strance titulu;
     * sidebar odkazy na jine tituly se odfiltruji prefixovou shodou.
     */
    private fun chaptersSlug(doc: Document, titleSlug: String): String =
        doc.select("a[href*='/chapters/']")
            .mapNotNull { Regex("/chapters/([^/]+)/\\d+").find(it.attr("href"))?.groupValues?.get(1) }
            .firstOrNull { titleSlug.startsWith(it) || it.startsWith(titleSlug) }
            ?: titleSlug

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            manga.copy(
                title = doc.selectFirst("h1.title")?.ownText()?.trim() ?: manga.title,
                description = doc.selectFirst(".r-fullstory .moreless, .r-fullstory-content, .fullstory")
                    ?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:description']")?.attr("content"),
                status = doc.selectFirst("a[href*='/cloud/status-trs/']")?.text()?.trim(),
                year = doc.selectFirst("a[href*='/cloud/year/']")?.text()?.trim()?.toIntOrNull(),
                coverUrl = doc.selectFirst(".r-fullstory figure.cover, figure.cover")?.attr("style")
                    ?.substringAfter("url(", "")?.substringBefore(")")?.trim('"', '\'', ' ')
                    ?.takeIf { it.isNotBlank() }?.let { resolveSourceUrl(baseUrl, it) } ?: manga.coverUrl,
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val mangaAbsUrl = resolveSourceUrl(baseUrl, manga.url)
            val titleSlug = titleSlug(mangaAbsUrl) ?: return@withContext emptyList()
            val slug = chaptersSlug(Jsoup.parse(get(mangaAbsUrl)), titleSlug)
            // Další stránka existuje, jen když paginace ukazuje na page/(page+1);
            // bezpečnostní limit 60. Dávky souběžně, vyhodnocení v pořadí stránek.
            val chapters = fetchPagesBatched(maxPages = 60) { page ->
                val url = "$root/chapters/$slug/" + if (page > 1) "page/$page/" else ""
                val doc = Jsoup.parse(get(url))
                val items = doc.select(".cat_block.cat_line a, .cat_line a").mapNotNull { a ->
                    val href = a.attr("href").ifBlank { return@mapNotNull null }
                    val title = a.selectFirst(".title")?.text()?.trim()
                        ?: a.attr("title").trim().ifBlank { a.text().trim() }
                    Triple(
                        href,
                        title,
                        parseChapterDate(
                            a.selectFirst("small")?.text(),
                            Locale.forLanguageTag("ru"),
                        ),
                    )
                }
                PageBatch(
                    items,
                    isLast = items.isEmpty() || doc.select("a[href*='/chapters/$slug/page/${page + 1}']").isEmpty(),
                )
            }
            // Fallback čísla/názvu závisí na pozici v celém seznamu - přečísluje se
            // až po složení všech dávek v původním pořadí.
            chapters.mapIndexed { i, (href, title, date) ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = title.ifBlank { "Глава ${i + 1}" },
                    // "Том 1. Глава 2" - první číslo je svazek, číslo kapitoly je poslední.
                    chapterNumber = Regex("[\\d.,]+").findAll(title).lastOrNull()?.value
                        ?.replace(',', '.')?.toFloatOrNull()
                        ?: (i + 1).toFloat(),
                    dateUpload = date,
                )
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, chapter.url)))
            val content = doc.selectFirst("#arrticle") ?: return@withContext emptyList()
            content.select("script, style, iframe, ins, .pc-adv, .adsbygoogle, form").remove()
            val text = content.novelText()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

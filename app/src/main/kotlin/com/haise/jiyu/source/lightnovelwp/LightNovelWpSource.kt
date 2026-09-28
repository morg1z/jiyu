package com.haise.jiyu.source.lightnovelwp

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.novelText
import com.haise.jiyu.util.parseChapterDate
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

/**
 * Generický zdroj pro novelové weby na "LightNovelWP" / WP-manga šablonách
 * (kolnovel.com, namevt.com, kdtnovels.net …) - archiv `/{archivePath}/` s kartami
 * `article.maindet`/`.bsx`, filtrování `?genre[]={slug}`, řazení `?order=` a seznam
 * kapitol v `.eplister` (položky `.epl-num`/`.epl-title`/`.epl-date`). Text kapitoly
 * je v `.epcontent` / `.entry-content`.
 */
class LightNovelWpSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val archivePath: String = "series",
    private val languageOverride: String = "en",
    private val isAdultOverride: Boolean = false,
    private val dateLocale: Locale = Locale.ENGLISH,
    private val inGlobalSearch: Boolean = false,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')

    override val contentType = "NOVEL"
    override val homepageUrl get() = baseUrl
    override val language get() = languageOverride
    override val isAdult get() = isAdultOverride
    override val includeInGlobalSearch get() = inGlobalSearch
    override val supportsTagFilter get() = true
    override val availableSorts get() = setOf("popular", "latest", "title")

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("article.maindet, div.bsx, div.listupd article").mapNotNull { item ->
            val link = item.selectFirst("h2 a, h3 a, h4 a, a.tip") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = href,
                title = link.text().trim().ifBlank { link.attr("title").trim() },
                coverUrl = item.selectFirst("img")?.lazySrc()?.let { resolveSourceUrl(baseUrl, it) },
                contentType = "NOVEL",
            )
        }.distinctBy { it.url }

    private fun archiveUrl(page: Int, filter: MangaFilter): String {
        val sb = StringBuilder("$root/$archivePath/?")
        filter.genres.forEach { sb.append("genre[]=").append(URLEncoder.encode(it, "UTF-8")).append('&') }
        val order = when (filter.sortBy) {
            "latest" -> "update"
            "title" -> "title"
            else -> "popular"
        }
        sb.append("order=").append(order)
        if (page > 1) sb.append("&page=").append(page)
        return sb.toString()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseListing(Jsoup.parse(get(archiveUrl(page, filter)))) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(archiveUrl(page, filter))))
            }
            val url = "$root/?s=${URLEncoder.encode(query, "UTF-8")}" + if (page > 1) "&paged=$page" else ""
            parseListing(Jsoup.parse(get(url)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            // Archivní stránka má filtrovací formulář s checkboxy input[name='genre[]']
            // (value = slug, popisek v labelu / vedlejším textu).
            val doc = Jsoup.parse(get("$root/$archivePath/"))
            val fromInputs = doc.select("input[name='genre[]']").mapNotNull { input ->
                // Nektere weby (kolnovel) maji ve value uz percent-encoded slug -
                // dekodujeme, jinak by archiveUrl slug znovu zakodoval na %25.
                // Pro plain ascii slugy je decode no-op.
                val slug = java.net.URLDecoder.decode(input.attr("value").trim(), "UTF-8")
                    .ifBlank { null } ?: return@mapNotNull null
                val inputId = input.attr("id")
                // Popisek: label[for=id], pak text bezprostředního rodiče (jen malý kontejner),
                // jinak humanizovaný slug.
                val label = (if (inputId.isNotBlank()) doc.selectFirst("label[for=$inputId]") else null)
                    ?.text()?.trim()?.ifBlank { null }
                    ?: input.parent()?.takeIf { it.tagName() in setOf("label", "span", "li") }
                        ?.text()?.trim()?.ifBlank { null }
                    ?: slug.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
                FilterTag(id = slug, label = label)
            }
            val tags = (fromInputs.ifEmpty {
                doc.select("a[href*='genre[]=']").mapNotNull { a ->
                    val slug = java.net.URLDecoder.decode(
                        a.attr("href").substringAfter("genre[]=", "").substringBefore('&').trim(), "UTF-8",
                    ).ifBlank { null } ?: return@mapNotNull null
                    FilterTag(id = slug, label = a.text().trim().ifBlank { slug })
                }
            }).distinctBy { it.id }.sortedBy { it.label }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            manga.copy(
                description = doc.selectFirst("div[itemprop='description']")?.text()?.trim()
                    ?: doc.selectFirst(".entry-content")?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:description']")?.attr("content"),
                author = doc.selectFirst(".spe span:contains(Author), .serl span:contains(Author)")?.ownText()
                    ?: doc.selectFirst("[itemprop='author']")?.text()?.trim(),
                status = doc.selectFirst(".spe span:contains(Status), .serl span:contains(Status)")
                    ?.ownText()?.trim(),
                genres = doc.select(".sertogenre a, a[rel='tag']").map { it.text().trim() }
                    .filter { it.isNotBlank() }.distinct(),
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            doc.select(".eplister ul li a, .epcheck li a").mapIndexedNotNull { i, a ->
                val href = a.attr("href").ifBlank { return@mapIndexedNotNull null }
                val numText = a.selectFirst(".epl-num")?.text() ?: ""
                val titleText = a.selectFirst(".epl-title")?.text()?.trim()
                val dateText = a.selectFirst(".epl-date")?.text()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = titleText?.takeIf { it.isNotBlank() }
                        ?: a.text().trim().ifBlank { "Chapter ${i + 1}" },
                    chapterNumber = Regex("[\\d.,]+").find(numText)?.value?.replace(',', '.')?.toFloatOrNull()
                        ?: (i + 1).toFloat(),
                    dateUpload = parseChapterDate(dateText, dateLocale),
                )
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, chapter.url)))
            // Nektere weby (teamchman/dakesu tema) pouzivaji <article class="epcontent">,
            // takze selektor nesmi byt vazany na div.
            val content = doc.selectFirst(".epcontent")
                ?: doc.selectFirst(".entry-content")
                ?: return@withContext emptyList()
            content.select("script, style, iframe, ins, .pc-adv, .adsbygoogle, div[itemtype], .dakesu-ad-wrapper").remove()
            val text = content.novelText()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

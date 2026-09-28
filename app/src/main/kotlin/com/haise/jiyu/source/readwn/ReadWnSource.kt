package com.haise.jiyu.source.readwn

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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Generický zdroj pro weby na "ReadWN/FanMTL" enginu (fanmtl.com, wuxiaspot.com …):
 * archiv `/list/{genre}/all-{sort}-{page0}.html` (page je 0-based), karty `.novel-item`,
 * seznam kapitol `ol.chapter-list`, text kapitoly `.chapter-content`.
 */
class ReadWnSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val languageOverride: String = "en",
    private val isAdultOverride: Boolean = false,
    private val inGlobalSearch: Boolean = false,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')

    override val contentType = "NOVEL"
    override val homepageUrl get() = baseUrl
    override val language get() = languageOverride
    override val isAdult get() = isAdultOverride
    override val includeInGlobalSearch get() = inGlobalSearch
    override val supportsTagFilter get() = true
    override val availableSorts get() = setOf("popular", "latest")

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select(".novel-item").mapNotNull { item ->
            val link = item.selectFirst("a[href*='novel']") ?: item.selectFirst("a")
                ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = href,
                title = item.selectFirst(".novel-title, h4, h3")?.text()?.trim()
                    ?: link.attr("title").trim().ifBlank { link.text().trim() },
                coverUrl = item.selectFirst(".novel-cover img, img")?.lazySrc()
                    ?.let { resolveSourceUrl(baseUrl, it) },
                contentType = "NOVEL",
            )
        }.distinctBy { it.url }

    /** URL archivu - `{slug}` = žánr ("all" = vše), sort onclick=newest? ne: onclick=views. */
    private fun listUrl(genre: String, page: Int, filter: MangaFilter): String {
        val sort = if (filter.sortBy == "latest") "newstime" else "onclick"
        val page0 = (page - 1).coerceAtLeast(0)
        return "$root/list/$genre/all-$sort-$page0.html"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull() ?: "all"
            parseListing(Jsoup.parse(get(listUrl(genre, page, filter))))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(listUrl(filter.genres.first(), page, filter))))
            }
            // EmpireCMS search - POST form (GET /search.html?keyword= vrací jen JS shell).
            // Odpověď redirectuje na /e/search/result/?searchid=N s výsledky (.novel-item).
            val body = FormBody.Builder()
                .add("show", "title").add("tempid", "1").add("tbname", "news")
                .add("keyboard", query)
                .build()
            val req = Request.Builder().url("$root/e/search/index.php").post(body)
                .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                .build()
            val html = client.newCall(req).execute().use { it.bodyOrThrow("$root/e/search/index.php") }
            parseListing(Jsoup.parse(html))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            // Žánry visí jako odkazy /list/{slug}/... na homepage i stránce /browsetags/.
            val tags = listOf("$root/browsetags/", root).asSequence().flatMap { url ->
                try {
                    Jsoup.parse(get(url)).select("a[href^='/list/'], a[href*='$root/list/']").asSequence()
                } catch (e: Exception) { e.rethrowIfControl(); emptySequence() }
            }.mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/list/").substringBefore('/').trim()
                    .ifBlank { null } ?: return@mapNotNull null
                if (slug == "all") return@mapNotNull null
                FilterTag(id = slug, label = a.text().trim().ifBlank { slug })
            }.distinctBy { it.id }.sortedBy { it.label }.toList()
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            manga.copy(
                description = doc.selectFirst("div[itemprop='description']")?.text()?.trim()
                    ?: doc.selectFirst(".synopsis, .summary, #synopsis")?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:description']")?.attr("content"),
                author = doc.selectFirst("[itemprop='author'], .author a, span.author")?.text()?.trim(),
                status = doc.selectFirst(".novel-stats .status, .status")?.text()?.trim(),
                genres = doc.select("a[href*='/list/']").mapNotNull { a ->
                    val slug = a.attr("href").substringAfter("/list/").substringBefore('/').trim()
                    if (slug.isBlank() || slug == "all") null else a.text().trim()
                }.filter { it.isNotBlank() }.distinct(),
                coverUrl = doc.selectFirst(".novel-cover img, .cover img")?.lazySrc()
                    ?.let { resolveSourceUrl(baseUrl, it) } ?: manga.coverUrl,
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            doc.select("ol.chapter-list li a, ul.chapter-list li a").mapIndexedNotNull { i, a ->
                    val href = a.attr("href").ifBlank { return@mapIndexedNotNull null }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = a.selectFirst(".chapter-title")?.text()?.trim()
                        ?: a.text().trim().ifBlank { "Chapter ${i + 1}" },
                    chapterNumber = Regex("[\\d.,]+").find(a.text())?.value?.replace(',', '.')?.toFloatOrNull()
                        ?: (i + 1).toFloat(),
                    dateUpload = parseChapterDate(a.selectFirst(".chapter-update")?.text()),
                )
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, chapter.url)))
            val content = doc.selectFirst(".chapter-content") ?: return@withContext emptyList()
            content.select("script, style, iframe, ins, .adsbygoogle, .chapter-ads, div[align]").remove()
            val text = content.novelText()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

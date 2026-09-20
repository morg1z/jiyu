package com.haise.jiyu.source.weebcentral

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
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

/**
 * weebcentral.com je htmx/Alpine.js aplikace - vetsina obsahu se dotahuje
 * pres samostatne endpointy misto klasickeho server-rendered HTML na jedne
 * strance:
 *  - listing/vyhledavani: /search/data?...&text={query}&page={n} (vyzaduje
 *    kompletni sadu query parametru, jinak vraci 307 na /400)
 *  - kompletni seznam kapitol: /series/{id}/full-chapter-list (detailni
 *    stranka sama o sobe ukazuje jen zlomek)
 *  - stranky kapitoly: /chapters/{id}/images?is_prev=False&current_page=1&
 *    reading_style=long_strip (bez reading_style parametru vraci 400)
 */
@Singleton
class WeebCentralSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "weebcentral"
    override val name = "Weeb Central"
    override val homepageUrl get() = base
    private val base = "https://weebcentral.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.bg-base-300").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/series/]") ?: return@mapNotNull null
            val href = toSourcePath(base, link.attr("href"))
            val title = card.selectFirst("a.link-hover")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    private fun searchData(query: String, page: Int, sort: String, filter: MangaFilter): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val s = URLEncoder.encode(sort, "UTF-8")
        val tags = filter.genres.joinToString("") { "&included_tag=${URLEncoder.encode(it, "UTF-8")}" }
        return "$base/search/data?sort=$s&order=Descending&official=Any&anime=Any&adult=Any&text=$q&page=$page&display_mode=Full%20Display$tags"
    }

    // /search stranka (Advanced Search) obsahuje statickou sadu checkboxu "Tags" -
    // skryte <input id="tag-{Name}-value" value="{Name}"> pro kazdy zanr, ktere se
    // pri behu appky nemeni. Overeno zive: parametr `included_tag` na /search/data
    // skutecne filtruje (a jde kombinovat opakovanim pro vice zanru najednou).
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/search"))
            val tags = doc.select("input[type=hidden][id^=tag-][id$=-value]").mapNotNull { input ->
                val name = input.attr("value").trim().ifBlank { return@mapNotNull null }
                FilterTag(id = name, label = name)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // "Latest Updates" overeno zive - vraci jiny (a spravny) poradek nez "Popularity".
        // Pozor: "Latest" samotne (bez "Updates") vraci 307 presmerovani na chybovou
        // stranku - API prijima jen presne tenhle text.
        val sort = if (filter.sortBy == "latest") "Latest Updates" else "Popularity"
        try { parseList(get(searchData("", page, sort, filter))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try { parseList(get(searchData(query, page, "Best Match", filter))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: manga.coverUrl,
                description = doc.selectFirst("p.whitespace-pre-wrap")?.text()?.trim(),
                genres = doc.select("a[href*=included_tag=]").map { it.text().trim() }.filter { it.isNotBlank() },
                author = doc.selectFirst("a[href*=\"search?author=\"]")?.text()?.trim(),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val id2 = manga.url.substringAfter("/series/").substringBefore("/")
            val doc = Jsoup.parse(get("$base/series/$id2/full-chapter-list"))
            val chapters = doc.select("a[href*=/chapters/]")
            chapters.mapIndexed { i, a ->
                val href = toSourcePath(base, a.attr("href"))
                val text = a.selectFirst("span.grow span")?.text()?.trim()?.ifBlank { null } ?: a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (chapters.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}/images?is_prev=False&current_page=1&reading_style=long_strip"))
            doc.select("img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

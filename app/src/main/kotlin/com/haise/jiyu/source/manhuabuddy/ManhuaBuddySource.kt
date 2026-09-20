package com.haise.jiyu.source.manhuabuddy

import com.haise.jiyu.util.absoluteMediaUrl
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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ManhuaBuddy (manhuabuddy.com) - vlastni PHP frontend, nikdy nebylo Madara.
 * Kapitoly se beru ze schema.org JSON-LD `ItemList` bloku na detailu titulu
 * (`<script type="application/ld+json">`) - obsahuje kompletni seznam se
 * spravnymi cisly a daty, na rozdil od HTML seznamu je uplny a stabilnejsi
 * nez CSS selektory.
 */
@Singleton
class ManhuaBuddySource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "manhuabuddy"
    override val name = "ManhuaBuddy"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://manhuabuddy.com"

    private fun get(url: String): Document {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        val html = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return Jsoup.parse(html)
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.visual").mapNotNull { el ->
            val link = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = el.parent()?.selectFirst("h3.title")?.text()?.trim().orEmpty()
                .ifBlank { link.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { return@mapNotNull null }
            val cover = link.selectFirst("img")?.attr("data-original")?.let { absoluteMediaUrl(base, it) }
            // Web pri redesignu 2026 zmenil "div.visual a" na relativni href (drive
            // absolutni) - OkHttp Request.Builder().url() na relativni URL vyhodi
            // IllegalArgumentException, ktera se ztrati v try/catch jako prazdny seznam.
            val absoluteHref = href.let { absoluteMediaUrl(base, it) } ?: (base + href)
            SManga(sourceId = id, url = absoluteHref, title = title, coverUrl = cover, contentType = "MANHWA")
        }

    // "/genre/{slug}" archiv pouziva stejne "div.visual" karty jako "/popular" a
    // "/search" - overeno zive (odlisne tituly pro "action" vs "romance"). Genrovy
    // archiv nema vlastni razeni, takze pri filter.genres pouzivame jen filter.genres.first().
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = get("$base/genres")
            val tags = doc.select("a[href*=/genre/]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = Regex("""/genre/([a-z0-9-]+)$""").find(href)?.groupValues?.get(1)?.ifBlank { null }
                    ?: return@mapNotNull null
                val label = a.selectFirst("h3.tag-name")?.text()?.trim()?.ifBlank { null }
                    ?: a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val genre = filter.genres.firstOrNull()
        if (genre != null) {
            return@withContext try { parseList(get("$base/genre/$genre?page=$page")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // /popular a /new-manga jsou samostatne cesty, ne query parametr - overeno zivě,
        // vraci prokazatelne jine tituly.
        val path = if (filter.sortBy == "latest") "new-manga" else "popular"
        try { parseList(get("$base/$path?page=$page")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val genre = filter.genres.firstOrNull()
        if (genre != null) {
            return@withContext try { parseList(get("$base/genre/$genre?page=$page")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?s=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun lineContent(doc: Document, label: String): String? =
        doc.select("span.line-text").firstOrNull { it.text().trim().trimEnd(':').equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            val genresSpan = doc.select("span.line-text").firstOrNull { it.text().trim() == "Genres" }?.nextElementSibling()
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("meta[name=twitter:description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() },
                status = lineContent(doc, "Status"),
                genres = genresSpan?.select("a.item-tag")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                contentType = "MANHWA",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            val json = doc.selectFirst("script[type=application/ld+json]")?.data() ?: return@withContext emptyList()
            val graph = JSONObject(json).optJSONArray("@graph") ?: return@withContext emptyList()
            var items: org.json.JSONArray? = null
            for (i in 0 until graph.length()) {
                val node = graph.getJSONObject(i)
                if (node.optString("@type") == "ItemList") {
                    items = node.optJSONArray("itemListElement")
                    break
                }
            }
            items ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                val entry = items.getJSONObject(i).optJSONObject("item") ?: return@mapNotNull null
                val href = entry.optString("url").ifBlank { return@mapNotNull null }
                val chName = entry.optString("name").ifBlank { "Chapter" }
                val num = Regex("""(\d+(?:\.\d+)?)""").find(chName)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = chName,
                    chapterNumber = num,
                    dateUpload = parseIsoDate(entry.optString("datePublished")),
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseIsoDate(text: String): Long = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            get(chapter.url).select("div.chapter-content div.item-photo img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

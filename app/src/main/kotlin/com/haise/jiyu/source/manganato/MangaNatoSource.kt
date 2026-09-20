package com.haise.jiyu.source.manganato

import com.haise.jiyu.util.lazySrc
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
class MangaNatoSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "manganato"
    override val name = "MangaNato"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://www.natomanga.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override val supportsTagFilter: Boolean get() = true

    // Panel "GENRES" (div.panel-category) je soucasti kazde stranky (sidebar) -
    // dotahujeme z homepage a cachujeme, seznam se v behu appky nemeni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get(base))
            val tags = doc.select("div.panel-category a[href*=/genre/]").mapNotNull { a ->
                val href = a.attr("href")
                if (href.contains("/genre/all")) return@mapNotNull null
                val slug = href.substringAfterLast("/genre/").substringBefore("?").trim().ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseListItems(doc: org.jsoup.nodes.Document) =
        // .list-story-item je pouzit i pro banner reklamy - ty maji href mimo /manga/
        doc.select(".list-story-item[href*=/manga/]").mapNotNull { el ->
            SManga(
                sourceId = id,
                url = toSourcePath(base, el.attr("href")),
                title = el.attr("title").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                coverUrl = el.selectFirst("img")?.let {
                    it.lazySrc().orEmpty()
                }?.let { absoluteMediaUrl(base, it) },
            )
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListItems(Jsoup.parse(get("$base/genre/${filter.genres.first()}?page=$page")))
            }
            val doc = Jsoup.parse(get("$base/manga-list/hot-manga?page=$page"))
            parseListItems(doc)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListItems(Jsoup.parse(get("$base/genre/${filter.genres.first()}?page=$page")))
            }
            val q = URLEncoder.encode(query.lowercase(), "UTF-8")
                .replace("+", "_").replace("%20", "_")
            val doc = Jsoup.parse(get("$base/search/story/$q?page=$page"))
            doc.select(".story_item").mapNotNull { el ->
                val link = el.selectFirst("a[href*=/manga/]") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = toSourcePath(base, link.attr("href")),
                    title = el.selectFirst(".story_name a")?.text()?.trim()
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src")?.let { absoluteMediaUrl(base, it) },
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val infoItems = doc.select(".manga-info-text li")
            fun liText(label: String) = infoItems.firstOrNull {
                it.text().contains(label, ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()

            manga.copy(
                title = doc.selectFirst(".manga-info-text h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".manga-info-pic img")?.let {
                    it.lazySrc().orEmpty()
                }?.let { absoluteMediaUrl(base, it) } ?: manga.coverUrl,
                description = doc.selectFirst("#contentBox")
                    ?.text()?.replace(Regex("^.*?summary:\\s*", RegexOption.IGNORE_CASE), "")?.trim(),
                author = liText("Author"),
                genres = doc.select(".manga-info-text li.genres a").map { it.text().trim() },
                status = liText("Status"),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val links = doc.select(".chapter-list .row a")
            links.mapIndexed { i, a ->
                val name = a.text().trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = toSourcePath(base, a.attr("href")),
                    name = name,
                    // Seznam je NEJNOVĚJŠÍ PRVNÍ, takže záloha při chybějícím čísle v názvu je size - i
                    // (dřív i + 1 = obrácené číslování).
                    chapterNumber = parseChapterNumber(name) ?: (links.size - i).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select(".container-chapter-reader img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

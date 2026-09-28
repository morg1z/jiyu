package com.haise.jiyu.source.readnovelfull

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
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Zdroj pro readnovelfull.com (jiný engine než novelfull.com):
 * seznamy `.list-novel .row` s `h3.novel-title a`, archivy `/novel-list/{sort}-novel`
 * a `/genres/{slug}` (žánrové menu je na každé stránce), seznam kapitol `#list-chapter`,
 * text kapitoly `#chr-content`.
 */
class ReadNovelFullSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val languageOverride: String = "en",
    private val inGlobalSearch: Boolean = false,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')

    override val contentType = "NOVEL"
    override val homepageUrl get() = baseUrl
    override val language get() = languageOverride
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
        doc.select(".list-novel .row, .col-novel-main").mapNotNull { item ->
            val link = item.selectFirst("h3.novel-title a, .novel-title a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = href,
                title = link.attr("title").trim().ifBlank { link.text().trim() },
                coverUrl = item.selectFirst("img.cover, img")?.lazySrc()
                    ?.let { resolveSourceUrl(baseUrl, it) },
                contentType = "NOVEL",
            )
        }.distinctBy { it.url }

    private fun listUrl(page: Int, filter: MangaFilter): String = when {
        filter.genres.isNotEmpty() -> "$root/genres/${filter.genres.first()}?page=$page"
        filter.sortBy == "latest" -> "$root/novel-list/latest-release-novel?page=$page"
        else -> "$root/novel-list/most-popular-novel?page=$page"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseListing(Jsoup.parse(get(listUrl(page, filter)))) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(listUrl(page, filter))))
            }
            val url = "$root/novel-list/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page"
            parseListing(Jsoup.parse(get(url)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            // Žánrové dropdown menu je v navbaru každé stránky (a[href^="/genres/"]).
            val doc = Jsoup.parse(get(root))
            val tags = doc.select("a[href^='/genres/'], a[href*='/genres/']").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/genres/").trim('/')
                    .ifBlank { null } ?: return@mapNotNull null
                if (!slug.matches(Regex("[a-z0-9-]+"))) return@mapNotNull null
                FilterTag(id = slug, label = a.text().trim().ifBlank { slug })
            }.distinctBy { it.id }.sortedBy { it.label }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            manga.copy(
                title = doc.selectFirst("h3.title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst(".desc-text")?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:description']")?.attr("content"),
                author = doc.selectFirst(".info-meta a[href^='/authors/'], .info-meta a[href*='/authors/']")
                    ?.text()?.trim(),
                status = doc.selectFirst(".info-meta a[href*='novel-list']")?.text()?.trim(),
                genres = doc.select(".info-meta a[href^='/genres/'], .info-meta a[href*='/genres/']")
                    .map { it.text().trim() }.filter { it.isNotBlank() }.distinct(),
                coverUrl = doc.selectFirst(".book img, .books img")?.lazySrc()
                    ?.let { resolveSourceUrl(baseUrl, it) } ?: manga.coverUrl,
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            doc.select("#list-chapter li a, #chapter-archive li a, ul.list-chapter li a")
                .mapIndexedNotNull { i, a ->
                    val href = a.attr("href").ifBlank { return@mapIndexedNotNull null }
                    SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = href,
                        name = a.attr("title").trim().ifBlank {
                            a.text().trim().ifBlank { "Chapter ${i + 1}" }
                        },
                        chapterNumber = Regex("[\\d.,]+").find(a.text())?.value?.replace(',', '.')?.toFloatOrNull()
                            ?: (i + 1).toFloat(),
                        dateUpload = 0L,
                    )
                }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, chapter.url)))
            val content = doc.selectFirst("#chr-content") ?: return@withContext emptyList()
            content.select("script, style, iframe, ins, .adsbygoogle").remove()
            val text = content.novelText()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

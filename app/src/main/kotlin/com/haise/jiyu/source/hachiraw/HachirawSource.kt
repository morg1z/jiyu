package com.haise.jiyu.source.hachiraw

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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HachirawSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hachiraw"
    override val name = "Hachiraw"
    override val language = "ja" // japonsky raw web bez prekladu (overeno zive)
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    override val supportsTagFilter: Boolean get() = true
    private val base = "https://hachiraw.win"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(article: Element): SManga? {
        val link = article.selectFirst("h3.entry-title a") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val title = link.text().trim().ifBlank { return null }
        val cover = article.selectFirst(".featured-thumb img")?.attr("data-src")?.trim()
            ?.takeIf { it.isNotBlank() }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    // Web ma jen par kategorii (Action/Adult/Ecchi/Fantasy/Harem - overeno zive),
    // odkazy "/category/{id}/" jsou v hlavni navigaci homepage. Archiv strankuje
    // jako WordPress "/category/{id}/page/{n}/". Vice kategorii najednou web
    // nepodporuje - pri vice vybranych se pouzije prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            Jsoup.parse(get("$base/")).select("a[href*=/category/]").mapNotNull { a ->
                val tagId = a.attr("href").substringAfter("/category/").trim('/')
                if (tagId.isBlank() || !tagId.all { it.isDigit() }) return@mapNotNull null
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = tagId, label = label)
            }.distinctBy { it.id }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val url = "$base/category/${filter.genres.first()}/" +
                    if (page > 1) "page/$page/" else ""
                val doc = Jsoup.parse(get(url))
                doc.select("article.post.manga").mapNotNull(::parseCard)
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        try {
            val url = if (page <= 1) "$base/" else "$base/page/$page/"
            val doc = Jsoup.parse(get(url))
            doc.select("article.post.manga").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            val doc = Jsoup.parse(get(url))
            doc.select("article.post.manga").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val genres = doc.select("a[href^=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }
            val authorText = doc.select("p").firstOrNull { it.text().trim().startsWith("Author:") }
                ?.text()?.removePrefix("Author:")?.trim()
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                author = authorText?.takeIf { it.isNotBlank() && !it.equals("Updating", ignoreCase = true) },
                genres = genres,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("table.table-hover a[href^=/chapter/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val num = parseChapterNumber(name) ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img.aligncenter[data-src]").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

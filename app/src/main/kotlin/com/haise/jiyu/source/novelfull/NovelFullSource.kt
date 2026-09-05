package com.haise.jiyu.source.novelfull

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NovelFullSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelfull"
    override val name = "NovelFull"
    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://novelfull.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // Sidebar dropdown na homepage ("ul.dropdown-menu a[href^=/genre/]") uvadi
    // kompletni seznam zanru webu - href uz obsahuje presne zakodovany slug
    // (napr. "Gender+Bender", "Slice+of+Life"), ktery jde primo pouzit v ceste
    // "/genre/{slug}" - overeno zivě, ze /genre/Action a /genre/Comedy vraci
    // odlisne (a od /most-popular odlisne) seznamy.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/"))
            val tags = doc.select("a[href^=/genre/]").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/genre/").ifBlank { return@mapNotNull null }
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun parseGenreList(doc: Document): List<SManga> =
        doc.select(".list-truyen .row").mapNotNull { row ->
            val link = row.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
            SManga(
                sourceId = id,
                url = link.attr("href"),
                title = link.text().trim(),
                coverUrl = row.selectFirst("img.cover")?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$base$it"
                },
                contentType = "NOVEL",
            )
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseGenreList(Jsoup.parse(get("$base/genre/${filter.genres.first()}?page=$page"))) } catch (_: Exception) { emptyList() }
        }
        try {
            // overeno zive: /latest-release-novel vraci jine poradi nez /most-popular
            val path = if (filter.sortBy == "latest") "latest-release-novel" else "most-popular"
            val doc = Jsoup.parse(get("$base/$path?page=$page"))
            doc.select(".list-truyen .row").mapNotNull { row ->
                val link = row.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href"),
                    title = link.text().trim(),
                    coverUrl = row.selectFirst("img.cover")?.attr("src")?.let {
                        if (it.startsWith("http")) it else "$base$it"
                    },
                    contentType = "NOVEL",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseGenreList(Jsoup.parse(get("$base/genre/${filter.genres.first()}?page=$page"))) } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?keyword=$q&page=$page"))
            doc.select(".list-truyen .row").mapNotNull { row ->
                val link = row.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href"),
                    title = link.text().trim(),
                    coverUrl = row.selectFirst("img.cover")?.attr("src")?.let {
                        if (it.startsWith("http")) it else "$base$it"
                    },
                    contentType = "NOVEL",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h3.title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".book img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$base$it"
                } ?: manga.coverUrl,
                description = doc.selectFirst(".desc-text")?.text(),
                author = doc.selectFirst(".info a[href*='author']")?.text(),
                genres = doc.select(".info a[href*='genre']").map { it.text() },
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page <= 50) {
                val doc = Jsoup.parse(get("$base${manga.url}?page=$page"))
                val items = doc.select("#list-chapter .row li a")
                if (items.isEmpty()) break
                items.forEachIndexed { i, a ->
                    chapters.add(SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = a.attr("href"),
                        name = a.text().trim(),
                        chapterNumber = chapters.size.toFloat() + i + 1,
                        dateUpload = 0L,
                    ))
                }
                if (doc.selectFirst("li.next a") == null) break
                page++
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("#chapter-content script, #chapter-content .ads-holder").remove()
            val text = doc.selectFirst("#chapter-content")?.text()?.trim() ?: ""
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

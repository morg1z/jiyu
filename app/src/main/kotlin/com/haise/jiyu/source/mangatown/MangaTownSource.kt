package com.haise.jiyu.source.mangatown

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
 * Obrázky (cover i čtecí stránky) běží na mangahere CDN subdoménách
 * (zjcdn.mangahere.org, fmcdn.mangahere.com) a vyžadují Referer:
 * mangatown.com - viz hotlinkRefererSuffixes v AppModule.kt.
 *
 * Čtecí stránky jsou staromódně jednoobrázkové (jeden request = jedna
 * stránka), takže getPageList musí projít 1..total_pages sekvenčně.
 */
@Singleton
class MangaTownSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangatown"
    override val name = "MangaTown"
    override val homepageUrl get() = base
    private val base = "https://www.mangatown.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a.manga_cover[href^=/manga/]").mapNotNull { link ->
            val href = link.attr("href")
            val title = link.attr("title").takeIf { it.isNotBlank() }
                ?: link.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseList(get("$base/directory/0-${filter.genres.first()}-0-0-0-0/$page.html"))
            } catch (_: Exception) { emptyList() }
        }
        // Vychozi /directory/ razeni je Views (Popularni) - "Latest Updated" je zvlastni
        // query string, ne cesta (overeno zivě, jine tituly nez vychozi razeni).
        val sortSuffix = if (filter.sortBy == "latest") "?last_chapter_time.za" else ""
        try { parseList(get("$base/directory/$page.html$sortSuffix")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseList(get("$base/directory/0-${filter.genres.first()}-0-0-0-0/$page.html"))
            } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?name=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    // ─── Filtrování podle žánru ─────────────────────────────────────────────
    // /directory/ ma postrannim panelu odkazy na jednotlive zanry ve tvaru
    // /directory/0-{slug}-0-0-0-0/ (bitmaskova URL struktura mangatown archivu,
    // pozice 2 = genre slug se podtrzitky) - overeno zive (page 1 vs 2 pro
    // "action" vraci prokazatelne odlisne tituly).

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/directory/"))
            val tags = doc.select("a[href^=/directory/0-]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = Regex("""/directory/0-(.+?)-0-0-0-0/?""").find(href)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusText = doc.selectFirst("li:contains(Status(s))")?.ownText()?.trim()
            manga.copy(
                title = doc.selectFirst("h1.title-top")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("p#hide")?.text()?.trim(),
                author = doc.selectFirst("li:contains(Author(s)) a")?.text()?.trim(),
                genres = doc.select("li:contains(Genre(s)) a").map { it.text() },
                status = when {
                    statusText.equals("Ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.equals("Completed", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val links = doc.select("ul.chapter_list a[href^=${manga.url}c]")
            links.mapIndexedNotNull { i, a ->
                val href = a.attr("href")
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (links.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val firstHtml = get("$base${chapter.url}")
            val firstImg = Jsoup.parse(firstHtml).selectFirst("img#image")?.attr("src")?.let { normalizeImgUrl(it) }
                ?: return@withContext emptyList()
            val totalPages = Regex("""total_pages\s*=\s*(\d+)""").find(firstHtml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val pages = mutableListOf(Page(0, firstImg, firstImg))
            for (p in 2..totalPages) {
                val html = get("$base${chapter.url}$p.html")
                val img = Jsoup.parse(html).selectFirst("img#image")?.attr("src")?.let { normalizeImgUrl(it) } ?: continue
                pages.add(Page(p - 1, img, img))
            }
            pages
        } catch (_: Exception) { emptyList() }
    }

    private fun normalizeImgUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url
}

package com.haise.jiyu.source.twmanga

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

/**
 * twbzmg.com (Twmanga) - RAW (cinske manhua) zdroj, vlastni sablona (AMP
 * markup na listing strankach). Odkazy na kapitoly na detailu jsou
 * tracking-redirect wrapper (`/user/page_direct?comic_id=X&section_slot=Y&
 * chapter_slot=Z`), ale skutecna ctecka URL se da poskladat primo z tech
 * samych query parametru bez nutnosti nasledovat redirect:
 * `/comic/chapter/{comic_id}/{section_slot}_{chapter_slot}.html`.
 */
@Singleton
class TwmangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "twmanga"
    override val name = "Twmanga"
    override val homepageUrl get() = base
    private val base = "https://www.twbzmg.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        if (!href.startsWith("/comic/") || href.count { it == '/' } > 2) return null
        val title = a.attr("title").trim().ifBlank { return null }
        val cover = a.selectFirst("amp-img")?.attr("src")?.trim()?.takeIf { it.startsWith("http") }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANHUA")
    }

    // "/classify?type={slug}&region=all&state=all&filter=*" pouziva stejnou
    // "a.comics-card__poster" kartu jako homepage/hledani a podporuje strankovani
    // (&page=N) - overeno zive (odlisne tituly pro type=lianai vs type=wuxia, i mezi
    // page=1 a page=2 stejneho typu).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/classify"))
            val tags = doc.select("a[href*=/classify?type=]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = Regex("""type=([a-z]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                if (slug == "all") return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun classifyUrl(type: String, page: Int): String =
        "$base/classify?type=$type&region=all&state=all&filter=%2a&page=$page"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val genre = filter.genres.firstOrNull()
        if (genre != null) {
            return@withContext try {
                val doc = Jsoup.parse(get(classifyUrl(genre, page)))
                doc.select("a.comics-card__poster").mapNotNull(::parseCard).distinctBy { it.url }
            } catch (_: Exception) { emptyList() }
        }
        if (page > 1) return@withContext emptyList()
        // overeno zive: /list/new ma jine poradi nez homepage (48 odlisnych titulu);
        // ani jeden neni strankovany (?page= je ignorovano, staticky vypis).
        val url = if (filter.sortBy == "latest") "$base/list/new" else "$base/"
        try {
            val doc = Jsoup.parse(get(url))
            doc.select("a.comics-card__poster").mapNotNull(::parseCard).distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val genre = filter.genres.firstOrNull()
        if (genre != null) {
            return@withContext try {
                val doc = Jsoup.parse(get(classifyUrl(genre, page)))
                doc.select("a.comics-card__poster").mapNotNull(::parseCard).distinctBy { it.url }
            } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?q=$q&page=$page"))
            doc.select("a.comics-card__poster").mapNotNull(::parseCard).distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val tags = doc.select("div.tag-list span.tag").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(
                title = doc.selectFirst("h1.comics-detail__title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("h2.comics-detail__author")?.text()?.trim()?.takeIf { it.isNotBlank() },
                status = tags.firstOrNull(),
                genres = tags.drop(1),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("a[href^=/user/page_direct]").mapNotNull { a ->
                val href = a.attr("href")
                val query = href.substringAfter('?', "")
                val params = query.split('&').associate { p ->
                    val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    k to v
                }
                val comicId = params["comic_id"] ?: return@mapNotNull null
                val section = params["section_slot"] ?: return@mapNotNull null
                val chapterSlot = params["chapter_slot"] ?: return@mapNotNull null
                val name = a.text().trim().ifBlank { "Chapter $chapterSlot" }
                val num = chapterSlot.toFloatOrNull() ?: 0f
                val chapterUrl = "/comic/chapter/$comicId/${section}_$chapterSlot.html"
                SChapter(sourceId = id, mangaUrl = manga.url, url = chapterUrl, name = name, chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img[src*=bzcdn.net]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

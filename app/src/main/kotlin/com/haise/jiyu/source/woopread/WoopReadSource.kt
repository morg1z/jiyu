package com.haise.jiyu.source.woopread

import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

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
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WoopRead (woopread.com) - textovy light-novel web, vlastni Next.js
 * (App Router) frontend, nikdy nebyl Madara.
 *
 * Seznam kapitol NENI v DOM jako <a href> (App Router streamuje data jako
 * React Server Component payload - escapovany JSON schovany v <script>
 * chuncích, ne v klasickych atributech). Misto Jsoup selektoru se proto
 * parsuje primo regexem z syrove odpovedi (`{"title":"...","slug":"...",
 * "publishDate":"..."}` vzor, po odstraneni escapovani zpetnych lomitek).
 */
@Singleton
class WoopReadSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "woopread"
    override val name = "WoopRead"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://woopread.com"

    private fun getRaw(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun get(url: String): Document = Jsoup.parse(getRaw(url))

    private fun coverFromSrcSet(img: Element): String? {
        val srcSet = img.attr("srcset").ifBlank { return img.attr("src").let { absoluteMediaUrl(base, it) } }
        val firstEntry = srcSet.substringBefore(",").trim().substringBefore(" ")
        val encoded = firstEntry.substringAfter("url=", "").substringBefore("&").ifBlank { return null }
        return try { URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { e.rethrowIfControl(); null }
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("a[href^=\"/series/\"]")
            .filterNot { it.attr("href").contains("/chapter-") }
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").removePrefix("Cover image for ").trim().ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = "$base$href", title = title, coverUrl = coverFromSrcSet(img), contentType = "NOVEL")
            }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Puvodne vzdy "New" (tedy fakticky poradek pro "Nejnovejsi" bez ohledu na
        // vybranou zalozku) - "Popular" overeno zive jako odlisna, spravna hodnota
        // pro "Populární".
        val sortBy = if (filter.sortBy == "latest") "New" else "Popular"
        try { parseList(get("$base/browse?sortBy=$sortBy&page=$page")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?q=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun labelValue(doc: Document, label: String): String? =
        doc.select("span").firstOrNull { it.text().trim().trimEnd(':').equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            val genresSibling = doc.select("span").firstOrNull { it.text().trim() == "Genres:" }?.nextElementSibling()
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() },
                author = labelValue(doc, "Author"),
                status = labelValue(doc, "Status"),
                genres = genresSibling?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private val chapterEntryRegex = Regex(
        "\"title\":\"([^\"]*)\",\"number\":(\\d+(?:\\.\\d+)?),\"slug\":\"([^\"]*)\",\"publishDate\":\"([^\"]*)\""
    )

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val raw = getRaw(manga.url).replace("\\\"", "\"")
            chapterEntryRegex.findAll(raw).mapNotNull { m ->
                val (title, numberStr, slug, publishDate) = m.destructured
                if (slug.isBlank()) return@mapNotNull null
                val num = Regex("""(\d+(?:\.\d+)?)""").find(slug)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: Regex("""(\d+(?:\.\d+)?)""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: numberStr.toFloatOrNull() ?: 0f
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "${manga.url}/$slug",
                    name = title.ifBlank { "Chapter $num" },
                    chapterNumber = num,
                    dateUpload = parseIsoDate(publishDate),
                )
            }.distinctBy { it.url }.toList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseIsoDate(text: String): Long = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val text = get(chapter.url).select("div[id^=chapter-] p").joinToString("\n\n") { it.text().trim() }
                .trim()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

package com.haise.jiyu.source.mangafreak

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
class MangaFreakSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id   = "mangafreak"
    override val name = "MangaFreak"
    private val base  = "https://mangafreak.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> =
        Jsoup.parse(html, base).select(".manga_poster, .manga-item, .film-poster").mapNotNull { el ->
            val link  = el.selectFirst("a") ?: return@mapNotNull null
            val href  = link.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
            val title = (el.selectFirst("p, .manga-name, span.manga-name")?.text()?.trim()
                ?: link.attr("title").trim()).ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get("$base/popular-manga${if (page > 1) "?page=$page" else ""}"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q   = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search/$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
            manga.copy(
                title       = doc.selectFirst(".manga_right h1, h1.title, h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".manga_right img, .cover img")?.attr("src")
                    ?.takeIf { it.startsWith("http") } ?: manga.coverUrl,
                description = doc.selectFirst(".manga_summary, .description, p.summary")?.text()?.trim(),
                genres      = doc.select(".manga_genres a, .genres a, .tag a")
                    .map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".manga_info span:contains(Author) + a, .author a")?.text()?.trim(),
                status      = doc.selectFirst(".manga_info span:contains(Status) + span, .status")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
            doc.select(".chapter_list li a, .manga_episodes li a, ul.chapter-list li a").mapIndexed { i, a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
                val name = a.text().trim().ifBlank { "Chapter ${i + 1}" }
                SChapter(
                    sourceId      = id,
                    mangaUrl      = manga.url,
                    url           = href,
                    name          = name,
                    chapterNumber = Regex("""[Cc]hapter[\s\-_]*([\d.]+)""").find(name)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload    = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"), base)
            doc.select(".chapter_container img, .reading-content img, .chapter_images img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }
                    .takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

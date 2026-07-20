package com.haise.jiyu.source.mangahome

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
 * Sdílí infrastrukturu s MangaTown/Mangafox (mangahere síť) - obrázky na
 * zjcdn.mangahere.org už mají Referer pokrytý přes hotlinkRefererSuffixes
 * v AppModule.kt. Na rozdíl od MangaTown je čtecí stránka jednostránková
 * (všechny obrázky rovnou v HTML), žádné sekvenční procházení není potřeba.
 */
@Singleton
class MangaHomeSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangahome"
    override val name = "MangaHome"
    override val homepageUrl get() = base
    private val base = "https://www.mangahome.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a.post-cover[href^=/manga/]").mapNotNull { link ->
            val href = link.attr("href")
            val title = link.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/directory/$page.html")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?name=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusText = doc.selectFirst("p:contains(Status:)")?.ownText()?.trim()
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("p.hide")?.text()?.trim(),
                author = doc.selectFirst("p:contains(Author(s)) a")?.text()?.trim(),
                genres = doc.select("p:contains(Genre(s)) a").map { it.text() },
                status = when {
                    statusText.equals("Ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.equals("Completed", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    private val dateFormat = java.text.SimpleDateFormat("MMM dd,yyyy", java.util.Locale.ENGLISH)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val items = doc.select("ul.detail-chlist li")
            items.mapIndexedNotNull { i, li ->
                val a = li.selectFirst("a[href^=${manga.url}/c]") ?: return@mapIndexedNotNull null
                val href = a.attr("href")
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(href.substringAfterLast("/c"))?.value?.toFloatOrNull()
                    ?: (items.size - i).toFloat()
                val dateText = li.selectFirst("span.time")?.text()?.trim()
                val date = try { dateText?.let { dateFormat.parse(it)?.time } ?: 0L } catch (_: Exception) { 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = date)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img.image[src]").mapIndexedNotNull { i, img ->
                val src = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val url = if (src.startsWith("//")) "https:$src" else src
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

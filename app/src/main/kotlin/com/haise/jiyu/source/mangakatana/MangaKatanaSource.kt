package com.haise.jiyu.source.mangakatana

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
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaKatanaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangakatana"
    override val name = "MangaKatana"
    private val base = "https://mangakatana.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.item").mapNotNull { el ->
            val link = el.selectFirst("h3.title a, h3 a") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix(base)
            val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val cover = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/latest/$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?search=$q&search_by=book_name&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusText = doc.selectFirst(".d-cell-small.value.status")?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1.heading")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".cover img, .thumb img")?.let {
                    it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                } ?: manga.coverUrl,
                description = doc.selectFirst(".summary p")?.text()?.trim(),
                genres = doc.select(".genres a").map { it.text() },
                author = doc.selectFirst(".authors a")?.text()?.trim(),
                status = when {
                    statusText.equals("Ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.equals("Completed", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    private val dateFormat = SimpleDateFormat("MMM-dd-yyyy", Locale.ENGLISH)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val rows = doc.select("div.chapters tr")
            rows.mapIndexedNotNull { i, row ->
                val a = row.selectFirst("div.chapter a") ?: return@mapIndexedNotNull null
                val href = a.attr("href").removePrefix(base)
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (rows.size - i).toFloat()
                val dateText = row.selectFirst("div.update_time")?.text()?.trim()
                val date = try { dateText?.let { dateFormat.parse(it)?.time } ?: 0L } catch (_: Exception) { 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = date)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val match = Regex("""var thzq\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?: return@withContext emptyList()
            match.groupValues[1]
                .split(",")
                .map { it.trim().trim('\'') }
                .filter { it.isNotBlank() }
                .mapIndexed { i, url -> Page(i, url, url) }
        } catch (_: Exception) { emptyList() }
    }
}

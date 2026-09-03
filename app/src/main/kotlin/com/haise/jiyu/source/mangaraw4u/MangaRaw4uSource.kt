package com.haise.jiyu.source.mangaraw4u

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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangaraw4u.com - RAW (japonske, neprelozene) manga. Cely web je server-rendered
 * vcetne cteni (zadny WebView/JS trik potreba), hledani jede pres cisty JSON
 * `/api/search?q=`.
 */
@Singleton
class MangaRaw4uSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangaraw4u"
    override val name = "MangaRaw4u"
    override val homepageUrl get() = base
    private val base = "https://mangaraw4u.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // /search?sort= ma stejnou strukturu karet jako uvodni strana, ale na rozdil
            // od ni umi radit i podle "-updated_at" (Nejnovejsi) - overeno zivě, jina data
            // nez u "-views" (Popularni).
            val sort = if (filter.sortBy == "latest") "-updated_at" else "-views"
            val doc = Jsoup.parse(get("$base/search?sort=$sort&page=$page"))
            doc.select("a.result-card").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = a.selectFirst(".result-card-title")?.text()?.trim() ?: return@mapNotNull null
                val cover = a.selectFirst(".result-card-image img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
                SManga(sourceId = id, url = href.removePrefix(base), title = title, coverUrl = cover, contentType = "MANGA")
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(get("$base/api/search?q=$q"))
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i ->
                val m = results.optJSONObject(i) ?: return@mapNotNull null
                val slug = m.optString("slug").ifBlank { return@mapNotNull null }
                SManga(
                    sourceId = id, url = "/manga/$slug", title = m.optString("name"),
                    coverUrl = m.optString("cover_full_url").takeIf { it.isNotBlank() },
                    contentType = "MANGA",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val genres = doc.select(".detail-genres a").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(
                title = doc.selectFirst("h1.detail-title")?.text()?.trim() ?: manga.title,
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.US)
            doc.select("div.detail-chapter-row").mapNotNull { row ->
                val a = row.selectFirst(".detail-col-chapter a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = row.attr("data-chapter-number").toFloatOrNull() ?: 0f
                val dateText = row.selectFirst(".detail-col-updated")?.text()?.trim().orEmpty()
                val date = try { dateFormat.parse(dateText)?.time ?: 0L } catch (_: Exception) { 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = a.text().trim(), chapterNumber = num, dateUpload = date)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img").filter { it.attr("alt").contains(" - Page ") }.mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

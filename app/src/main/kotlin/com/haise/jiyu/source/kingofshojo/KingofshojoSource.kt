package com.haise.jiyu.source.kingofshojo

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WordPress "mangareader" téma (odlišné od Madara). Seznam kapitol se
 * NEnačítá server-rendered (v HTML je jen Handlebars šablona `{{id}}`),
 * ale přes WP AJAX `admin-ajax.php?action=get_chapters&id={postId}`, kde
 * postId je v `a.series[rel]` atributu na listing/detail stránce.
 */
@Singleton
class KingofshojoSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "kingofshojo"
    override val name = "Kingofshojo"
    override val contentType = "MANHWA"
    private val base = "https://kingofshojo.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun postForm(url: String, params: Map<String, String>): String {
        val bodyBuilder = FormBody.Builder()
        params.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .post(bodyBuilder.build())
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun encodeUrl(path: String, postId: String) = "$path::$postId"
    private fun pathOf(mangaUrl: String) = mangaUrl.substringBefore("::")
    private fun postIdOf(mangaUrl: String) = mangaUrl.substringAfter("::", "")

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("li").mapNotNull { li ->
            val link = li.selectFirst("div.leftseries h2 a.series") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix(base)
            val postId = link.attr("rel").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = li.selectFirst("div.imgseries img")?.attr("src")
            SManga(sourceId = id, url = encodeUrl(href, postId), title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/manga/?order=update" else "$base/manga/page/$page/?order=update"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/page/$page/?s=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${pathOf(manga.url)}"))
            val statusText = doc.selectFirst("td:contains(Status)")?.nextElementSibling()?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.entry-content-single")?.text()?.trim(),
                genres = doc.select("div.seriestugenre a").map { it.text() },
                status = when {
                    statusText.isNullOrBlank() -> null
                    statusText.contains("ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.contains("complet", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val postId = postIdOf(manga.url).ifBlank { return@withContext emptyList() }
            val html = postForm("$base/wp-admin/admin-ajax.php", mapOf("action" to "get_chapters", "id" to postId))
            val options = Jsoup.parse(html).select("option[value]")
            options.mapIndexedNotNull { i, opt ->
                val href = opt.attr("value").removePrefix(base)
                val text = opt.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (options.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img[src*=cdn.kingofshojo.com]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

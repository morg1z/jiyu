package com.haise.jiyu.source.mangafire

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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaFireSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangafire"
    override val name = "MangaFire"

    private val base = "https://mangafire.to"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseListPage(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".unit .inner").mapNotNull { el ->
            val link = el.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
            val href = link.attr("href").let { if (it.startsWith("http")) it else "$base$it" }
            val title = el.selectFirst(".info .name, .name, h3, h2")?.text()?.trim()
                ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }?.let { if (it.startsWith("//")) "https:$it" else it }
            SManga(sourceId = id, url = href.removePrefix(base), title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseListPage(get("$base/filter?sort=most_viewed&page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseListPage(get("$base/filter?keyword=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val title = doc.selectFirst(".manga-datas h1, .info h1, h1.name, .detail-title")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".poster img, .manga-poster img, .cover img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }?.let { if (it.startsWith("//")) "https:$it" else it } ?: manga.coverUrl
            val desc = doc.selectFirst(".synopsis p, .synopsis, .description p, .description")?.text()
            val genres = doc.select(".genres a, .genre a, .manga-datas .meta a[href*='genre']").map { it.text().trim() }.filter { it.isNotBlank() }
            val author = doc.selectFirst(".author a, .manga-datas .meta a[href*='author']")?.text()?.trim()
            manga.copy(title = title, coverUrl = cover, description = desc, genres = genres, author = author)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val items = doc.select("#chapter-list li a, .chapter-list li a")
            if (items.isNotEmpty()) {
                return@withContext items.mapIndexed { i, el ->
                    val href = el.attr("href").removePrefix(base)
                    val chName = el.selectFirst(".name, .chapter-name")?.text()?.trim()
                        ?: el.text().trim().takeIf { it.isNotBlank() }
                        ?: "Chapter ${i + 1}"
                    val num = el.attr("data-number").toFloatOrNull() ?: (items.size - i).toFloat()
                    SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = href,
                        name = chName,
                        chapterNumber = num,
                        dateUpload = 0L,
                    )
                }
            }
            emptyList()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterId = chapter.url.substringAfterLast("/")
            val json = get("$base/ajax/read/$chapterId/images")
            val result = JSONObject(json).optJSONObject("result") ?: return@withContext emptyList()
            val images = result.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).map { i ->
                val img = images.getJSONArray(i)
                val url = img.getString(0)
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

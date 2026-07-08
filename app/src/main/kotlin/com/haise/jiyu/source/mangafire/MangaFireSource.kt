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
            val html = get("$base${manga.url}")
            val doc = Jsoup.parse(html)

            // Metoda 1: Přímé parsování HTML chapter listu
            val htmlChapters = doc.select("#chapter-list li, .chapter-list li, [class*='chapter'] li")
                .mapIndexedNotNull { i, li ->
                    val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    val title = li.selectFirst(".name, span")?.text()?.trim()
                        ?: a.text().trim().takeIf { it.isNotBlank() }
                        ?: "Chapter ${i + 1}"
                    val num = li.attr("data-number").toFloatOrNull()
                        ?: Regex("""[Cc]hapter[\s\-_]*([\d.]+)""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: (i + 1).toFloat()
                    SChapter(
                        sourceId = id, mangaUrl = manga.url,
                        url = href.removePrefix(base), name = title,
                        chapterNumber = num, dateUpload = 0L,
                    )
                }
            if (htmlChapters.isNotEmpty()) return@withContext htmlChapters

            // Metoda 2: Ajax API fallback
            val mangaId = doc.selectFirst("[data-id]")?.attr("data-id")
                ?: manga.url.substringAfterLast(".").takeIf { it.matches(Regex("[a-z0-9]+")) }
                ?: manga.url.substringAfterLast("/").substringAfterLast(".")
            if (mangaId.isBlank()) return@withContext emptyList()

            val ajaxDoc = try {
                Jsoup.parse(get("$base/ajax/manga/$mangaId/chapter/en"))
            } catch (_: Exception) { return@withContext emptyList() }

            ajaxDoc.select("li a, .item a").mapIndexedNotNull { i, a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val title = a.selectFirst(".name, span")?.text()?.trim()
                    ?: a.text().trim().takeIf { it.isNotBlank() }
                    ?: "Chapter ${i + 1}"
                val num = Regex("""[Cc]hapter[\s\-_]*([\d.]+)""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                SChapter(
                    sourceId = id, mangaUrl = manga.url,
                    url = href.removePrefix(base), name = title,
                    chapterNumber = num, dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterId = chapter.url.substringAfterLast("/").substringBefore("?")

            // Metoda 1: Ajax images API
            val json = try { get("$base/ajax/read/$chapterId/images") } catch (_: Exception) { "" }
            if (json.isNotBlank() && json.contains("images")) {
                try {
                    val result = JSONObject(json).optJSONObject("result")
                    val images = result?.optJSONArray("images")
                    if (images != null && images.length() > 0) {
                        return@withContext (0 until images.length()).mapNotNull { i ->
                            val img = images.optJSONArray(i) ?: return@mapNotNull null
                            val url = img.optString(0).takeIf { it.startsWith("http") } ?: return@mapNotNull null
                            Page(i, url, url)
                        }
                    }
                } catch (_: Exception) { }
            }

            // Metoda 2: HTML parsování stránky kapitoly
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select(".reader-images img, [class*='read'] img, .chapter-images img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

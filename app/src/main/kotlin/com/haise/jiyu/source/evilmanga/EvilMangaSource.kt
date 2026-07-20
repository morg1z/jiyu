package com.haise.jiyu.source.evilmanga

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
class EvilMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "evilmanga"
    override val name = "Evil Manga"
    override val homepageUrl get() = base
    private val base = "https://evil-manga.eu"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return (
            doc.select(".manga-item, .post-item, .page-item-detail, .manga")
                .takeIf { it.isNotEmpty() }
                ?: doc.select("article, .item")
        ).mapNotNull { el ->
            val link = el.selectFirst("a[href*='evil-manga'], a[href^='/']") ?: return@mapNotNull null
            val href = link.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
            val title = (el.selectFirst(".manga-name, .post-title, h3, h2, .title")?.text()
                ?: link.attr("title")
                ?: link.text()).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?s=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h1, .manga-title, .post-title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".manga-cover img, .summary_image img, .book-cover img")?.let {
                    it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                } ?: manga.coverUrl,
                description = doc.selectFirst(".manga-summary, .summary__content, .description p")?.text(),
                genres = doc.select(".genres a, .manga-genres a").map { it.text() },
                author = doc.selectFirst(".author a, .manga-authors a")?.text(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val chapters = doc.select(".wp-manga-chapter a, .chapter-list a, .chapters a, li.chapter a")
            chapters.mapIndexed { i, a ->
                SChapter(
                    sourceId = id, mangaUrl = manga.url,
                    url = a.attr("href").removePrefix(base),
                    name = a.text().trim().takeIf { it.isNotBlank() } ?: "Kapitola ${i + 1}",
                    chapterNumber = (chapters.size - i).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select(".reading-content img, .page-break img, .chapter-content img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

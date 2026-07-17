package com.haise.jiyu.source.novelfire

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
 * Novel Fire (novelfire.net) - server-rendered custom web. Kapitoly
 * jsou na samostatne strance "{book}/chapters" (stovky az tisice
 * kapitol, strankovano po 100), text kapitoly primo v `#content`.
 */
@Singleton
class NovelFireSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelfire"
    override val name = "Novel Fire"
    override val contentType: String get() = "NOVEL"
    private val base = "https://novelfire.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("li.novel-item").mapNotNull { el ->
            val link = el.selectFirst("h2.title a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$base$it" }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/latest-release-novels?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?keyword=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val description = doc.select("div.summary div.content p").joinToString("\n\n") { it.text().trim() }
                .ifBlank { null }
            manga.copy(
                title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("div.author span[itemprop=author]")?.text()?.trim(),
                genres = doc.select("div.categories a.property-item").map { it.text().trim() },
                description = description,
                status = doc.selectFirst("strong.ongoing, strong.completed, strong.hiatus")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page < 300) {
                val doc = Jsoup.parse(get("$base${manga.url}/chapters?page=$page"))
                val rows = doc.select("ul.chapter-list li a")
                if (rows.isEmpty()) break
                rows.forEach { a ->
                    val href = a.attr("href")
                    val num = a.selectFirst("span.chapter-no")?.text()?.trim()?.toFloatOrNull() ?: 0f
                    val title = a.selectFirst("strong.chapter-title")?.text()?.trim()?.ifBlank { null }
                        ?: "Chapter $num"
                    val dateAttr = a.selectFirst("time.chapter-update")?.attr("datetime")
                    chapters.add(
                        SChapter(
                            sourceId = id,
                            mangaUrl = manga.url,
                            url = href,
                            name = title,
                            chapterNumber = num,
                            dateUpload = parseDate(dateAttr),
                        )
                    )
                }
                if (doc.selectFirst("li.page-item a[href*=page=${page + 1}]") == null) break
                page++
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val text = doc.selectFirst("div#content")?.text()?.trim().orEmpty()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

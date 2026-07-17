package com.haise.jiyu.source.manganato

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
class MangaNatoSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "manganato"
    override val name = "MangaNato"
    private val base = "https://www.natomanga.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/manga-list/hot-manga?page=$page"))
            // .list-story-item je pouzit i pro banner reklamy - ty maji href mimo /manga/
            doc.select(".list-story-item[href*=/manga/]").mapNotNull { el ->
                SManga(
                    sourceId = id,
                    url = el.attr("href").removePrefix(base),
                    title = el.attr("title").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.let {
                        it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                    }?.takeIf { it.startsWith("http") },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query.lowercase(), "UTF-8")
                .replace("+", "_").replace("%20", "_")
            val doc = Jsoup.parse(get("$base/search/story/$q?page=$page"))
            doc.select(".story_item").mapNotNull { el ->
                val link = el.selectFirst("a[href*=/manga/]") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = el.selectFirst(".story_name a")?.text()?.trim()
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val infoItems = doc.select(".manga-info-text li")
            fun liText(label: String) = infoItems.firstOrNull {
                it.text().contains(label, ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()

            manga.copy(
                title = doc.selectFirst(".manga-info-text h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".manga-info-pic img")?.let {
                    it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
                }?.takeIf { it.startsWith("http") } ?: manga.coverUrl,
                description = doc.selectFirst("#contentBox")
                    ?.text()?.replace(Regex("^.*?summary:\\s*", RegexOption.IGNORE_CASE), "")?.trim(),
                author = liText("Author"),
                genres = doc.select(".manga-info-text li.genres a").map { it.text().trim() },
                status = liText("Status"),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select(".chapter-list .row a").mapIndexed { i, a ->
                val name = a.text().trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = a.attr("href").removePrefix(base),
                    name = name,
                    chapterNumber = Regex("""[Cc]hapter\s*([\d.]+)""")
                        .find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select(".container-chapter-reader img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.novelfull

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
class NovelFullSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelfull"
    override val name = "NovelFull"
    private val base = "https://novelfull.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/most-popular-novel?page=$page"))
            doc.select(".list-truyen .row").mapNotNull { row ->
                val link = row.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href"),
                    title = link.text().trim(),
                    coverUrl = row.selectFirst("img.cover")?.attr("src")?.let {
                        if (it.startsWith("http")) it else "$base$it"
                    },
                    contentType = "NOVEL",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?keyword=$q&page=$page"))
            doc.select(".list-truyen .row").mapNotNull { row ->
                val link = row.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href"),
                    title = link.text().trim(),
                    coverUrl = row.selectFirst("img.cover")?.attr("src")?.let {
                        if (it.startsWith("http")) it else "$base$it"
                    },
                    contentType = "NOVEL",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h3.title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".book img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$base$it"
                } ?: manga.coverUrl,
                description = doc.selectFirst(".desc-text")?.text(),
                author = doc.selectFirst(".info a[href*='author']")?.text(),
                genres = doc.select(".info a[href*='genre']").map { it.text() },
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page <= 50) {
                val doc = Jsoup.parse(get("$base${manga.url}?page=$page"))
                val items = doc.select("#list-chapter .row li a")
                if (items.isEmpty()) break
                items.forEachIndexed { i, a ->
                    chapters.add(SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = a.attr("href"),
                        name = a.text().trim(),
                        chapterNumber = chapters.size.toFloat() + i + 1,
                        dateUpload = 0L,
                    ))
                }
                if (doc.selectFirst("li.next a") == null) break
                page++
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("#chapter-content script, #chapter-content .ads-holder").remove()
            val text = doc.selectFirst("#chapter-content")?.text()?.trim() ?: ""
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

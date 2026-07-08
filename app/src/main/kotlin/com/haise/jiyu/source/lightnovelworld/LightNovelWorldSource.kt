package com.haise.jiyu.source.lightnovelworld

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
class LightNovelWorldSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "lightnovelworld"
    override val name = "LightNovelWorld"
    private val base = "https://www.lightnovelworld.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/browse/sort-popular?page=$page"))
            doc.select(".novel-item").mapNotNull { item ->
                val link = item.selectFirst("a.cover-image") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href"),
                    title = item.selectFirst(".novel-title")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = item.selectFirst("img")?.attr("data-src"),
                    contentType = "NOVEL",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?title=$q&page=$page"))
            doc.select(".novel-item").mapNotNull { item ->
                val link = item.selectFirst("a.cover-image") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href"),
                    title = item.selectFirst(".novel-title")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = item.selectFirst("img")?.attr("data-src"),
                    contentType = "NOVEL",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst(".novel-title, h1.novel-title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".cover img")?.attr("data-src") ?: manga.coverUrl,
                description = doc.selectFirst(".summary .content, .novel-introduction")?.text(),
                author = doc.selectFirst(".author a")?.text(),
                genres = doc.select(".categories a, .tag-item a").map { it.text() },
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select(".chapter-list li a, ul.chapter-list li a").mapIndexed { i, a ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = a.attr("href"),
                    name = a.selectFirst(".chapter-title")?.text()?.trim()
                        ?: a.text().trim().takeIf { it.isNotBlank() }
                        ?: "Chapter ${i + 1}",
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("#chapter-container script, #chapter-container .ad").remove()
            val text = doc.selectFirst("#chapter-container, .chapter-content")?.text()?.trim() ?: ""
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

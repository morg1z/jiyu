package com.haise.jiyu.source.freewebnovel

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeWebNovelSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "freewebnovel"
    override val name = "FreeWebNovel"
    override val contentType = "NOVEL"
    private val base = "https://freewebnovel.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("div.li").mapNotNull { item ->
            val link = item.selectFirst("h3.tit a") ?: return@mapNotNull null
            val cover = item.selectFirst("div.pic img")?.attr("src")
            SManga(
                sourceId = id,
                url = link.attr("href"),
                title = link.text().trim(),
                coverUrl = cover?.let { if (it.startsWith("http")) it else "$base$it" },
                contentType = "NOVEL",
            )
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page > 1) "$base/sort/most-popular/$page" else "$base/sort/most-popular"
            parseListing(Jsoup.parse(get(url)))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseListing(Jsoup.parse(get("$base/search?searchkey=$q")))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                description = doc.selectFirst("meta[property=og:description]")?.attr("content"),
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("ul#idData li a.con").mapIndexed { i, a ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = a.attr("href"),
                    name = a.text().trim().ifBlank { "Chapter ${i + 1}" },
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val text = doc.selectFirst("div#article")?.text()?.trim() ?: ""
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.boxnovel

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
class BoxNovelSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "boxnovel"
    override val name = "BoxNovel"
    override val contentType = "NOVEL"
    private val base = "https://boxnovel.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/novel/?m_orderby=views&page=$page"))
            doc.select(".page-listing-item").mapNotNull { el ->
                val link = el.selectFirst("h3 a, h3.h5 a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = link.text().trim(),
                    coverUrl = el.selectFirst("img")?.attr("data-src"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/?s=$q&post_type=wp-manga"))
            doc.select(".c-tabs-item__content").mapNotNull { el ->
                val link = el.selectFirst("h3 a, .post-title a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = link.text().trim(),
                    coverUrl = el.selectFirst("img")?.attr("data-src"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst(".post-title h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".summary_image img")?.attr("data-src") ?: manga.coverUrl,
                description = doc.selectFirst(".summary__content p, .description-summary p")?.text(),
                genres = doc.select(".genres-content a").map { it.text() },
                author = doc.selectFirst(".author-content a")?.text(),
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select(".wp-manga-chapter a").mapIndexed { i, a ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = a.attr("href").removePrefix(base),
                    name = a.text().trim(),
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val content = doc.selectFirst(".reading-content .text-left, .reading-content")
                ?: return@withContext emptyList()
            content.select("script, style, .code-block, .chapter-warning").remove()
            val text = content.text().trim()
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

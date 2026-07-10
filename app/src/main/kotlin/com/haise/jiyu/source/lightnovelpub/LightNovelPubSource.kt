package com.haise.jiyu.source.lightnovelpub

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
class LightNovelPubSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id          = "lightnovelpub"
    override val name        = "LightNovelPub"
    override val contentType = "NOVEL"
    private val base         = "https://www.lightnovelpub.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> =
        Jsoup.parse(html, base).select(".novel-item").mapNotNull { el ->
            val link = el.selectFirst(".novel-title a, a.novel-cover") ?: return@mapNotNull null
            val href = link.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
            SManga(
                sourceId = id,
                url      = href,
                title    = el.selectFirst(".novel-title a")?.text()?.trim()
                    ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null,
                coverUrl = el.selectFirst("img")?.let { img ->
                    (img.attr("data-src").ifBlank { img.attr("src") }).let { src ->
                        if (src.startsWith("http")) src
                        else if (src.startsWith("/")) "$base$src"
                        else null
                    }
                },
            )
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/browse/genre/all-genres/order-popular?page=$page")) }
        catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?inputContent=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
            manga.copy(
                title       = doc.selectFirst(".novel-title, h1.title")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".novel-cover img, .cover img")?.let { img ->
                    (img.attr("src").ifBlank { img.attr("data-src") }).let { src ->
                        if (src.startsWith("http")) src else if (src.startsWith("/")) "$base$src" else manga.coverUrl
                    }
                } ?: manga.coverUrl,
                description = doc.selectFirst(".summary .content, .novel-synopsis")?.text()?.trim(),
                genres      = doc.select(".categories .content a, .novel-tags a")
                    .map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".author .content a, .authors a")?.text()?.trim(),
                status      = if (doc.selectFirst(".ongoing") != null) "Ongoing"
                              else if (doc.selectFirst(".completed") != null) "Completed" else null,
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            // Chapters jsou na podstránce /chapters/page-1/
            val slug = manga.url.trimEnd('/')
            val doc  = Jsoup.parse(get("$base$slug/chapters/page-1/"), base)
            doc.select("ul.chapter-list li a, .chapter-list .chapter-item a").mapIndexed { i, a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
                val name = a.text().trim().ifBlank { "Chapter ${i + 1}" }
                SChapter(
                    sourceId      = id,
                    mangaUrl      = manga.url,
                    url           = href,
                    name          = name,
                    chapterNumber = Regex("""[Cc]hapter[\s\-_]*([\d.]+)""").find(name)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload    = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val url = if (chapter.url.startsWith("http")) chapter.url else "$base${chapter.url}"
            val doc = Jsoup.parse(get(url), base)
            val content = doc.selectFirst("#chapter-container .content, .chapter-content, .chapter-reading-page")
                ?: return@withContext emptyList()
            content.select("script, style, .ads-holder, ins, .adsbygoogle").remove()
            val text = content.text().trim()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

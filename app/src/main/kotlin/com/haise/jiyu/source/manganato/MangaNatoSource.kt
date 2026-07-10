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
    private val base = "https://chapmanganato.to"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/genre-all/$page?type=topview"))
            doc.select(".content-genres-item").mapNotNull { el ->
                val link = el.selectFirst(".genres-item-name a, h3 a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = link.text().trim(),
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
            doc.select(".panel-search-story .search-story-item").mapNotNull { el ->
                val imgLink = el.selectFirst("a.item-img") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = imgLink.attr("href").removePrefix(base),
                    title = el.selectFirst(".item-right h3 a, h3")?.text()?.trim()
                        ?: imgLink.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val rows = doc.select(".variations-tableInfo tr")
            fun rowValue(label: String) = rows.firstOrNull {
                it.select("td").firstOrNull()?.text()?.contains(label, ignoreCase = true) == true
            }?.selectFirst("td.table-value")

            manga.copy(
                title = doc.selectFirst(".story-info-right h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".story-info-left img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst(".panel-story-info-description")
                    ?.text()?.replace(Regex("^Description\\s*:\\s*"), "")?.trim(),
                author = rowValue("Author")?.select("a")?.joinToString { it.text() },
                genres = rowValue("Genre")?.select("a")?.map { it.text().trim() } ?: emptyList(),
                status = rowValue("Status")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select(".row-content-chapter li a").mapIndexed { i, a ->
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

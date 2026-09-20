package com.haise.jiyu.source.voidscans

import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Void Scans (voidscans.net) - maly staticky Hugo web, nikdy nebyl Madara.
 * Cely katalog (~18 titulu) je na jedne uvodni strance, zadne strankovani
 * ani fulltextove hledani - search proto jen filtruje homepage lokalne
 * (stejny vzor jako [com.haise.jiyu.source.hachirumi.HachirumiSource]).
 */
@Singleton
class VoidScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "voidscans"
    override val name = "Void Scans"
    override val supportsSortOrder: Boolean get() = false
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://voidscans.net"

    private fun get(url: String): Document {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        val html = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return Jsoup.parse(html)
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.card.shadow-sm").mapNotNull { el ->
            val link = el.selectFirst("a[href*=\"/library/\"]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("p.card-text")?.text()?.trim() ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("src")?.let { absoluteMediaUrl(base, it) }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANHWA")
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try { parseList(get(base)) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try { parseList(get(base)).filter { it.title.contains(query, ignoreCase = true) } } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("img#manga-img")?.attr("src")?.let { absoluteMediaUrl(base, it) } ?: manga.coverUrl,
                description = doc.selectFirst("h1 + p")?.text()?.trim()?.takeIf { it.isNotBlank() },
                contentType = "MANHWA",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            doc.select("ul.list-group a.list-group-item").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = href.substringAfterLast('/').toFloatOrNull() ?: return@mapNotNull null
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = a.text().trim().ifBlank { "Chapter $num" },
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            get(chapter.url).select("img[data-elem=pinchzoomer]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

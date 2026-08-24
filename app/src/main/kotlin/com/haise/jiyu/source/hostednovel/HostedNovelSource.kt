package com.haise.jiyu.source.hostednovel

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
 * HostedNovel (hostednovel.com) - redesignovano na vlastni Laravel/Vue
 * frontend, nikdy nebyla Madara. `?search=` parametr na /novels nefiltruje
 * server-side (jen klientsky JS komponent) - search proto stahne prvni
 * stranku a filtruje nazvy lokalne, stejny vzor jako
 * [com.haise.jiyu.source.hachirumi.HachirumiSource].
 */
@Singleton
class HostedNovelSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hostednovel"
    override val name = "HostedNovel"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://hostednovel.com"

    private fun get(url: String): Document {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        val html = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return Jsoup.parse(html)
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("a[href*=\"/novel/\"]")
            .filterNot { it.attr("href").contains("/chapter-") }
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = a.nextElementSibling()?.takeIf { it.tagName() == "p" }?.text()?.trim()
                    ?.ifBlank { null } ?: return@mapNotNull null
                // Web mezitim odstranil lazy-loading (data-src) uplne - vsechny obrazky
                // maji rovnou "src" - bez fallbacku vychazel cover null pro KAZDY
                // titul (overeno zive, 0/50 melo jeste data-src).
                val img = a.selectFirst("img")
                val cover = img?.attr("data-src")?.ifBlank { img.attr("src") }?.takeIf { it.startsWith("http") }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "NOVEL")
            }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/novels?sort=popular&status=any&page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            parseList(get("$base/novels?sort=name&status=any")).filter { it.title.contains(query, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    private fun ddFor(doc: Document, label: String): String? =
        doc.select("dt").firstOrNull { it.text().trim().trimEnd(':').equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.prose div")?.text()?.trim()?.takeIf { it.isNotBlank() },
                author = ddFor(doc, "Author"),
                status = ddFor(doc, "Status"),
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            doc.select("div#chapters a[href*=\"/chapter-\"]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = Regex("""/chapter-(\d+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                val name = a.text().trim().let { Regex("""Chapter\s+\d+:?.*""").find(it)?.value ?: it }
                    .ifBlank { "Chapter ${num.toInt()}" }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val text = get(chapter.url).selectFirst("div#chapter-content")?.text()?.trim().orEmpty()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

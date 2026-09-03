package com.haise.jiyu.source.mangadoom

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
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * manga-doom.com - katalog/detail/kapitoly plne server-rendered. Kazda stranka
 * kapitoly je samostatny pozadavek (`/{slug}/{chapter}/{page}`, presne jak
 * funguje zivy web), obrazky jsou na CDN podomene s hotlink ochranou (funguje
 * jen s Referer hlavickou na puvodni stranku). Hledani se nepodarilo najit
 * (advanced-search pouziva AJAX autocomplete plugin bez staticky
 * parsovatelneho vysledkoveho endpointu), proto search() vraci prazdny seznam.
 */
@Singleton
class MangaDoomSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangadoom"
    override val name = "MangaDoom"
    override val homepageUrl get() = base
    private val base = "https://manga-doom.com"

    private fun get(url: String, referer: String = base): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", referer)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").trim().ifBlank { return null }
        val cover = a.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
        return SManga(sourceId = id, url = href.removePrefix(base), title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // overereno zive: homepage (`/`) ma stejne razeni jako `/latest-chapters`,
            // zatimco `/popular-manga` ma vlastni odlisne razeni a jinou znacku karet
            // (`div.manga-list-style a[title]` misto `div.manga-cover a[href]`).
            if (filter.sortBy == "latest") {
                val url = if (page <= 1) "$base/" else "$base/?page=$page"
                val doc = Jsoup.parse(get(url))
                doc.select("div.manga-cover a[href]").mapNotNull(::parseCard)
            } else {
                val doc = Jsoup.parse(get("$base/popular-manga?page=$page"))
                doc.select("div.manga-list-style a[title]:has(img)").mapNotNull(::parseCard)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = emptyList()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val fields = doc.select("dl.dl-horizontal dt").associate { dt ->
                dt.text().trim().trimEnd(':', ' ') to dt.nextElementSibling()?.text()?.trim().orEmpty()
            }
            val origin = fields["Type"].orEmpty()
            val contentType = when (origin.lowercase()) {
                "chinese" -> "MANHUA"
                "korean" -> "MANHWA"
                else -> "MANGA"
            }
            manga.copy(
                title = doc.selectFirst("h5.widget-heading")?.text()?.trim() ?: manga.title,
                author = fields["Author"]?.takeIf { it.isNotBlank() && it != "-" },
                artist = fields["Artist"]?.takeIf { it.isNotBlank() && it != "-" },
                status = fields["Status"]?.takeIf { it.isNotBlank() },
                genres = doc.select("dd a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() },
                contentType = contentType,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("ul.chapter-list li a[href]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = href.trimEnd('/').substringAfterLast('/').toFloatOrNull() ?: 0f
                val name = a.selectFirst("span.val")?.text()?.trim() ?: "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = href.removePrefix(base), name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val firstPageUrl = "$base${chapter.url}/1"
            val html = get(firstPageUrl)
            val doc = Jsoup.parse(html)
            val pageCount = doc.select("select option[value*=${chapter.url}/]").count { opt ->
                opt.attr("value").substringAfterLast('/').toIntOrNull() != null
            }.coerceAtLeast(1)
            (1..pageCount).map { p ->
                Page(index = p - 1, url = "$base${chapter.url}/$p")
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(page.url))
            doc.selectFirst("img#chapter_img")?.attr("src")?.takeIf { it.startsWith("http") } ?: page.url
        } catch (_: Exception) { page.url }
    }
}

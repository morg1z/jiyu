package com.haise.jiyu.source.baozimanhua

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

/**
 * Baozi Manhua (包子漫畫) - čínský raw manhua agregátor. Čtecí stránky se
 * fyzicky obsluhují přes zrcadlo twmanga.com, ale wwww.baozimh.com funguje
 * jako přímý alias se stejnou strukturou (`/comic/chapter/{id}/{section}_{slot}.html`),
 * takže se dá číst bez extra přesměrování přes /user/page_direct.
 */
@Singleton
class BaoziManhuaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "baozimanhua"
    override val name = "Baozi Manhua (Raw)"
    override val contentType = "MANHUA"
    override val language = "zh"
    private val base = "https://www.baozimh.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.comics-card").mapNotNull { el ->
            val link = el.selectFirst("a.comics-card__poster") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.startsWith("/comic/") } ?: return@mapNotNull null
            val title = el.selectFirst(".comics-card__title h3")?.text()?.trim()
                ?: link.attr("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val cover = el.selectFirst("amp-img[src]")?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANHUA")
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/classify?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?q=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    private fun meta(doc: org.jsoup.nodes.Document, name: String): String? =
        doc.selectFirst("meta[name=\"$name\"]")?.attr("content")?.takeIf { it.isNotBlank() }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusRaw = meta(doc, "og:novel:status")
            manga.copy(
                title = meta(doc, "og:novel:book_name") ?: manga.title,
                coverUrl = meta(doc, "og:image") ?: manga.coverUrl,
                description = meta(doc, "og:description"),
                author = meta(doc, "og:novel:author"),
                genres = meta(doc, "og:novel:category")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                status = when {
                    statusRaw == null -> null
                    statusRaw.contains("完") -> "Completed"
                    else -> "Ongoing"
                },
                contentType = "MANHUA",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val comicId = manga.url.removePrefix("/comic/").trim('/')
            val items = doc.select("a.comics-chapters__item")
            items.mapIndexedNotNull { i, a ->
                val href = a.attr("href")
                val m = Regex("""section_slot=(\d+)&chapter_slot=(\d+)""").find(href) ?: return@mapIndexedNotNull null
                val (section, slot) = m.destructured
                val name = a.text().trim().ifBlank { "Chapter ${items.size - i}" }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/comic/chapter/$comicId/${section}_$slot.html",
                    name = name,
                    chapterNumber = (items.size - i).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            Regex("""amp-img id="chapter-img-\d+-\d+"[^>]*src="([^"]+)"""")
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .mapIndexed { i, url -> Page(i, url, url) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

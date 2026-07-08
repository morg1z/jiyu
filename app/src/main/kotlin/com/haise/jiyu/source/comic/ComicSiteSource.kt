package com.haise.jiyu.source.comic

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

/**
 * Base class for Western comic reader sites.
 * Override selectors/paths per-site as needed.
 */
abstract class ComicSiteSource(
    override val id: String,
    override val name: String,
    open val base: String,
    protected val client: OkHttpClient,
) : MangaSource {

    override val contentType: String = "COMIC"

    open val popularPath: String = "/"
    open val comicItemSelector: String = "div.eg-box"
    open val comicLinkAttr: String = "href"
    open val comicLinkSelector: String = "a.egitem_header, div.egitem_title a, h2.comic-name a"
    open val comicCoverSelector: String = "img"
    open val chapterItemSelector: String = "ul.chapterlist li a, div.chapter-list a"
    open val pageImgSelector: String = "#chapter_container img, div.reading-content img, div#divImage img, .viewer img"
    open val searchPath: String = "/?s="
    open val searchResultSelector: String = comicItemSelector
    open val descriptionSelector: String = "div.description, div.comic-description, div.summary__content p"
    open val statusSelector: String = "span.status, div.status"
    open val paginatedPopular: Boolean = false
    open val popularPageParam: String = "?page="

    protected suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { it.body!!.string() }
    }

    protected fun String.absoluteUrl(): String =
        if (startsWith("http")) this else "$base${if (startsWith("/")) this else "/$this"}"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val url = if (page > 1 && paginatedPopular) "$base$popularPath$popularPageParam$page"
                  else "$base$popularPath"
        val doc = Jsoup.parse(get(url))
        doc.select(comicItemSelector).mapNotNull { el ->
            val linkEl = el.selectFirst(comicLinkSelector) ?: return@mapNotNull null
            val href = linkEl.attr(comicLinkAttr).ifBlank { return@mapNotNull null }
            val cover = el.selectFirst(comicCoverSelector)
            SManga(
                sourceId = id,
                url = href.removePrefix(base),
                title = linkEl.text().trim().ifBlank { el.text().trim() },
                coverUrl = cover?.let { it.attr("src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-lazy-src") } } },
                contentType = "COMIC",
            )
        }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val url = "$base$searchPath${query.replace(" ", "+")}"
        val doc = Jsoup.parse(get(url))
        doc.select(searchResultSelector).mapNotNull { el ->
            val linkEl = el.selectFirst(comicLinkSelector) ?: return@mapNotNull null
            val href = linkEl.attr(comicLinkAttr).ifBlank { return@mapNotNull null }
            val cover = el.selectFirst(comicCoverSelector)
            SManga(
                sourceId = id,
                url = href.removePrefix(base),
                title = linkEl.text().trim().ifBlank { el.text().trim() },
                coverUrl = cover?.let { it.attr("src").ifBlank { it.attr("data-src") } },
                contentType = "COMIC",
            )
        }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$base${manga.url}"))
        manga.copy(
            description = doc.selectFirst(descriptionSelector)?.text(),
            status = doc.selectFirst(statusSelector)?.text(),
        )
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$base${manga.url}"))
        doc.select(chapterItemSelector).mapIndexed { i, a ->
            val text = a.text().trim()
            val num = Regex("""#?(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: (1000f - i)
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = a.attr("href").removePrefix(base),
                name = text.ifBlank { "Issue $num" },
                chapterNumber = num,
                dateUpload = 0L,
            )
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$base${chapter.url}"))
        doc.select(pageImgSelector).mapIndexed { i, img ->
            val url = img.attr("src").ifBlank { img.attr("data-src").ifBlank { img.attr("data-lazy-src") } }
            Page(i, url)
        }.filter { it.url.isNotBlank() }
    }
}

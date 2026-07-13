package com.haise.jiyu.source.comic

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ReadFreeComicsOnline - americké superhrdinské komiksy (Marvel/DC), jednotlivá
 * čísla jako WordPress příspěvky. Homepage/kategorie používají "ultp" block grid,
 * fulltextové hledání jede přes standardní WP šablonu (article.post) - proto
 * combinovaný comicLinkSelector/comicCoverSelector pro obě varianty.
 */
@Singleton
class ReadFreeComicsOnlineSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "readfreecomicsonline",
    name = "ReadFreeComicsOnline",
    base = "https://readfreecomicsonline.com",
    client = client,
) {
    override val popularPath = "/"
    override val comicItemSelector = "div.ultp-block-item, article.ultp-block-item"
    override val comicLinkSelector = "h3.ultp-block-title a, h2.entry-title a"
    override val comicCoverSelector = "img.wp-post-image, img"
    override val searchPath = "/?s="
    override val searchResultSelector = "article.post"
    override val descriptionSelector = "meta[property=og:description]"
    override val paginatedPopular = true
    override val popularPageParam = "page/"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$base/page/$page/" else base
        Jsoup.parse(get(url)).select(comicItemSelector).mapNotNull { el ->
            val linkEl = el.selectFirst(comicLinkSelector) ?: return@mapNotNull null
            val href = linkEl.attr("href").ifBlank { return@mapNotNull null }
            val cover = el.selectFirst(comicCoverSelector)
            SManga(
                sourceId = id,
                url = href.removePrefix(base),
                title = linkEl.text().trim(),
                coverUrl = cover?.attr("src")?.ifBlank { cover.attr("data-src") },
                contentType = "COMIC",
            )
        }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$base${manga.url}"))
        manga.copy(
            description = doc.selectFirst("meta[property=og:description]")?.attr("content"),
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: manga.coverUrl,
        )
    }

    // Jednotlivá čísla, ne seriálové kapitoly - celý komiks = jedna "kapitola".
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = "Read",
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$base${chapter.url}"))
        doc.select("div.entry-content img").mapIndexed { i, img ->
            val url = img.attr("src").ifBlank { img.attr("data-src") }
            Page(i, url)
        }.filter { it.url.isNotBlank() }
    }
}

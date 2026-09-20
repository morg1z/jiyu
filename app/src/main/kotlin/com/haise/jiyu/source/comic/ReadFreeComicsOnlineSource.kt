package com.haise.jiyu.source.comic

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.util.Locale
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
    override val homepageUrl get() = base
    override val popularPath = "/"
    override val comicItemSelector = "div.ultp-block-item, article.ultp-block-item"
    override val comicLinkSelector = "h3.ultp-block-title a, h2.entry-title a"
    override val comicCoverSelector = "img.wp-post-image, img"
    override val searchPath = "/?s="
    override val searchResultSelector = "article.post"
    override val descriptionSelector = "meta[property=og:description]"
    override val paginatedPopular = true
    override val popularPageParam = "page/"

    // Zdroj (WordPress) taguje kazdy komiks vlastnimi kategoriemi (Batman, Marvel,
    // Zombies, ...) - pouzivaji se tu jako "zanry". Kompletni seznam slugu je
    // dostupny primo z category-sitemap.xml (135 kategorii, zive overeno), takze
    // neni potreba scrapovat/parsovat zadny navigacni widget.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/category-sitemap.xml"))
            val tags = doc.select("loc").mapNotNull { loc ->
                val slug = Regex("""/category/([^/]+)/?$""").find(loc.text())?.groupValues?.get(1)
                    ?.ifBlank { null } ?: return@mapNotNull null
                if (slug == "uncategorized") return@mapNotNull null
                val label = slug.split("-").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Genre archiv ("/category/{slug}/[page/N/]") pouziva standardni WP theme
    // markup (article.post + h2.entry-title a), ne homepage "ultp" block grid -
    // proto parsuje pres jiny selektor nez zbytek getPopular, ale spolecny
    // comicCoverSelector ("img.wp-post-image, img") na obou markupech sedi.
    private fun parseCategoryList(doc: org.jsoup.nodes.Document): List<SManga> =
        doc.select("article.post").mapNotNull { el ->
            val linkEl = el.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val href = linkEl.attr("href").ifBlank { return@mapNotNull null }
            val cover = el.selectFirst(comicCoverSelector)
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = linkEl.text().trim(),
                coverUrl = cover?.attr("src")?.ifBlank { cover.attr("data-src") },
                contentType = "COMIC",
            )
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            val slug = filter.genres.first()
            val url = if (page > 1) "$base/category/$slug/page/$page/" else "$base/category/$slug/"
            return@withContext parseCategoryList(Jsoup.parse(get(url)))
        }
        val url = if (page > 1) "$base/page/$page/" else base
        Jsoup.parse(get(url)).select(comicItemSelector).mapNotNull { el ->
            val linkEl = el.selectFirst(comicLinkSelector) ?: return@mapNotNull null
            val href = linkEl.attr("href").ifBlank { return@mapNotNull null }
            val cover = el.selectFirst(comicCoverSelector)
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = linkEl.text().trim(),
                coverUrl = cover?.attr("src")?.ifBlank { cover.attr("data-src") },
                contentType = "COMIC",
            )
        }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
        val url = "$base$searchPath${java.net.URLEncoder.encode(query.trim(), "UTF-8")}"
        Jsoup.parse(get(url)).select(searchResultSelector).mapNotNull { el ->
            val linkEl = el.selectFirst(comicLinkSelector) ?: return@mapNotNull null
            val href = linkEl.attr("href").ifBlank { return@mapNotNull null }
            val cover = el.selectFirst(comicCoverSelector)
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = linkEl.text().trim().ifBlank { el.text().trim() },
                coverUrl = cover?.attr("src")?.ifBlank { cover.attr("data-src") },
                contentType = "COMIC",
            )
        }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
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
        val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
        doc.select("div.entry-content img").mapIndexed { i, img ->
            val url = img.lazySrc().orEmpty()
            Page(i, url)
        }.filter { it.url.isNotBlank() }
    }
}

package com.haise.jiyu.source.mangadoom

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.source.FilterTag
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
 *
 * Zanrovy filtr: homepage ma v postrannim widgetu (`ul.widget-text-list`)
 * odkazy na vsechny zanrove archivy `/category/{slug}` (overeno zive - kazdy
 * odkaz i ma vlastni `title` atribut s citelnym nazvem). Archivni stranka
 * podporuje `?page=N` a pouziva jiny HTML tvar karet nez popularni/nejnovejsi
 * vypis (`div.col-md-4 a[title]:has(img)` misto `div.manga-cover`/
 * `div.manga-list-style`), ale [parseCard] funguje na oba tvary beze zmeny.
 * Kombinace vice zanru najednou web nepodporuje (jeden archiv = jeden zanr),
 * proto se pouziva jen `filter.genres.first()` - stejny vzor jako Madara.
 */
@Singleton
class MangaDoomSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangadoom"
    override val name = "MangaDoom"
    override val homepageUrl get() = base
    private val base = "https://manga-doom.com"

    private fun get(url: String, referer: String = base): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", referer)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").trim().ifBlank { return null }
        val cover = a.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
        return SManga(sourceId = id, url = toSourcePath(base, href), title = title, coverUrl = cover, contentType = "MANGA")
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/"))
            val tags = doc.select("ul.widget-text-list a[href^=/category/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/category/").trim().ifBlank { return@mapNotNull null }
                val label = a.attr("title").trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreDoc(slug: String, page: Int): org.jsoup.nodes.Document =
        Jsoup.parse(get("$base/category/$slug?page=$page"))

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext genreDoc(filter.genres.first(), page)
                    .select("div.col-md-4 a[title]:has(img)").mapNotNull(::parseCard)
            }
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
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isEmpty()) return@withContext emptyList()
        try {
            genreDoc(filter.genres.first(), page).select("div.col-md-4 a[title]:has(img)").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
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
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("ul.chapter-list li a[href]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = href.trimEnd('/').substringAfterLast('/').toFloatOrNull() ?: 0f
                val name = a.selectFirst("span.val")?.text()?.trim() ?: "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = toSourcePath(base, href), name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
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
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(page.url))
            doc.selectFirst("img#chapter_img")?.attr("src")?.let { absoluteMediaUrl(base, it) } ?: page.url
        } catch (e: Exception) { e.rethrowIfControl(); page.url }
    }
}

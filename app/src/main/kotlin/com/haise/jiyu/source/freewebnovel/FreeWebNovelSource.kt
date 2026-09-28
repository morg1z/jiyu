package com.haise.jiyu.source.freewebnovel

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.novelText

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeWebNovelSource @Inject constructor(client: OkHttpClient) : FreeWebNovelEngine(
    client = client,
    id = "freewebnovel",
    name = "FreeWebNovel",
    base = "https://freewebnovel.com",
    inGlobalSearch = true,
)

/**
 * Sdílený engine pro weby na "FreeWebNovel" šabloně (freewebnovel.com, novgo.net):
 * karty `div.li` s `h3.tit a`, žánry `/genre/{Name}`, seznam kapitol `ul#idData li a.con`.
 * Novgo má jiné cesty výpisů (`/most-popular`, `?page=N`) a JS-only hledání -
 * proto jsou cesty a chování parametrizované.
 */
open class FreeWebNovelEngine(
    private val client: OkHttpClient,
    override val id: String,
    override val name: String,
    private val base: String,
    private val inGlobalSearch: Boolean = false,
    /** Cesta populárního výpisu (fwn "sort/most-popular", novgo "most-popular"). */
    private val popularPath: String = "sort/most-popular",
    private val latestPath: String = "sort/latest-release",
    /** Paginace: "path" = `/{cesta}/{N}` (fwn), "query" = `/{cesta}?page=N` (novgo). */
    private val pageStyle: String = "path",
    /** Web bez server-side hledání (novgo - jen JS widget) - search vrací prázdné. */
    private val searchSupported: Boolean = true,
    /** Selektor textu kapitoly (fwn "div#article", novgo "div#chapter-content"). */
    private val contentSelector: String = "div#article",
) : MangaSource {

    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    override val includeInGlobalSearch get() = inGlobalSearch
    override val availableSorts get() = setOf("popular", "latest")

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("div.li").mapNotNull { item ->
            val link = item.selectFirst("h3.tit a") ?: return@mapNotNull null
            val cover = item.selectFirst("div.pic img")?.attr("src")
            SManga(
                sourceId = id,
                url = link.attr("href"),
                title = link.text().trim(),
                coverUrl = cover?.let { resolveSourceUrl(base, it) },
                contentType = "NOVEL",
            )
        }

    // Homepage/kategorie odkazuji primo na "/genre/{Slug}" archivni stranky (WP-style
    // slug s "+" mistu mezer) - stejna struktura seznamu (div.li) jako obycejny
    // vypis, jen jina sada titulu (overeno zive: Horror str.1 vs str.2 - jine tituly).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get(base))
            val tags = doc.select("a[href^=/genre/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/genre/").trim('/').ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }.sortedBy { it.label }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun paged(path: String, page: Int): String = when (pageStyle) {
        "query" -> "$base/$path" + if (page > 1) "?page=$page" else ""
        else -> "$base/$path" + if (page > 1) "/$page" else ""
    }

    private fun genreUrl(slug: String, page: Int) = paged("genre/$slug", page)

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Genre archiv nema vlastni razeni (jen jeden zanr na pozadavek, podobne
            // jako u Madara genre archivu) - pri vybranem tagu se filter.sortBy ignoruje.
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            val path = if (filter.sortBy == "latest") latestPath else popularPath
            parseListing(Jsoup.parse(get(paged(path, page))))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            if (!searchSupported) return@withContext emptyList()
            // "/search?searchkey=" je JS-only shell (vysledky se dokresluji az
            // za behu) - server-rendered vysledky se stejnym "div.li" markupem
            // vraci az advanced search GET "/search-adv?keyword=&apply=1".
            val q = URLEncoder.encode(query, "UTF-8")
            parseListing(Jsoup.parse(get("$base/search-adv?keyword=$q&apply=1")))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                description = doc.selectFirst("meta[property=og:description]")?.attr("content"),
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("ul#idData li a.con").mapIndexed { i, a ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = a.attr("href"),
                    name = a.text().trim().ifBlank { "Chapter ${i + 1}" },
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            val content = doc.selectFirst(contentSelector) ?: return@withContext emptyList()
            content.select("script, style, iframe, ins, .adsbygoogle").remove()
            val text = content.novelText().ifBlank { content.text().trim() }
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

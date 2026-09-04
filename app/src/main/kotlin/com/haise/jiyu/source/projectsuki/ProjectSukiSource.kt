package com.haise.jiyu.source.projectsuki

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * projectsuki.com - scanlator hub, plne server-rendered vcetne cteni. Reader
 * nema zadny "pocet stranek" indikator primo na strance - resenim je pozadat
 * o velmi vysoke cislo stranky (`/9999`), web na to odpovi 302 redirectem na
 * POSLEDNI skutecnou stranku a z Location URL se precte skutecny pocet stranek.
 */
@Singleton
class ProjectSukiSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "projectsuki"
    override val name = "Project Suki"
    override val homepageUrl get() = base
    private val base = "https://projectsuki.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun resolvedUrl(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.request.url.toString() }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val img = a.selectFirst("img.browse") ?: return null
        val title = a.attr("aria-label").trim().ifBlank { img.attr("alt").trim() }.ifBlank { return null }
        val cover = img.attr("src").trim().takeIf { it.isNotBlank() }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    // "/genre/{slug}" jsou archivni stranky jednotlivych zanru (stejna karetni
    // struktura "a:has(img.browse)" jako browse) - web nema zadnou dedikovanou
    // stranku vypisujici VSECHNY zanry ("/genres", "/genre", "/tags" vraci 404),
    // takze seznam je overeny rucne (kazdy slug 200 vs bogus slug 404 - overeno
    // zive) misto zive natazeni. Genre archiv nema vlastni razeni - pri vybranem
    // tagu se filter.sortBy ignoruje, kombinace vice zanru neni podporovana.
    override val supportsTagFilter: Boolean get() = true

    private val staticTags = listOf(
        "action", "adventure", "comedy", "doujinshi", "drama", "ecchi", "fantasy",
        "harem", "historical", "horror", "isekai", "josei", "mecha", "mystery",
        "psychological", "romance", "seinen", "shoujo", "shounen", "smut", "sports",
        "supernatural", "tragedy", "yaoi", "yuri",
    ).map { FilterTag(id = it, label = it.split('-').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }) }

    override suspend fun getAvailableTags(): List<FilterTag> = staticTags

    private fun genreUrl(slug: String, page: Int) =
        if (page <= 1) "$base/genre/$slug" else "$base/genre/$slug/$page"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                val doc = Jsoup.parse(get(genreUrl(filter.genres.first(), page)))
                return@withContext doc.select("a:has(img.browse)").mapNotNull(::parseCard)
            }
            val doc = Jsoup.parse(get("$base/browse/$page"))
            doc.select("a:has(img.browse)").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val doc = Jsoup.parse(get(genreUrl(filter.genres.first(), page)))
                doc.select("a:has(img.browse)").mapNotNull(::parseCard)
            } catch (_: Exception) { emptyList() }
        }
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?q=$q"))
            doc.select("a:has(img.browse)").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val fields = doc.select("div.strong").associate { label ->
                label.text().trim().trimEnd(':') to (label.nextElementSibling()?.text()?.trim().orEmpty())
            }
            manga.copy(
                author = fields["Author"]?.takeIf { it.isNotBlank() },
                artist = fields["Artist"]?.takeIf { it.isNotBlank() },
                status = fields["Status"]?.takeIf { it.isNotBlank() },
                genres = doc.select("div[itemprop=genre] a").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("a[href^=/read/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href.trimEnd('/').substringBeforeLast('/'), name = name, chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val lastPageUrl = resolvedUrl("$base${chapter.url}/9999")
            val lastPage = lastPageUrl.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: return@withContext emptyList()
            (1..lastPage).map { p ->
                Page(index = p - 1, url = "$base${chapter.url}/$p")
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(page.url))
            doc.selectFirst("img.img-fluid.center-block[src*=/images/gallery/]")?.attr("src")?.takeIf { it.startsWith("http") }
                ?: page.url
        } catch (_: Exception) { page.url }
    }
}

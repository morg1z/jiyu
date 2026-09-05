package com.haise.jiyu.source.manhwa210

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
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bespoke Laravel/Livewire šablona (ne Madara/Mangathemesia - potvrzeno živě přes
 * `wire:id`/`wire:click` atributy). Katalog i žánrový archiv sdílí stejnou kartu
 * (`div.manga-vertical`), obálka je jako CSS `background-image` na `div.cover`, ne
 * `<img src>`. Hlavní vypis jede pres "/list?sort={sort}&page=N" ("-views" pro
 * populární, "-updated_at" pro nejnovější - overeno zive, ze davaji odlisne sady),
 * zanrovy archiv pres "/genre/{slug}?page=N" (overeno zive: stranka 1 vs 2 i genre
 * vs genre odlisne, 60 karet na stranku).
 *
 * "/search?q=..." parametr tise ignoruje (overeno zive - dva ruzne/nesmyslne
 * dotazy vraci bajtove identickou sadu 60 karet), takze hledani se resi lokalne
 * nad stazenym vypisem, stejny vzor jako u jinych zdroju bez funkcniho serverove
 * ho hledani.
 *
 * Ctecí stránka kapitoly má obrázky přímo v `<img class="lazy" src="...">` -
 * žádný lazy-load `data-src`/token navíc, `src` je rovnou plná CDN URL.
 */
@Singleton
class Manhwa210Source @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "manhwa210"
    override val name = "Manhwa210"
    override val contentType = "MANHWA"
    override val homepageUrl get() = base
    override val supportsTagFilter = true
    private val base = "https://manhwa210.com"

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            val doc = Jsoup.parse(get("$base/"))
            doc.select("a[href^=/genre/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/genre/").trim('/').ifBlank { return@mapNotNull null }
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
        } catch (_: Exception) { emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private val bgImageRegex = Regex("""url\(['"]?([^'")]+)['"]?\)""")

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.manga-vertical").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = card.selectFirst("div.p-2 a, a.text-ellipsis")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val bgStyle = card.selectFirst("div.cover")?.attr("style").orEmpty()
            val cover = bgImageRegex.find(bgStyle)?.groupValues?.get(1)
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }

    private fun genreUrl(slug: String, page: Int) = "$base/genre/$slug?page=$page"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            val sort = if (filter.sortBy == "latest") "-updated_at" else "-views"
            parseList(Jsoup.parse(get("$base/list?sort=$sort&page=$page")))
        } catch (_: Exception) { emptyList() }
    }

    // Server-side "/search" parametr nefunguje (viz komentar u tridy) - hledani proto
    // stahne prvnich par stranek vypisu a filtruje podle titulku lokalne, stejny vzor
    // jako u jinych zdroju bez funkcniho hledani.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(Jsoup.parse(get(genreUrl(filter.genres.first(), page)))) }
            catch (_: Exception) { emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            val q = query.trim()
            (1..3).flatMap { p ->
                try { parseList(Jsoup.parse(get("$base/list?sort=-views&page=$p"))) } catch (_: Exception) { emptyList() }
            }.distinctBy { it.url }.filter { it.title.contains(q, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val statusText = doc.selectFirst("span:contains(Status:)")?.parent()?.text()
                ?.substringAfter("Status:")?.trim()
            manga.copy(
                title = doc.selectFirst("span.text-ellipsis.font-semibold")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("span:contains(Alt name:)")?.parent()?.text()
                    ?.substringAfter("Alt name:")?.trim()?.ifBlank { null },
                genres = doc.select("span:contains(Genres:)").firstOrNull()?.parent()
                    ?.select("a[href^=/genre/]")?.map { it.text().trim() } ?: manga.genres,
                author = doc.selectFirst("span:contains(Artist:)")?.parent()?.selectFirst("a")?.text()?.trim()
                    ?.takeIf { !it.equals("Updating", ignoreCase = true) },
                status = when {
                    statusText.equals("Ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.equals("Completed", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("a[href^=${manga.url}/chapter-]").mapIndexedNotNull { i, a ->
                val href = a.attr("href")
                val name = a.selectFirst("span.text-ellipsis")?.text()?.trim()?.ifBlank { null } ?: a.text().trim()
                val num = Regex("""chapter-([\d.]+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href,
                    name = name.ifBlank { "Chapter $num" }, chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img.lazy[src]").mapIndexedNotNull { i, img ->
                val src = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, src, src)
            }
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.doujiva

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Doujiva (doujiva.com) - anglicka hentai doujinshi galerie. Na rozdil od
 * vetsiny podobnych webu (viz AsmHentaiSource/EHentaiSource) NEPOTREBUJE
 * getPageList zadny dalsi "/read/..." pozadavek - obrazky kapitoly jsou uz
 * primo na CDN pod predvidatelnou cestou
 * "https://cdn.doujiva.com/{slug}/chapter-1/{NNN}.webp" (NNN = trojciselne
 * cislo stranky s nulami zleva), ktera se da odvodit primo ze slugu v URL a
 * poctu stranek zjisteneho z "{NNN}.thumb.webp" nahledu na detailu - zadny
 * hash ani token neni treba. Web technicky podporuje vice kapitol na titul
 * (`/manga/{slug}/read/{chapterId}`), ale zatim nebyl narazen zadny vicedilny
 * titul - proto (stejne jako u NhentaiSource) cela galerie = jedna kapitola.
 */
@Singleton
class DoujivaSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "doujiva"
    override val name = "Doujiva"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://doujiva.com"

    // "/tags" ma pres 860 polozek mixujicich zanry/postavy/serie (SKIPPED-TOO-LARGE
    // uroven) - misto toho pouzivame hrusi kuratorovanou sadu z vlastniho webu
    // ("Popular Tags" panel na strankach /tags i homepage, overeno zive shodne na
    // obou), pro kterou existuje dedikovana archivni cesta "/tag/{slug}"
    // (overeno zive - jina sada titulu nez homepage, podporuje i "?page=N").
    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = listOf(
        FilterTag("big-breasts", "Big Breasts"),
        FilterTag("romance", "Romance"),
        FilterTag("comedy", "Comedy"),
        FilterTag("vanilla", "Vanilla"),
        FilterTag("full-color", "Full Color"),
        FilterTag("schoolgirl-uniform", "Schoolgirl Uniform"),
        FilterTag("sole-female", "Sole Female"),
        FilterTag("sole-male", "Sole Male"),
        FilterTag("stockings", "Stockings"),
        FilterTag("glasses", "Glasses"),
        FilterTag("uncensored", "Uncensored"),
    )

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("a[href^=/manga/]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.selectFirst("p")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val cover = a.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }.distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val genre = filter.genres.firstOrNull()
            if (genre != null) {
                return@withContext try { parseGalleryList(fetchDocument("$base/tag/$genre?page=$page")) }
                catch (_: Exception) { emptyList() }
            }
            // Bez parametru web řadí od nejnovějšího nahrání - "Populární" tab proto
            // potřebuje explicitní "?sort=popular-all" (ověřeno živě, jinak vrací úplně
            // jinou sadu titulů než skutečně populární výběr).
            val sort = if (filter.sortBy == "popular") "&sort=popular-all" else ""
            try { parseGalleryList(fetchDocument("$base/?page=$page$sort")) }
            catch (_: Exception) { emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            // "/search" ani "/tag/{slug}" neumi kombinovat fulltext dotaz s tag filtrem
            // (overeno zive - "?q=...&tag=..." i "/tag/{slug}?q=..." vraci identickou
            // sadu jako bez toho druheho parametru), takze stejne jako MadaraSource
            // (Vzor B) ma zvoleny zanr prednost pred textovym dotazem.
            if (filter.genres.isNotEmpty() || query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/search?q=$q&page=$page"))
            } catch (_: Exception) { emptyList() }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val artist = doc.selectFirst("a[href^=/artist/] span")?.text()?.trim()?.ifBlank { null }
            val genres = doc.select("a[href^=/tag/]").mapNotNull { it.text().trim().ifBlank { null } }
            manga.copy(title = title, artist = artist, genres = genres)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    // Nahledy jsou "chapter-1/{NNN}.thumb.webp", plne obrazky stejna cesta
    // bez ".thumb" - viz komentar u tridy.
    private val thumbRegex = Regex("""(https://cdn\.doujiva\.com/[^"'\s]+/chapter-\d+)/(\d+)\.thumb\.webp""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(chapter.url)
            thumbRegex.findAll(html)
                .map { it.groupValues[1] to it.groupValues[2] }
                .distinctBy { it.second }
                .sortedBy { it.second.toInt() }
                .mapIndexed { i, (dir, num) ->
                    val full = "$dir/$num.webp"
                    Page(index = i, url = full, imageUrl = full)
                }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

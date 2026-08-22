package com.haise.jiyu.source.hentaizap

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
 * HentaiZap (hentaizap.com) - anglicka hentai doujinshi/manga galerie. Detail
 * strana ("/gallery/{id}/") uz obsahuje nahledy vsech stranek (CDN cesta
 * ".../{n}t.jpg"), ale to jsou jen male nahledy - plne rozliseni je na STEJNE
 * ceste bez "t" a s priponou ".webp" misto ".jpg" (overeno na "/g/{id}/{n}/"
 * readeru). Diky tomu getPageList potrebuje jen jeden pozadavek na detail -
 * odvodi si CDN adresar z URL obalky galerie ("cover.jpg") a vygeneruje plne
 * URL vsech stranek bez dalsich requestu.
 */
@Singleton
class HentaiZapSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "hentaizap"
    override val name = "HentaiZap"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://hentaizap.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("article.hz-gallery-card").mapNotNull { card ->
            val a = card.selectFirst("h2.hz-gallery-card__title a") ?: return@mapNotNull null
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.text().trim().ifBlank { return@mapNotNull null }
            val img = card.selectFirst("div.hz-gallery-card__media img")
            val cover = img?.attr("data-src")?.trim()?.ifBlank { img.attr("src").trim() }?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }.distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            try { parseGalleryList(fetchDocument("$base/popular/?page=$page")) }
            catch (_: Exception) { emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/search/?key=$q&page=$page"))
            } catch (_: Exception) { emptyList() }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            // Scopovano na div.hz-gallery-metadata - stranka ma i postranni "popular right now"
            // widget se stejnymi "a[href^=/tag/]" odkazy na CIZI galerie, bez scope by se
            // genres/artist naplnily nahodnymi tagy z jine galerie misto teto.
            val metadata = doc.selectFirst("div.hz-gallery-metadata")
            val artist = metadata?.selectFirst("a[href^=/artist/] span.hz-gallery-tag__name")
                ?.text()?.trim()?.ifBlank { null }
            val genres = metadata?.select("a[href^=/tag/] span.hz-gallery-tag__name")
                ?.mapNotNull { it.text().trim().ifBlank { null } } ?: emptyList()
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

    private val coverRegex = Regex("""(https?://[^"'\s]+/)cover\.jpg""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(chapter.url)
            val dir = coverRegex.find(html)?.groupValues?.get(1) ?: return@withContext emptyList()
            val thumbRegex = Regex(Regex.escape(dir) + """(\d+)t\.jpg""")
            thumbRegex.findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .sortedBy { it.toInt() }
                .mapIndexed { i, num ->
                    val full = "$dir$num.webp"
                    Page(index = i, url = full, imageUrl = full)
                }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

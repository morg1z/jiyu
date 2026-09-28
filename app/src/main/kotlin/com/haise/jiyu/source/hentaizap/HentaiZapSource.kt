package com.haise.jiyu.source.hentaizap

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
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
    override val supportsTagFilter: Boolean get() = true

    private val base = "https://hentaizap.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
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

    // Tagovy index "/tags/popular/?page=N" (50 tagu/strana razeno podle poctu
    // galerii; plny abecedni index "/tags/" ma 338 stranek = ~17k tagu, coz je
    // pro picker neprimerane - proto se nabizi top ~200 nejpouzivanejsich).
    // Nazev tagu je ve span.hz-legacy-taxonomy-item__name (vedlejsi span nese
    // pocet galerii, proto a.text() nestaci). Archiv "/tag/{slug}/?page=N".
    // Kombinace vice tagu web nepodporuje - pri vice vybranych se pouzije prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            val out = mutableListOf<FilterTag>()
            var p = 1
            while (p <= 4) {
                val pageTags = fetchDocument("$base/tags/popular/?page=$p").select("a[href^=/tag/]").mapNotNull { a ->
                    val slug = a.attr("href").removePrefix("/tag/").trim('/').ifBlank { return@mapNotNull null }
                    val label = (a.selectFirst("span.hz-legacy-taxonomy-item__name, span.hz-gallery-tag__name")?.text() ?: a.text())
                        .trim().ifBlank { return@mapNotNull null }
                    FilterTag(id = slug, label = label)
                }
                if (pageTags.isEmpty()) break
                out += pageTags
                p++
            }
            out.distinctBy { it.id }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (filter.genres.isNotEmpty()) {
                return@withContext try {
                    parseGalleryList(fetchDocument("$base/tag/${filter.genres.first()}/?page=$page"))
                } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
            }
            // Overeno zive: "/" je vlastni "nejnovejsi" feed webu, "/popular/" je
            // samostatny popularitni zebricek - dva skutecne odlisne seznamy.
            val path = if (filter.sortBy == "latest") "/?page=$page" else "/popular/?page=$page"
            try { parseGalleryList(fetchDocument("$base$path")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/search/?key=$q&page=$page"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
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
        } catch (e: Exception) { e.rethrowIfControl(); manga }
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

    private val pagesRegex = Regex("""hz-gallery-pages">\s*Pages:\s*(\d+)""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(chapter.url)
            val dir = coverRegex.find(html)?.groupValues?.get(1) ?: return@withContext emptyList()
            // "Pages: N" v p.hz-gallery-pages je autoritativni soucet - "{n}t.jpg"
            // thumby se renderuji jen pro prvnich ~10 stran (audit 2026-11:
            // Pages: 131 pri 10 thumbech), pocitat je stranky usekne.
            val count = pagesRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex(Regex.escape(dir) + """(\d+)t\.jpg""").findAll(html)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                ?: return@withContext emptyList()
            (1..count).map { n ->
                val full = "$dir$n.webp"
                Page(index = n - 1, url = full, imageUrl = full)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

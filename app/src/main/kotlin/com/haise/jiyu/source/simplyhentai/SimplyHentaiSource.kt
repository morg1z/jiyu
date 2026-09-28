package com.haise.jiyu.source.simplyhentai

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.rethrowIfControl
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * simply-hentai.com - anglicka hentai doujin/manga galerie. Web drive nesel
 * kompletni data v `__NEXT_DATA__` JSON (Next.js), od 2026-11 ale bezi na
 * server-renderovanem frontendu ("web2"): vypis = `article.manga-container`
 * karty, detail = `h1` + `pages-overview` nahledy.
 *
 * Stranky galerie: plne URL obrazku zije na CDN `images.sh-cdn.com` pod nazvem
 * `{hash}.jpg` - z nahledu `small_thumb_{hash}.jpg` staci oriznout prefix.
 * Detail ukazuje jen prvnich ~12 nahledu; cely pocet nese odkaz
 * `all-pages-link` ("View all N images") a `/page/{id}` identifikatory jsou
 * sekvcencni (overeno zive). `getPageList` proto nejdriv zkusi primou stranku
 * `/all-pages` (v appce ji projde Cloudflare interceptor pres WebView), jako
 * fallback vrati virtualni `/page/{id}` adresy, ktere lazy rozparsuje
 * [getImageUrl].
 *
 * POZOR: `/all-pages`, `/page/N` a `/search/` jsou za CF vyzvou - z JVM bez
 * WebView vratou "Just a moment", v appce je vyresi CloudflareInterceptor.
 *
 * Skutecne fulltextove hledani neexistuje - `/search/{slug}` presmeruje primo
 * na galerii jen pri presne shode slug; search() to vraci jako best-effort
 * jednu galerii, jinak prazdny seznam.
 */
@Singleton
class SimplyHentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "simplyhentai"
    override val name = "Simply Hentai"
    override val supportsSortOrder: Boolean get() = false
    // Tagy na webu existuji, ale browsable schema tag URL nebylo overitelne
    // (403 i s realistickym UA) - radsi picker schovame nez shipnout rozbitou
    // implementaci.
    override val supportsTagFilter: Boolean get() = false
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://www.simply-hentai.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(html: String): List<SManga> =
        Jsoup.parse(html, base).select("article.manga-container").mapNotNull { card ->
            val a = card.selectFirst("a.content-link") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("abs:href") }
            if (href.isBlank()) return@mapNotNull null
            val title = card.selectFirst("h3.title a")?.text()?.ifBlank { null }
                ?: card.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: return@mapNotNull null
            // Nahled "small_thumb_{hash}" -> plne rozliseni "{hash}" (stejna cesta
            // na CDN) - cover se pouziva i v hlavicce detailu, kde thumb mizi.
            val cover = card.selectFirst(".cover-slot img")?.absUrl("src")?.ifBlank { null }
                ?.replace("small_thumb_", "")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            try { parseListing(fetchHtml("$base/2-mangas/sort-most-viewed?page=$page")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun slugify(text: String): String = text.trim().lowercase()
        .replace(Regex("""\s+"""), "-")
        .replace(Regex("""[^a-z0-9-]"""), "")

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            if (page > 1) return@withContext emptyList()
            try {
                val doc = Jsoup.parse(fetchHtml("$base/search/${slugify(query)}"), base)
                // `/search/{slug}` pri presne shode presmeruje primo na galerii
                // (pozna se podle `pages-overview`), jinak muze vratit karty.
                if (doc.selectFirst("[data-testid=pages-overview]") != null) {
                    mangaFromDetailDoc(doc)?.let { listOf(it) } ?: emptyList()
                } else {
                    doc.select("article.manga-container").takeIf { it.isNotEmpty() }
                        ?.let { parseListing(doc.html()) }
                        ?: emptyList()
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun mangaFromDetailDoc(doc: Document): SManga? {
        val title = doc.selectFirst("h1")?.text()?.ifBlank { null } ?: return null
        val cover = doc.selectFirst("[data-testid=cover-link] img")?.absUrl("src")?.ifBlank { null }
            ?.replace("small_thumb_", "")
        // Web zadny canonical link nema - og:url nese relativni cestu galerie
        // ("/{series}/{slug}"), kterou absUrl spoji proti `base`.
        val url = doc.selectFirst("meta[property=og:url]")?.absUrl("content")?.ifBlank { null }
            ?: return null
        return SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(fetchHtml(manga.url), base)
            val title = doc.selectFirst("h1")?.text()?.ifBlank { null }
            val cover = doc.selectFirst("[data-testid=cover-link] img")?.absUrl("src")?.ifBlank { null }
                ?.replace("small_thumb_", "")
            manga.copy(
                title = title ?: manga.title,
                coverUrl = cover ?: manga.coverUrl,
            )
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

    private val pageIdRegex = Regex("""/page/(\d+)""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            // Primarne "/all-pages" - v appce ji projde Cloudflare interceptor a
            // vrati vsechny plne obrazky najednou (bez per-page dotazu).
            runCatching {
                Jsoup.parse(fetchHtml("${chapter.url}/all-pages"), base)
                    .select("img")
                    .mapNotNull { it.lazySrc() }
                    .filter { "sh-cdn.com" in it }
                    .map { it.replace("small_thumb_", "").replace("thumb_", "") }
                    .distinct()
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { urls ->
                return@withContext urls.mapIndexed { i, u -> Page(i, u, u) }
            }
            // Fallback (JVM bez WebView nebo kdyz /all-pages layout zmeni): detail
            // nese "View all N images" + prvnich ~12 sekvcencnich /page/{id}
            // odkazu - z nich se da spocitat cely rozsah id. Virtualni adresy
            // rozparsuje az getImageUrl (v appce CF interceptor vyresi vyzvu).
            val doc = Jsoup.parse(fetchHtml(chapter.url), base)
            val firstId = doc.select("a[href*=/page/]")
                .mapNotNull { pageIdRegex.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
                .minOrNull() ?: return@withContext emptyList()
            val total = doc.selectFirst("[data-testid=all-pages-link], [data-testid=actions-all-pages-link]")
                ?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val count = total ?: doc.select("a[href*=/page/]").size
            (0 until count).map { i ->
                Page(i, "${chapter.url}/page/${firstId + i}")
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String {
        // Virtualni "/page/{id}" adresa - lazy nacte stranku a vytahne plne CDN URL.
        if (page.imageUrl != null) return page.imageUrl!!
        if (!page.url.contains("/page/")) return page.url
        return withContext(Dispatchers.IO) {
            try {
                Jsoup.parse(fetchHtml(page.url), base)
                    .select("img")
                    .mapNotNull { it.lazySrc() }
                    .filter { "sh-cdn.com" in it }
                    .firstOrNull { "thumb" !in it }
                    ?: page.url
            } catch (e: Exception) { e.rethrowIfControl(); page.url }
        }
    }
}

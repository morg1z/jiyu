package com.haise.jiyu.source.imhentai

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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * imhentai.xxx - nhentai-styl doujinshi galerie (stejny "thumb/caption" engine
 * jako hentaifox.com a hentaizap.com, ale s vlastnimi cestami: listing pouziva
 * "?page=N" query param, hledani "/search/?key=", reader "/view/{id}/{n}/").
 * Cela galerie = jedna "kapitola" (viz NhentaiSource). Presna pripona plne
 * stranky (jpg/webp/png) se lisi stranku od stranky, proto se resolvuje az
 * lenive pres getImageUrl z reader stranky (img#gimg).
 */
@Singleton
class ImHentaiSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "imhentai"
    override val name = "IMHentai"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://imhentai.xxx"

    // Plny seznam tagu na /tags/ ma pres 16 000 polozek (SKIPPED-TOO-LARGE by
    // se hodilo na tag urovni) - misto toho pouzivame vlastni hrubsi taxonomii
    // webu "Categories" (viz /advsearch/ formular: hidden inputy m/d/w/i/a/g),
    // ktera ma jen 6 polozek a existuje pro ni i dedikovana archivni cesta
    // "/category/{slug}/" (overeno zive - jina sada galerii nez /popular/).
    override val supportsTagFilter: Boolean get() = true

    private val categoryParams = linkedMapOf(
        "doujinshi" to "d",
        "manga" to "m",
        "western" to "w",
        "imageset" to "i",
        "artistcg" to "a",
        "gamecg" to "g",
    )

    override suspend fun getAvailableTags(): List<FilterTag> = listOf(
        FilterTag("doujinshi", "Doujinshi"),
        FilterTag("manga", "Manga"),
        FilterTag("western", "Western"),
        FilterTag("imageset", "Image Set"),
        FilterTag("artistcg", "Artist CG"),
        FilterTag("gamecg", "Game CG"),
    )

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDoc(url: String): Document = Jsoup.parse(get(url), url)

    private fun parseThumb(thumb: Element): SManga? {
        val a = thumb.selectFirst("div.thumbnail a[href^=/gallery/]") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = thumb.selectFirst("h2.gallery_title a")?.text()?.trim()?.ifBlank { null }
            ?: a.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            // "/popular/" nema h2.gallery_title (jen div.caption > a s textem primo) -
            // overeno zive, bez tohoto fallbacku by parseThumb vratil null pro VSECHNY
            // polozky na te strance.
            ?: thumb.selectFirst("div.caption a")?.text()?.trim()?.ifBlank { null }
            ?: return null
        val cover = a.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } }
            ?.trim()?.ifBlank { null }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.thumb").mapNotNull(::parseThumb).distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (filter.genres.isNotEmpty()) {
                val slug = filter.genres.first()
                if (page <= 1) "$base/category/$slug/" else "$base/category/$slug/?page=$page"
            } else if (filter.sortBy == "latest") {
                // Overeno zive: homepage je razena podle nejnovejsich pridanych galerii
                // (sestupne ID), zatimco "/popular/" ma vlastni zebricek popularity - jine
                // ID poradi uz od druhe polozky. Obe podporuji strankovani pres "?page=N".
                if (page <= 1) "$base/" else "$base/?page=$page"
            } else {
                if (page <= 1) "$base/popular/" else "$base/popular/?page=$page"
            }
            parseList(fetchDoc(url))
        } catch (_: Exception) { emptyList() }
    }

    // "/search/" bere kategorie jen jako explicitni sadu 0/1 pro VSECH sest
    // hidden inputu - poslani jen jednoho "w=1" bez zbytku vyfiltrovane
    // vysledky nezmeni (overeno zive), musi se poslat cely set.
    private fun categoryQuery(slug: String): String {
        val target = categoryParams[slug] ?: return ""
        return categoryParams.values.joinToString("") { p -> "&$p=${if (p == target) 1 else 0}" }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val catParam = filter.genres.firstOrNull()?.let(::categoryQuery) ?: ""
            parseList(fetchDoc("$base/search/?key=$q&page=$page$catParam"))
        } catch (_: Exception) { emptyList() }
    }

    // Kazda <li> v ul.galleries_info ma label ve span.tags_text ("Tags:", "Artists:", ...)
    // a hodnoty jako a.tag odkazy za nim - stejne pole se pouziva pro vsechny kategorie.
    private fun parseInfoGroup(doc: Document, label: String): List<String> =
        doc.select("ul.galleries_info li").firstOrNull { it.selectFirst("span.tags_text")?.text()?.trim() == label }
            ?.select("a.tag")?.map { it.ownText().trim() }?.filter { it.isNotBlank() } ?: emptyList()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc("$base${manga.url}")
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val artists = parseInfoGroup(doc, "Artists:")
            val tags = parseInfoGroup(doc, "Tags:")
            val categories = parseInfoGroup(doc, "Category:")
            val pagesText = doc.selectFirst("li.pages")?.text()?.trim()

            manga.copy(
                title = title,
                author = artists.firstOrNull(),
                artist = artists.firstOrNull(),
                genres = tags,
                description = pagesText,
                status = categories.firstOrNull(),
            )
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc("$base${chapter.url}")
            val galleryId = chapter.url.trim('/').substringAfterLast('/')
            val count = doc.select("div.gthumb").size.takeIf { it > 0 }
                ?: doc.selectFirst("li.pages")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                ?: return@withContext emptyList()
            (1..count).map { n -> Page(index = n - 1, url = "$base/view/$galleryId/$n/") }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(page.url)
            doc.selectFirst("img#gimg")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } }
                ?.trim()?.takeIf { it.startsWith("http") } ?: page.url
        } catch (_: Exception) { page.url }
    }
}

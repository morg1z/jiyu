package com.haise.jiyu.source.hentaifox

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
 * hentaifox.com - nhentai-styl doujinshi galerie (vlastni ExHentai-like sablona,
 * stejny engine jako imhentai.xxx / hentaizap.com, ale s vlastnimi URL cestami).
 * Cela galerie = jedna "kapitola" (viz NhentaiSource). Detail stranka nema plne
 * rozliseni obrazku primo v HTML, jen thumbnaily (".../{n}t.jpg") a pocet stran -
 * plna URL se ziska az z reader stranky "/g/{id}/{n}/" (tag #gimg, atribut
 * data-src). Pripona souboru (jpg/webp/png) se LISI stranku od stranky, proto se
 * musi resolvovat lenive pres getImageUrl - nejde ji odhadnout z thumbnailu.
 */
@Singleton
class HentaiFoxSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hentaifox"
    override val name = "HentaiFox"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://hentaifox.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDoc(url: String): Document = Jsoup.parse(get(url), url)

    private fun parseThumb(thumb: Element): SManga? {
        val a = thumb.selectFirst("div.inner_thumb a[href^=/gallery/]") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = thumb.selectFirst("div.caption h2.g_title a")?.text()?.trim()
            ?.ifBlank { null } ?: return null
        val cover = thumb.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.trim()?.ifBlank { null }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.lc_galleries div.thumb").mapNotNull(::parseThumb).distinctBy { it.url }

    // Plny seznam tagu ma pres 2000 polozek (viz /tags/) - prilis mnoho na hardcode
    // nebo zive stahovani celeho seznamu. Site ale ma i mnohem hrubsi, jen 7polozkovou
    // taxonomii "Categories" (/categories/) - artist-cg/doujinshi/image-set/manga/misc/
    // non-h/western - lehka na dotahnuti a live overena, ze skutecne filtruje vypis
    // (/category/{slug}/ vraci odlisne galerie nez / i nez jine kategorie).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = fetchDoc("$base/categories/")
            val tags = doc.select("div.tag_item a.tag_btn").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val slug = href.trim('/').substringAfterLast('/')
                val label = a.selectFirst("h3.list_tag")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    // Stranka 1 kategorie je na /category/{slug}/, dalsi stranky ale (na rozdil
    // od home "/page/N/") pouzivaji jine slovo v ceste - "/category/{slug}/pag/N/"
    // (overeno zive: obsah page 1 vs pag/2/ vs pag/3/ je pokazde jiny).
    private fun categoryUrl(slug: String, page: Int): String =
        if (page <= 1) "$base/category/$slug/" else "$base/category/$slug/pag/$page/"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(fetchDoc(categoryUrl(filter.genres.first(), page))) } catch (_: Exception) { emptyList() }
        }
        try {
            val url = if (page <= 1) "$base/" else "$base/page/$page/"
            parseList(fetchDoc(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(fetchDoc(categoryUrl(filter.genres.first(), page))) } catch (_: Exception) { emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseList(fetchDoc("$base/search/?q=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    // a.tag_btn ma uvnitr jeste <span class='t_badge'>pocet</span> - ownText() vezme jen
    // text patrici primo elementu <a> (jmeno tagu), ne text vnoreneho span s citacem.
    private fun parseTagGroup(doc: Document, cssClass: String): List<String> =
        doc.select("ul.$cssClass li a.tag_btn").map { it.ownText().trim() }.filter { it.isNotBlank() }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc("$base${manga.url}")
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val artists = parseTagGroup(doc, "artists")
            val tags = parseTagGroup(doc, "tags")
            val categories = parseTagGroup(doc, "categories")
            val pages = doc.selectFirst("span.i_text.pages")?.text()?.trim()

            manga.copy(
                title = title,
                author = artists.firstOrNull(),
                artist = artists.firstOrNull(),
                genres = tags,
                description = pages,
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
            val count = doc.select("div#append_thumbs div.gallery_thumb").size
                .takeIf { it > 0 }
                ?: doc.selectFirst("span.i_text.pages")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                ?: return@withContext emptyList()
            (1..count).map { n -> Page(index = n - 1, url = "$base/g/$galleryId/$n/") }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(page.url)
            doc.selectFirst("img#gimg")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.trim()?.takeIf { it.startsWith("http") } ?: page.url
        } catch (_: Exception) { page.url }
    }
}

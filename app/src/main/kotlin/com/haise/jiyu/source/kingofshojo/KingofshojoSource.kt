package com.haise.jiyu.source.kingofshojo

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WordPress "mangareader" téma (odlišné od Madara). Seznam kapitol se
 * NEnačítá server-rendered (v HTML je jen Handlebars šablona `{{id}}`),
 * ale přes WP AJAX `admin-ajax.php?action=get_chapters&id={postId}`, kde
 * postId je v `a.series[rel]` atributu na listing/detail stránce.
 */
@Singleton
class KingofshojoSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "kingofshojo"
    override val name = "Kingofshojo"
    override val supportsSortOrder: Boolean get() = false
    override val contentType = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://kingofshojo.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun postForm(url: String, params: Map<String, String>): String {
        val bodyBuilder = FormBody.Builder()
        params.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .post(bodyBuilder.build())
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun encodeUrl(path: String, postId: String) = "$path::$postId"
    private fun pathOf(mangaUrl: String) = mangaUrl.substringBefore("::")
    private fun postIdOf(mangaUrl: String) = mangaUrl.substringAfter("::", "")

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("li").mapNotNull { li ->
            val link = li.selectFirst("div.leftseries h2 a.series") ?: return@mapNotNull null
            val href = toSourcePath(base, link.attr("href"))
            val postId = link.attr("rel").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = li.selectFirst("div.imgseries img")?.attr("src")
            SManga(sourceId = id, url = encodeUrl(href, postId), title = title, coverUrl = cover, contentType = "MANHWA")
        }
    }

    // Jediny funkcni mechanismus zanroveho filtrovani je WP taxonomie archiv
    // "/genres/{slug}/" (overeno zive - odlisne tituly stranka od stranky i vuci
    // nefiltrovanemu vypisu). Widget "genre[]" checkboxu na /manga/ vypada jako
    // Madara-style filtr, ale motiv ho sam odstranuje z DOM pres inline skript
    // ($(".section .quickfilter").parent().remove()) a ?genres=slug parametr je
    // tichy no-op (identicky vystup jako bez parametru) - overeno zive.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/manga/?order=update"))
            val tags = doc.select("ul.genre li a[href]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = Regex("""/genres/([^/]+)/?""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            if (tags.isNotEmpty()) cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /**
     * Karty na "/genres/{slug}/" taxonomie archivu maji jiny (klasicky Madara-like
     * "bsx") markup nez vlastni "/manga/" vypis a NEobsahuji atribut "rel" s
     * postId - proto se pro ne postId ukladat neda a ukladame prazdny retezec
     * (viz fallback v getChapterList, ktery si ho v tom pripade dotahne z detailu).
     */
    private fun parseGenreArchive(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.bsx").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = toSourcePath(base, link.attr("href"))
            val title = card.selectFirst("div.tt")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = encodeUrl(href, ""), title = title, coverUrl = cover, contentType = "MANHWA")
        }
    }

    private fun genreArchiveUrl(slug: String, page: Int) =
        if (page <= 1) "$base/genres/$slug/" else "$base/genres/$slug/page/$page/"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreArchive(get(genreArchiveUrl(filter.genres.first(), page)))
            }
            val url = if (page <= 1) "$base/manga/?order=update" else "$base/manga/page/$page/?order=update"
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreArchive(get(genreArchiveUrl(filter.genres.first(), page)))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/page/$page/?s=$q"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${pathOf(manga.url)}"))
            val statusText = doc.selectFirst("td:contains(Status)")?.nextElementSibling()?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.entry-content-single")?.text()?.trim(),
                genres = doc.select("div.seriestugenre a").map { it.text() },
                status = when {
                    statusText.isNullOrBlank() -> null
                    statusText.contains("ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.contains("complet", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
                contentType = "MANHWA",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            var postId = postIdOf(manga.url)
            if (postId.isBlank()) {
                // Manga prisla ze zanroveho archivu ("/genres/{slug}/"), ktery
                // neuvadi postId primo v karte - dotahneme ho z detailu (atribut
                // data-id na div.bookmark - overeno zive, stejne id, jake pouziva
                // web sam pro tenhle AJAX endpoint).
                val detailDoc = Jsoup.parse(get("$base${pathOf(manga.url)}"))
                postId = detailDoc.selectFirst("div.bookmark[data-id]")?.attr("data-id")?.trim().orEmpty()
            }
            if (postId.isBlank()) return@withContext emptyList()
            val html = postForm("$base/wp-admin/admin-ajax.php", mapOf("action" to "get_chapters", "id" to postId))
            val options = Jsoup.parse(html).select("option[value]")
            options.mapIndexedNotNull { i, opt ->
                val href = toSourcePath(base, opt.attr("value"))
                val text = opt.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (options.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[src*=cdn.kingofshojo.com]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

package com.haise.jiyu.source.mangack

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
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangack.com - vlastni WordPress sablona (ne Madara), plne server-rendered
 * vcetne cteni. Obrazky kapitoly jsou hostovane primo na i.imgur.com.
 */
@Singleton
class MangackSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangack"
    override val name = "mangack"
    override val homepageUrl get() = base
    private val base = "https://mangack.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").trim().ifBlank { return null }
        val cover = img.attr("src").trim().takeIf { it.startsWith("http") }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    // Taxonomie "Genres" - REST base ma velke pismeno (overeno zive na
    // /wp-json/wp/v2/taxonomies), archiv jednoho zanru je pak na
    // /genres/{slug}/page/{page}/. Na rozdil od parseCard() ale karta v tomto
    // archivu nema titul v atributu img[alt] (prazdny), titul je misto toho
    // v samostatnem odkazu ".wrap-text" ve stejnem radku - proto vlastni parser.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val json = JSONArray(get("$base/wp-json/wp/v2/Genres?per_page=100"))
            val tags = (0 until json.length()).mapNotNull { i ->
                val o = json.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug").ifBlank { return@mapNotNull null }
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = name)
            }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun parseGenreList(doc: Document): List<SManga> {
        val coverByHref = doc.select("a:has(img)")
            .filter { it.attr("href").contains("/manga/") }
            .associate { a -> a.attr("href") to a.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.startsWith("http") } }
        return doc.select("a.wrap-text[href*=/manga/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.text().trim().ifBlank { return@mapNotNull null }
            SManga(sourceId = id, url = href, title = title, coverUrl = coverByHref[href], contentType = "MANGA")
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseGenreList(Jsoup.parse(get("$base/genres/${filter.genres.first()}/page/$page/")))
            } catch (_: Exception) { emptyList() }
        }
        try {
            val doc = Jsoup.parse(get("$base/newest/page/$page/"))
            doc.select("a:has(img)").filter { it.attr("href").contains("/manga/") }.mapNotNull(::parseCard).distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseGenreList(Jsoup.parse(get("$base/genres/${filter.genres.first()}/page/$page/")))
            } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            val doc = Jsoup.parse(get(url))
            doc.select("a:has(img)").filter { it.attr("href").contains("/manga/") }.mapNotNull(::parseCard).distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                author = doc.select("a[href*=/authors/]").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() },
                genres = doc.select("a[href*=/genres/]").map { it.text().trim() }.filter { it.isNotBlank() },
                status = doc.select("a[href*=/manga-status/]").firstOrNull()?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("ul.chapterslist li a.title[href]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.ownText().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select("img[src*=i.imgur.com]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

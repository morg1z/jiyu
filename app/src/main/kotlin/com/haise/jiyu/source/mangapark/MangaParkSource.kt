package com.haise.jiyu.source.mangapark

import com.haise.jiyu.util.lazySrc
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangapark.net/.org/.io presmerovavaji na malvertising/anti-adblock stenu
 * (mangapark.io, stejna rodina domen jako zabaveny bato.to), takze puvodni
 * GraphQL API (/apo/) uz neni dostupne. mangapark.page je samostatna zivá
 * domena bez GraphQL - misto toho server-rendered HTML (/series listing,
 * /series/{slug}.{hash} detail se schema.org microdata) plus dva
 * pomocne JSON endpointy zjistene z bundlovaneho JS:
 *  - /api/search?search={query} - vyhledavani (slug_hash je uz plna cast URL)
 *  - /get-chapter-list?slug={slug} - kompletni seznam kapitol (detailni
 *    stranka v prvotnim HTML ukazuje jen kapitolu 1 + poslednich ~18)
 */
@Singleton
class MangaParkSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangapark"
    override val name = "MangaPark"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base

    private val base = "https://mangapark.page"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.comic-item").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/series/]") ?: return@mapNotNull null
            val href = link.attr("href")
            val img = card.selectFirst("img.series-card-img")
            val title = card.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: img?.attr("alt")?.removePrefix("Cover of ")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Web podporuje ?genres={slug} (overeno live) - jen jeden zanr naraz,
            // kombinace genres=a&genres=b server nerozumi.
            val genre = filter.genres.firstOrNull()
                ?.let { "&genres=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            parseList(get("$base/series?page=$page$genre"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // S vybranym zanrem pres /series HTML (searchTerm + genres) - /api/search
        // JSON genre nepodporuje.
        val genre = filter.genres.firstOrNull()
        if (query.isBlank() && genre == null) return@withContext getPopular(page, filter)
        if (genre != null) {
            try {
                val q = if (query.isBlank()) "" else "&searchTerm=" + URLEncoder.encode(query, "UTF-8")
                return@withContext parseList(get("$base/series?page=$page&genres=${URLEncoder.encode(genre, "UTF-8")}$q"))
            } catch (e: Exception) { e.rethrowIfControl(); return@withContext emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val body = get("$base/api/search?search=$q&page=$page")
            val comics = JSONObject(body).optJSONArray("comics") ?: return@withContext emptyList()
            (0 until comics.length()).map { i ->
                val c = comics.getJSONObject(i)
                SManga(
                    sourceId = id,
                    url = "/series/${c.optString("slug_hash")}",
                    title = c.optString("title"),
                    coverUrl = c.optString("image").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Slug bez hashe pouzity v /get-chapter-list?slug=... - "gachiakuta.NTE9Qw" -> "gachiakuta". */
    private fun slugOf(manga: SManga) = manga.url.substringAfterLast("/").substringBefore(".")

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("[itemprop=image] img")?.let {
                    it.lazySrc().orEmpty()
                }?.takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = doc.selectFirst("[itemprop=description]")?.text()?.trim(),
                genres = doc.select("a[itemprop=genre]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugOf(manga)
            val json = JSONObject(get("$base/get-chapter-list?slug=$slug"))
            if (!json.optBoolean("success")) return@withContext emptyList()
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).map { i ->
                val d = data.getJSONObject(i)
                val num = d.optDouble("chapter_num", 0.0).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "${manga.url}/${d.optString("chapter_slug")}",
                    name = d.optString("chapter_name", "Chapter $num"),
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.sortedByDescending { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[data-number]").sortedBy { it.attr("data-number").toIntOrNull() ?: 0 }
                .mapIndexedNotNull { i, img ->
                    val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    Page(i, url, url)
                }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /**
     * Zanrove chips z filtracni stranky /series (`label.genre-item-row[data-slug]` -
     * overeno live). Natvrdo fallback seznam pokud web markup zmeni.
     */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        try {
            val live = Jsoup.parse(get("$base/series"))
                .select("label.genre-item-row[data-slug]")
                .mapNotNull { el ->
                    val slug = el.attr("data-slug").trim().ifBlank { return@mapNotNull null }
                    FilterTag(id = slug, label = el.selectFirst("span")?.text()?.trim()?.ifBlank { null } ?: slug)
                }
                .distinctBy { it.id }
                .sortedBy { it.label.lowercase() }
            if (live.isNotEmpty()) return@withContext live
        } catch (e: Exception) { e.rethrowIfControl() }
        FALLBACK_GENRES
    }

    private companion object {
        // Overeno live z /series - slugy i labely kopiruji markup filtracniho formulare.
        val FALLBACK_GENRES = listOf(
            "action", "adult", "adventure", "boys love", "comedy", "demons", "drama",
            "ecchi", "fantasy", "full color", "girls love", "harem", "historical",
            "horror", "isekai", "josei", "magic", "manga", "manhwa", "martial arts",
            "mature", "mystery", "one shot", "others", "psychological", "romance",
            "school", "school life", "sci-fi", "seinen", "shoujo", "shounen",
            "slice of life", "smut", "sports", "supernatural", "thriller", "tragedy",
            "webtoons", "yaoi",
        ).map { slug ->
            FilterTag(id = slug, label = slug.replace('-', ' ').replaceFirstChar(Char::uppercase))
        }
    }
}

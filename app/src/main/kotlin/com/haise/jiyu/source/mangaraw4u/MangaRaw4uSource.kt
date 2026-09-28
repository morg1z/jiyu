package com.haise.jiyu.source.mangaraw4u

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
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
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangaraw4u.com - RAW (japonske, neprelozene) manga. Cely web je server-rendered
 * vcetne cteni (zadny WebView/JS trik potreba), hledani jede pres cisty JSON
 * `/api/search?q=`.
 */
@Singleton
class MangaRaw4uSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangaraw4u"
    override val name = "MangaRaw4u"
    override val language = "vi" // vietnamsky web (html lang="vi", overeno zive)
    override val homepageUrl get() = base
    private val base = "https://mangaraw4u.com"

    override val supportsTagFilter: Boolean get() = true

    // /search ma <select id="filterGenre"> se statickym seznamem zanru (~580 polozek,
    // overeno zive) - stahne se jednou a cachuje po dobu behu appky, stejne jako u MangaDexu.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/search"))
            val tags = doc.select("select#filterGenre option[value]").mapNotNull { opt ->
                val value = opt.attr("value").trim()
                if (value.isBlank()) return@mapNotNull null
                val label = opt.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = value, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseResultCards(doc: org.jsoup.nodes.Document): List<SManga> =
        doc.select("a.result-card").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.selectFirst(".result-card-title")?.text()?.trim() ?: return@mapNotNull null
            val cover = a.selectFirst(".result-card-image img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            SManga(sourceId = id, url = toSourcePath(base, href), title = title, coverUrl = cover, contentType = "MANGA")
        }

    // Web /search filtruje status= 1(renzai)|2(kankei)|3(kyusai)|4(uchikiri) -
    // overeno zive (status=2 vraci jinou sadu nez vychozi). Select "filterType"
    // na strance existuje, ale zadna URL varianta (type=, types[]=, filter[type]=)
    // serverove nefiltruje - typ filtr nepropagujeme.
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus", "cancelled")
    override val availableSorts: Set<String> get() = setOf("popular", "latest", "title")

    private val statusValues = mapOf(
        "ongoing" to "1", "completed" to "2", "hiatus" to "3", "cancelled" to "4",
    )
    private val sortValues = mapOf(
        "latest" to "-updated_at", "updated" to "-updated_at",
        "popular" to "-views", "new" to "-created_at", "title" to "name",
    )

    private fun searchUrl(filter: MangaFilter, page: Int?, query: String? = null): String {
        val sort = sortValues[filter.sortBy] ?: "-views"
        val sb = StringBuilder("$base/search?sort=$sort")
        page?.let { sb.append("&page=").append(it) }
        filter.genres.firstOrNull()?.let { sb.append("&genre=").append(URLEncoder.encode(it, "UTF-8")) }
        statusValues[filter.status]?.let { sb.append("&status=").append(it) }
        query?.takeIf { it.isNotBlank() }
            ?.let { sb.append("&filter%5Bname%5D=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // /search?sort= ma stejnou strukturu karet jako uvodni strana, ale na rozdil
            // od ni umi radit i podle "-updated_at" (Nejnovejsi) - overeno zivě, jina data
            // nez u "-views" (Popularni).
            val doc = Jsoup.parse(get(searchUrl(filter, page)))
            parseResultCards(doc)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        val hasBrowseFilters = filter.genres.isNotEmpty() || filter.status != null
        if (hasBrowseFilters) {
            // Genrovy archiv nema samostatny JSON endpoint - pouzivame stejnou HTML
            // /search stranku jako getPopular, jen navic s parametrem genre a volitelnym
            // filter[name] pro dotaz (overeno zive: kombinace genre+filter[name] funguje).
            try {
                val doc = Jsoup.parse(get(searchUrl(filter, null, query)))
                return@withContext parseResultCards(doc)
            } catch (e: Exception) { e.rethrowIfControl(); return@withContext emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(get("$base/api/search?q=$q"))
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i ->
                val m = results.optJSONObject(i) ?: return@mapNotNull null
                val slug = m.optString("slug").ifBlank { return@mapNotNull null }
                SManga(
                    sourceId = id, url = "/manga/$slug", title = m.optString("name"),
                    coverUrl = m.optString("cover_full_url").takeIf { it.isNotBlank() },
                    contentType = "MANGA",
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val genres = doc.select(".detail-genres a").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(
                title = doc.selectFirst("h1.detail-title")?.text()?.trim() ?: manga.title,
                genres = genres,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.US)
            doc.select("div.detail-chapter-row").mapNotNull { row ->
                val a = row.selectFirst(".detail-col-chapter a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = row.attr("data-chapter-number").toFloatOrNull() ?: 0f
                val dateText = row.selectFirst(".detail-col-updated")?.text()?.trim().orEmpty()
                val date = try { dateFormat.parse(dateText)?.time ?: 0L } catch (e: Exception) { e.rethrowIfControl(); 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = a.text().trim(), chapterNumber = num, dateUpload = date)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img").filter { it.attr("alt").contains(" - Page ") }.mapIndexedNotNull { i, img ->
                val url = img.lazySrc().orEmpty().let { absoluteMediaUrl(base, it) }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

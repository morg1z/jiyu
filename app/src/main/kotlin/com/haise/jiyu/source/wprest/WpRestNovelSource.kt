package com.haise.jiyu.source.wprest

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.novelText
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Generický zdroj pro překladatelské blogy na WordPressu, kde jsou série
 * uspořádané jako kategorie a kapitoly jako příspěvky (hasutl.wordpress.com,
 * oasistranslations.wordpress.com …). Používá veřejné WP REST API -
 * self-hosted `{base}/wp-json/wp/v2` nebo WordPress.com přes
 * `public-api.wordpress.com/wp/v2/sites/{host}`.
 *
 * Mapování: kategorie = manga (url = veřejný link kategorie), příspěvek
 * v kategorii = kapitola (url = permalink, text z `content.rendered`).
 */
class WpRestNovelSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val apiBase: String,
    private val client: OkHttpClient,
    private val languageOverride: String = "en",
    private val isAdultOverride: Boolean = false,
    private val inGlobalSearch: Boolean = false,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')
    private val api get() = apiBase.trimEnd('/')

    override val contentType = "NOVEL"
    override val homepageUrl get() = baseUrl
    override val supportsTagFilter: Boolean get() = false // WP kategorie = serie, ne zanry
    override val language get() = languageOverride
    override val isAdult get() = isAdultOverride
    override val includeInGlobalSearch get() = inGlobalSearch
    override val availableSorts get() = setOf("popular", "latest", "title")

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun JSONArray.toObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    /** Slugy výchozí WP kategorie ("Uncategorized") napříč jazyky - nejsou série. */
    private val uncategorizedSlugs = setOf(
        "uncategorized", "non-classe", "sin-categoria", "sem-categoria", "non-classifiee",
    )

    private fun categoryToManga(c: JSONObject): SManga? {
        if (c.optString("slug") in uncategorizedSlugs) return null
        val link = c.optString("link").ifBlank { null } ?: return null
        val title = Jsoup.parse(c.optString("name")).text().trim()
        if (title.isBlank()) return null
        return SManga(
            sourceId = id,
            url = link,
            title = title,
            coverUrl = null,
            description = Jsoup.parse(c.optString("description")).text().trim().ifBlank { null },
            contentType = "NOVEL",
        )
    }

    private fun categoriesUrl(page: Int, filter: MangaFilter, search: String? = null): String {
        val orderby = when (filter.sortBy) {
            "latest" -> "id&order=desc"
            "title" -> "name&order=asc"
            else -> "count&order=desc"
        }
        var url = "$api/categories?per_page=100&page=$page&orderby=$orderby&exclude=1&_fields=id,name,slug,count,link,description"
        if (search != null) url += "&search=" + URLEncoder.encode(search, "UTF-8")
        return url
    }

    private fun fetchCategories(url: String): List<SManga> =
        JSONArray(getJson(url)).toObjects().mapNotNull { categoryToManga(it) }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { fetchCategories(categoriesUrl(page, filter)) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { fetchCategories(categoriesUrl(page, filter, query)) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Slug kategorie z veřejného linku (poslední segment cesty). */
    private fun categorySlug(url: String): String =
        url.trimEnd('/').substringAfterLast('/')

    private fun categoryId(url: String): Int? {
        val slug = categorySlug(url)
        val arr = JSONArray(getJson("$api/categories?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=id"))
        return arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val slug = categorySlug(resolveUrl(manga.url))
            val arr = JSONArray(getJson("$api/categories?slug=${URLEncoder.encode(slug, "UTF-8")}"))
            val c = arr.optJSONObject(0) ?: return@withContext manga
            manga.copy(
                title = Jsoup.parse(c.optString("name")).text().trim().ifBlank { manga.title },
                description = Jsoup.parse(c.optString("description")).text().trim().ifBlank { manga.description },
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun resolveUrl(url: String): String =
        if (url.startsWith("http")) url else root + if (url.startsWith("/")) url else "/$url"

    private fun isoDate(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(s)?.time ?: 0L
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val catId = categoryId(resolveUrl(manga.url)) ?: return@withContext emptyList()
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (true) {
                val url = "$api/posts?categories=$catId&per_page=100&page=$page" +
                    "&orderby=date&order=asc&_fields=id,date,link,slug,title"
                val posts = JSONArray(getJson(url)).toObjects()
                if (posts.isEmpty()) break
                posts.forEach { p ->
                    val link = p.optString("link").ifBlank { null } ?: return@forEach
                    val title = Jsoup.parse(p.optJSONObject("title")?.optString("rendered") ?: "")
                        .text().trim().ifBlank { p.optString("slug") }
                    chapters += SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = link,
                        name = title,
                        chapterNumber = Regex("[\\d.,]+").find(title)?.value?.replace(',', '.')?.toFloatOrNull()
                            ?: (chapters.size + 1).toFloat(),
                        dateUpload = isoDate(p.optString("date")),
                    )
                }
                if (posts.size < 100) break
                page++
                if (page > 50) break // bezpečnostní limit (100×50 = 5000 kapitol)
            }
            // Kontrakt MangaSource = seřazené od nejnovější; API čerpá chronologicky (asc).
            chapters.asReversed()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            // chapter.url je permalink příspěvku - post najdeme přes ?slug=.
            val slug = chapter.url.trimEnd('/').substringAfterLast('/')
            val arr = JSONArray(getJson("$api/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=content"))
            val html = arr.optJSONObject(0)?.optJSONObject("content")?.optString("rendered") ?: ""
            if (html.isBlank()) return@withContext emptyList()
            val doc = Jsoup.parse(html)
            doc.select("script, style, iframe, ins, .pc-adv, .adsbygoogle, form, .sharedaddy").remove()
            val text = doc.body()?.novelText() ?: doc.novelText()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

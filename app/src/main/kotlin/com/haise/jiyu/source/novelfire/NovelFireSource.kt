package com.haise.jiyu.source.novelfire

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
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Novel Fire (driv novelfire.net, dnes novelphoenix.com) - server-rendered
 * custom web. Web se prestěhoval: novelfire.net stranky kapitol vraceji JS
 * redirect na novelphoenix.com a detail URL se zmenily z "/book/{slug}" na
 * "/novel/{slug}" (audit 2026-09). Markup karet/kapitol/#content je identicky.
 * Kapitoly jsou na samostatne strance "{novel}/chapters" (stovky az tisice
 * kapitol, strankovano po 100), text kapitoly primo v `#content`.
 */
@Singleton
class NovelFireSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelfire"
    override val name = "Novel Fire"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://novelphoenix.com"

    private fun get(url: String, ua: String = SourceHttp.USER_AGENT_DESKTOP): String {
        // Web aktivne rate-limituje (429) - napr. pruchod 23 stranek kapitol
        // za sebou. Jednou pockat a zkusit znovu je levnejsi nez hazet.
        var lastCode = 0
        repeat(2) { attempt ->
            val req = Request.Builder().url(url)
                .header("User-Agent", ua)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return resp.body?.string().orEmpty()
                lastCode = resp.code
                val retryable = resp.code == 429 || resp.code in 500..599
                if (!retryable || attempt == 1) resp.bodyOrThrow(url)
            }
            Thread.sleep(1200)
        }
        throw java.io.IOException("HTTP $lastCode při načítání $url")
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("li.novel-item").mapNotNull { el ->
            // Web prejmenoval "h2.title a" na "a:has(h4.novel-title)" (audit
            // 2026-07-27) - item-body ma navic druhy odkaz na posledni
            // kapitolu (h5.chapter-title), proto nestaci prvni <a> v bloku.
            val link = el.selectFirst("a:has(h4.novel-title)") ?: el.selectFirst("h2.title a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.selectFirst("h4.novel-title")?.text()?.trim()?.ifBlank { null } ?: link.text().trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.let {
                it.lazySrc().orEmpty()
            }?.let { resolveSourceUrl(base, it) }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "NOVEL")
        }
    }

    // /search-adv ma checkboxy "categories[]" (numericke ID pro pripadny AJAX
    // formular), ale skutecny prohlizeci archiv pouziva vlastni slug primo v
    // ceste - "/genre-{slug}/sort-{sort}/status-all/all-novel" - overeno zivě,
    // ze slug = jednoduchy slugify() nazvu (lowercase, mezery/"+"/ostatni
    // znaky -> "-"), shoduje se s odkazy v paticce webu (napr. "Sci-fi" ->
    // "sci-fi", "Slice of Life" -> "slice-of-life", "Lgbt+" -> "lgbt").
    private fun slugify(text: String): String = text.trim().lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/search-adv"))
            val tags = doc.select("label.chk-item:has(input[name=\"categories[]\"])").mapNotNull { label ->
                val name = label.ownText().trim().ifBlank { null } ?: return@mapNotNull null
                val slug = slugify(name).ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = name)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Web filtruje status dvema cestami: v zanrove ceste jako segment
    // "status-all|status-ongoing|status-completed" a na /search jako query
    // "status=0|1" (0=Ongoing, 1=Completed) - oboji overeno zive, ruzne sady.
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed")

    private val statusValues = mapOf("ongoing" to "ongoing", "completed" to "completed")
    private val queryValues = mapOf("ongoing" to "0", "completed" to "1")
    private val sortValues = mapOf(
        "latest" to "date", "popular" to "rank-top", "rating" to "rating-score-top",
    )

    private fun genreUrl(slug: String, page: Int, sortBy: String, status: String? = null): String {
        val sortSegment = if (sortBy == "latest") "sort-new" else "sort-popular"
        val statusSegment = statusValues[status] ?: "all"
        return "$base/genre-$slug/$sortSegment/status-$statusSegment/all-novel?page=$page"
    }

    private fun searchUrl(filter: MangaFilter, page: Int, query: String? = null): String {
        val sort = sortValues[filter.sortBy] ?: "date"
        val sb = StringBuilder("$base/search?sort=$sort&page=$page")
        queryValues[filter.status]?.let { sb.append("&status=").append(it) }
        query?.takeIf { it.isNotBlank() }?.let { sb.append("&keyword=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(get(genreUrl(filter.genres.first(), page, filter.sortBy, filter.status))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (filter.status != null) {
            return@withContext try { parseList(get(searchUrl(filter, page))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // /ranking je pevny top-100 bez strankovani ("?page=N" web ignoruje a
        // vraci znovu p1 - audit DUP), takze pro popular existuje jen stranka 1.
        // /latest-release-novels paginuje korektne (p1/p2/p3 s rozdilnymi tituly).
        if (filter.sortBy != "latest" && page > 1) return@withContext emptyList()
        // /ranking pouziva layout s h2.title, /latest-release-novels h4.novel-title -
        // parseList uz oboje umi (fallback na "h2.title a").
        val path = if (filter.sortBy == "latest") "latest-release-novels" else "ranking"
        try { parseList(get("$base/$path?page=$page")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(get(genreUrl(filter.genres.first(), page, filter.sortBy, filter.status))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        try {
            parseList(get(searchUrl(filter, page, query)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val description = doc.select("div.summary div.content p").joinToString("\n\n") { it.text().trim() }
                .ifBlank { null }
            manga.copy(
                title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("div.author span[itemprop=author]")?.text()?.trim(),
                genres = doc.select("div.categories a.property-item").map { it.text().trim() },
                description = description,
                status = doc.selectFirst("strong.ongoing, strong.completed, strong.hiatus")?.text()?.trim(),
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page < 300) {
                val url = "$base${manga.url}/chapters?page=$page"
                var doc = try { Jsoup.parse(get(url)) } catch (e: Exception) { e.rethrowIfControl(); null }
                var rows = doc?.select("ul.chapter-list li a").orEmpty()
                if (doc == null || (rows.isEmpty() && page == 1)) {
                    // /chapters je za Cloudflare obcas blokovana podle TLS/UA
                    // fingerprintu (audit: desktop OkHttp dostal 403/challenge,
                    // curl i mobilni UA prochazi) - na p1 jednou zkusime
                    // mobilni UA, az pak se vzdat.
                    doc = try { Jsoup.parse(get(url, SourceHttp.USER_AGENT_ANDROID)) }
                        catch (e: Exception) { e.rethrowIfControl(); break }
                    rows = doc.select("ul.chapter-list li a")
                }
                if (rows.isEmpty()) break
                rows.forEach { a ->
                    val href = a.attr("href")
                    val num = a.selectFirst("span.chapter-no")?.text()?.trim()?.toFloatOrNull() ?: 0f
                    val title = a.selectFirst("strong.chapter-title")?.text()?.trim()?.ifBlank { null }
                        ?: "Chapter $num"
                    val dateAttr = a.selectFirst("time.chapter-update")?.attr("datetime")
                    chapters.add(
                        SChapter(
                            sourceId = id,
                            mangaUrl = manga.url,
                            url = href,
                            name = title,
                            chapterNumber = num,
                            dateUpload = parseDate(dateAttr),
                        )
                    )
                }
                if (doc.selectFirst("li.page-item a[href*=page=${page + 1}]") == null) break
                page++
            }
            chapters
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (e: Exception) { e.rethrowIfControl(); 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val url = resolveSourceUrl(base, chapter.url)
            var text = try {
                Jsoup.parse(get(url)).selectFirst("div#content")?.text()?.trim().orEmpty()
            } catch (e: Exception) { e.rethrowIfControl(); "" }
            if (text.isBlank()) {
                // Stejny CF fallback jako getChapterList - challenge stranka se
                // sparsuje, ale #content v ni neni, takze vysledek je prazdny.
                text = try {
                    Jsoup.parse(get(url, SourceHttp.USER_AGENT_ANDROID))
                        .selectFirst("div#content")?.text()?.trim().orEmpty()
                } catch (e: Exception) { e.rethrowIfControl(); "" }
            }
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

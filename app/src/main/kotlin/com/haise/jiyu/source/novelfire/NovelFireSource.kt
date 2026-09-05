package com.haise.jiyu.source.novelfire

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
 * Novel Fire (novelfire.net) - server-rendered custom web. Kapitoly
 * jsou na samostatne strance "{book}/chapters" (stovky az tisice
 * kapitol, strankovano po 100), text kapitoly primo v `#content`.
 */
@Singleton
class NovelFireSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelfire"
    override val name = "Novel Fire"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://novelfire.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
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
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$base$it" }
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
        } catch (_: Exception) { emptyList() }
    }

    private fun genreUrl(slug: String, page: Int, sortBy: String): String {
        val sortSegment = if (sortBy == "latest") "sort-new" else "sort-popular"
        return "$base/genre-$slug/$sortSegment/status-all/all-novel?page=$page"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(get(genreUrl(filter.genres.first(), page, filter.sortBy))) } catch (_: Exception) { emptyList() }
        }
        // /ranking (Popularni) pouziva jiny sablonovy layout (h2.title) nez
        // /latest-release-novels (h4.novel-title) - parseList uz oboje umi
        // (fallback na "h2.title a"), overeno zivě jako prokazatelne jine tituly.
        val path = if (filter.sortBy == "latest") "latest-release-novels" else "ranking"
        try { parseList(get("$base/$path?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(get(genreUrl(filter.genres.first(), page, filter.sortBy))) } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search?keyword=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
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
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page < 300) {
                val doc = Jsoup.parse(get("$base${manga.url}/chapters?page=$page"))
                val rows = doc.select("ul.chapter-list li a")
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
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val text = doc.selectFirst("div#content")?.text()?.trim().orEmpty()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.oppaistream

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * oppai.stream je puvodne hentai VIDEO stranka - manga/manhwa cteni je na
 * samostatne subdomene "read.oppai.stream" (jina sekce, sdili jen branding).
 * Karty (homepage load-more.php i api-search.php) i seznam kapitol na
 * detailu jsou cistě server-rendovane HTML (zadny JS reader potreba).
 * Obrazky stranek hosti treti CDN domena "myspacecat.pictures":
 *  - pocet stranek kapitoly: "myspacecat.pictures/manhwa/images.php?f-m={slug}&c={cislo}" (vraci holé cislo)
 *  - URL stranky: "myspacecat.pictures/manhwa/{slug}/{cislo kapitoly}/{cislo stranky}.jpg"
 */
@Singleton
class OppaiStreamSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "oppaistream"
    override val name = "Oppai Stream"
    override val contentType = "MANHWA"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://read.oppai.stream"
    private val cdnBase = "https://myspacecat.pictures/manhwa"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun slugOf(manga: SManga) = manga.url.substringAfter("?m=")

    private fun parseCardListing(doc: Document): List<SManga> =
        doc.select("div.in-grid").mapNotNull { card ->
            val a = card.selectFirst("a") ?: return@mapNotNull null
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = card.selectFirst("h3.man-title")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val cover = card.selectFirst("img.read-cover")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANHWA")
        }.distinctBy { it.url }

    // "/search" stranka ma checkbox panel zanru (h5.search-tag-box[genre=slug]) ktery
    // JS posila do "api-search.php?...&genres=slug1,slug2" - stejna karetni struktura
    // (div.in-grid) jako normalni load-more.php vypis, jen jina sada titulu (overeno
    // zive: genres=yuri vs bez filtru - jine tituly).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = fetchDocument("$base/search?a=recent")
            val tags = doc.select("h5.search-tag-box[genre]").mapNotNull { el ->
                val slug = el.attr("genre").trim().ifBlank { null } ?: return@mapNotNull null
                val label = el.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreSearchUrl(query: String, page: Int, genres: List<String>, order: String = ""): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val g = URLEncoder.encode(genres.joinToString(","), "UTF-8")
        return "$base/api-search.php?text=$q&order=$order&page=$page&limit=18&status=&genres=$g&blacklist="
    }

    // Řazení "api-search.php?order=" z webu (checkboxy Order): views = Most Views, uploaded = Recently
    // Uploaded (nové kapitoly), recent = Recently Released (nové tituly, výchozí pořadí load-more.php).
    // Dřív "Populární" i "Nejnovější" vracely totéž pořadí (load-more.php), takže přepínač nic nedělal.
    private fun orderFor(filter: MangaFilter) = if (filter.sortBy == "latest") "uploaded" else "views"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            try {
                parseCardListing(fetchDocument(genreSearchUrl("", page, filter.genres, orderFor(filter))))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (filter.genres.isNotEmpty()) {
                return@withContext try {
                    parseCardListing(fetchDocument(genreSearchUrl(query.trim(), page, filter.genres)))
                } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
            }
            if (query.isBlank()) return@withContext getPopular(page, filter)
            if (page > 1) return@withContext emptyList()
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseCardListing(fetchDocument("$base/api-search.php?text=$q"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val titleHeading = doc.selectFirst("h1.line-3")
            val author = titleHeading?.selectFirst("a")?.text()?.trim()?.ifBlank { null }
            val title = titleHeading?.clone()?.apply { selectFirst("a")?.remove() }?.text()?.trim()
                ?.removeSuffix("By")?.trim()?.ifBlank { null } ?: manga.title
            val description = doc.selectFirst("h5.description")?.text()?.trim()?.ifBlank { null }
            val genres = doc.select("div.genres a h5").mapNotNull { it.text().trim().ifBlank { null } }

            manga.copy(title = title, author = author, description = description, genres = genres)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugOf(manga)
            val doc = fetchDocument(manga.url)
            doc.select(".category-chapters a[ch-num]").mapNotNull { a ->
                val chNumStr = a.attr("ch-num").ifBlank { return@mapNotNull null }
                val chapterNumber = chNumStr.toFloatOrNull() ?: return@mapNotNull null
                val name = a.selectFirst("h4")?.text()?.trim()?.ifBlank { null } ?: "Chapter $chNumStr"
                val dateText = a.selectFirst("h6")?.text()?.trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    // "c" musi zustat presny puvodni retezec z "ch-num" (ne odvozeny z Float),
                    // aby se predesla ztrate presnosti ("60" -> 60.0f -> "60.0" by uz images.php nenaslo).
                    url = "$base/page?m=$slug&c=$chNumStr",
                    name = name,
                    chapterNumber = chapterNumber,
                    dateUpload = parseRelativeDate(dateText),
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val match = Regex("""(\d+)\s*(hour|day|week|month|year)""").find(text.lowercase()) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unitMs = when (match.groupValues[2]) {
            "hour"  -> 3_600_000L
            "day"   -> 86_400_000L
            "week"  -> 7 * 86_400_000L
            "month" -> 30L * 86_400_000L
            "year"  -> 365L * 86_400_000L
            else    -> return 0L
        }
        return System.currentTimeMillis() - amount * unitMs
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val slug = chapter.url.substringAfter("?m=").substringBefore("&c=")
            val chapterNum = chapter.url.substringAfter("&c=")
            val countText = fetchHtml("$cdnBase/images.php?f-m=$slug&c=$chapterNum").trim()
            val count = countText.toIntOrNull() ?: return@withContext emptyList()
            (1..count).map { i ->
                val url = "$cdnBase/$slug/$chapterNum/$i.jpg"
                Page(index = i - 1, url = url, imageUrl = url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

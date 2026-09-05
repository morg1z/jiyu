package com.haise.jiyu.source.mangageko

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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaGekoSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangageko"
    override val name = "MangaGeko"
    override val homepageUrl get() = base
    override val supportsTagFilter = true
    private val base = "https://www.mgeko.cc"

    @Volatile private var cachedTags: List<FilterTag>? = null

    /**
     * "/browse-comics/" je klientska JS aplikace (chip filtry) - samotna
     * stranka zadne vysledky server-side nerenderuje, tahaji se pres JSON
     * "/browse-comics/data/?include_genres=X&q=&page=N" (viz API_BASE_URL
     * v inline JS stranky). Zanry pro "include_genres" jsou "data-value"
     * atributy chipu `button.chip[data-group=include_genres]" na te same
     * strance. Endpoint podporuje i kombinaci s textovym hledanim ("q=") a
     * s vice zanry najednou (carkou oddelene, AND/union overeno zive -
     * pocet vysledku odpovida ocekavani, ne stejnemu jako jeden zanr).
     */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            val doc = Jsoup.parse(get("$base/browse-comics/"))
            doc.select("button.chip[data-group=include_genres]").mapNotNull { chip ->
                val value = chip.attr("data-value").ifBlank { return@mapNotNull null }
                val label = chip.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = value, label = label)
            }
        } catch (_: Exception) { emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    private fun parseBrowseCards(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.comic-card").mapNotNull { card ->
            val a = card.selectFirst("h3.comic-card__title a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.text().trim().ifBlank { return@mapNotNull null }
            val cover = card.selectFirst("img")?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
        }
    }

    private fun fetchBrowseComics(query: String, page: Int, filter: MangaFilter): List<SManga> {
        val genres = URLEncoder.encode(filter.genres.joinToString(","), "UTF-8")
        val sort = if (filter.sortBy == "latest") "latest" else "popular_all_time"
        val q = if (query.isNotBlank()) "&q=${URLEncoder.encode(query, "UTF-8")}" else ""
        val json = JSONObject(get("$base/browse-comics/data/?include_genres=$genres&sort=$sort&page=$page$q"))
        return parseBrowseCards(json.optString("results_html"))
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").trim().ifBlank {
            a.selectFirst(".novel-title")?.text()?.trim().orEmpty()
        }.takeIf { it.isNotBlank() } ?: return null
        val img = a.selectFirst("img")
        val cover = img?.attr("data-src")?.trim()?.takeIf { it.isNotBlank() } ?: img?.attr("src")?.trim()
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { fetchBrowseComics("", page, filter) } catch (_: Exception) { emptyList() }
        }
        try {
            // Web sam bez parametru vraci "Latest Updated Manga" (viz <title> stranky) -
            // teprve "hot=true" prepne na skutecne popularni/trending tituly (overeno
            // zive, oba vraceji zcela odlisne seznamy).
            val hotParam = if (filter.sortBy == "latest") "" else "&hot=true"
            val doc = Jsoup.parse(get("$base/jumbo/manga/?results=$page&filter=All$hotParam"))
            doc.select("a.list-body[href^=/manga/]").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { fetchBrowseComics(query, page, filter) } catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search/?search=$q&page=$page"))
            doc.select("a.list-body[href^=/manga/]").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val genres = doc.select("div.categories a.property-item").map { it.text().trim() }
            val rawDescription = doc.selectFirst("p.description")?.text()?.trim()
            val description = rawDescription
                ?.substringAfter("The Summary is", rawDescription)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val contentType = when {
                genres.any { it.equals("Manhwa", ignoreCase = true) } -> "MANHWA"
                genres.any { it.equals("Manhua", ignoreCase = true) } -> "MANHUA"
                else -> "MANGA"
            }
            manga.copy(
                title = doc.selectFirst("h1.novel-title, h1")?.text()?.trim() ?: manga.title,
                description = description,
                author = doc.selectFirst("a.property-item span[itemprop=author]")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("Updating", ignoreCase = true) },
                status = doc.selectFirst("strong.ongoing, strong.completed")?.text()?.trim(),
                genres = genres.filterNot { it.equals("Manga", true) || it.equals("Manhwa", true) || it.equals("Manhua", true) },
                contentType = contentType,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("li.chapter-list-item a[href*=/reader/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = Regex("""chapter-([\d.]+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val name = if (num == num.toInt().toFloat()) "Chapter ${num.toInt()}" else "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img[src*=/sv2/comic/]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

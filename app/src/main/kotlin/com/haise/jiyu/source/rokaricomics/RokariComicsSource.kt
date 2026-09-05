package com.haise.jiyu.source.rokaricomics

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
 * rokaricomics.com - WordPress motiv rodiny "Mangastream"/Mangathemesia
 * (stejne selektory jako [com.haise.jiyu.source.galaxymanga.GalaxyMangaSource]
 * a [com.haise.jiyu.source.rawkuma.RawKumaSource]), ale status/typ jsou tu v
 * `<table><tr><td>` radcich misto `div.imptdt`. Seznam stranek kapitoly neni
 * server-rendered (#readerarea je prazdny div, JS ho plni z
 * `ts_reader.run({...,"images":[...]})` vlozeneho v <script>).
 */
@Singleton
class RokariComicsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "rokaricomics"
    override val name = "Rokari Comics"
    override val homepageUrl get() = base
    private val base = "https://rokaricomics.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".bsx").mapNotNull { el ->
            val link = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { el.selectFirst(".tt")?.text().orEmpty() }
                .trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = link.attr("href"), title = title, coverUrl = cover, contentType = "MANGA")
        }
    }

    // "/manga/" ma DLE filter formular s checkboxy zanru (name="genre[]"), jehoz
    // hodnoty odpovidaji dedikovanemu archivu "/genres/{slug}/" se stejnou kartovou
    // strukturou (.bsx) jako obycejny vypis, jen jina sada titulu (overeno zive:
    // genre=action str.1 vs str.2 - jine tituly, i oproti nefiltrovanemu vypisu).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/manga/"))
            val tags = doc.select("input[name='genre[]']").mapNotNull { input ->
                val slug = input.attr("value").trim().ifBlank { null } ?: return@mapNotNull null
                val label = doc.selectFirst("label[for='${input.attr("id")}']")?.text()?.trim()?.ifBlank { null } ?: slug
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun genreUrl(genre: String, page: Int): String =
        if (page <= 1) "$base/genres/$genre/" else "$base/genres/$genre/page/$page/"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(genreUrl(filter.genres.first(), page)))
            }
            val order = if (filter.sortBy == "latest") "update" else "popular"
            parseList(get("$base/manga/?page=$page&order=$order"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(genreUrl(filter.genres.first(), page)))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val fields = doc.select("tr").mapNotNull { tr ->
                val cells = tr.select("td")
                if (cells.size < 2) null else cells[0].text().trim() to cells[1].text().trim()
            }.toMap()
            val contentType = when (fields["Type"]?.lowercase()) {
                "manhwa" -> "MANHWA"
                "manhua" -> "MANHUA"
                "manga" -> "MANGA"
                else -> "MANGA"
            }
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst(".entry-content-single")?.text()?.trim(),
                genres = doc.select("a[href*=/genres/]").map { it.text().trim() }.filter { it.isNotBlank() },
                status = fields["Status"]?.takeIf { it.isNotBlank() },
                contentType = contentType,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("div.eplister li a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.selectFirst("span.chapternum")?.text()?.trim()
                    ?: a.text().trim().ifBlank { "Chapter ${i + 1}" }
                val num = Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val raw = Regex(""""images":\[(.*?)\]""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            Regex(""""([^"]+)"""").findAll(raw)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.isNotBlank() }
                .mapIndexed { i, url -> Page(i, url, url) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

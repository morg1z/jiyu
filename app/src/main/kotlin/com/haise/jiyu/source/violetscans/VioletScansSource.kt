package com.haise.jiyu.source.violetscans

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * violetscans.org - MangaThemesia (WordPress) motiv, stejna rodina jako EvaScansSource/
 * Hentai20Source/ToongodSource, ale s vlastnim permalinkem "/comics/{slug}" misto
 * vychoziho "/manga/{slug}". Katalog i vyhledavani sdili stejnou kartu `div.bsx`
 * (`a[href][title]` + `img` obalka). Genrovy filtr NENI pres cislene "genre[]"
 * hodnoty z checkboxu na "/comics/" (ty nebyly zive overeny jako funkcni), ale pres
 * samostatny archiv "/genres/{slug}/" (potvrzeno primo v breadcrumb datech detailu
 * mangy) - slug se odvozuje ze zobrazovaneho nazvu zanru (lowercase, mezery/ostatni
 * znaky -> "-"), overeno zive na "drama"/"romance"/"dark-fantasy".
 *
 * Strankovani NENI "/comics/page/N/" (to vraci porad stejnou prvni stranku, overeno
 * zive) ale query parametr "/comics/?page=N" (funguje i s "?order=..." a "?genre" zanrem
 * najednou).
 *
 * Nektere nove kapitoly jsou zamknute za mincemi (odkaz je jen JS modal bez `href`,
 * misto skutecneho odkazu na cteni) - takove polozky se proste vynechaji, presne jako
 * u jinych zdroju s premium/zamknutymi kapitolami.
 */
@Singleton
class VioletScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "violetscans"
    override val name = "Violet Scans"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://violetscans.org"

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    private fun slugify(text: String): String = text.trim().lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/comics/"))
            val tags = doc.select("input.genre-item[name=\"genre[]\"]").mapNotNull { input ->
                val label = doc.selectFirst("label[for=${input.attr("id")}]")?.text()?.trim()
                    ?.ifBlank { null } ?: return@mapNotNull null
                val slug = slugify(label).ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun genreUrl(slug: String, page: Int): String =
        if (page <= 1) "$base/genres/$slug/" else "$base/genres/$slug/?page=$page"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.bsx").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.attr("title").trim().ifBlank {
                card.selectFirst("div.tt")?.text()?.trim().orEmpty()
            }.ifBlank { return@mapNotNull null }
            val cover = card.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            val url = if (genre != null) {
                genreUrl(genre, page)
            } else {
                val order = if (filter.sortBy == "latest") "update" else "popular"
                "$base/comics/?order=$order&page=$page"
            }
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            val url = if (genre != null) {
                genreUrl(genre, page)
            } else {
                if (page > 1) return@withContext emptyList()
                val q = URLEncoder.encode(query, "UTF-8")
                "$base/?s=$q"
            }
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    /** "Label" / "Hodnota" dvojice v `div.tsinfo.bixbox div.imptdt` (Type, Status, Author, ...). */
    private fun statValue(doc: Document, label: String): String? =
        doc.select("div.tsinfo.bixbox div.imptdt").firstOrNull {
            it.selectFirst("h1")?.text()?.trim().equals(label, ignoreCase = true)
        }?.selectFirst("i")?.text()?.trim()?.ifBlank { null }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manhua" -> "MANHUA"
        "manga" -> "MANGA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANHWA"
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("div.thumb img")?.attr("src")?.trim()?.ifBlank { null } ?: manga.coverUrl,
                description = doc.selectFirst("div.entry-content.entry-content-single")?.text()?.trim()?.ifBlank { null },
                genres = doc.select(".mgen a[rel=tag]").map { it.text().trim() }.filter { it.isNotBlank() },
                author = statValue(doc, "Author"),
                artist = statValue(doc, "Artist"),
                status = statValue(doc, "Status")?.lowercase(),
                contentType = normalizeContentType(statValue(doc, "Type")),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("#chapterlist li[data-num]").mapNotNull { li ->
                // Zamknute (mincove) kapitoly maji misto skutecneho odkazu jen JS modal
                // (`data-bs-toggle="modal"`, zadny `href`) - takove proste vynechame.
                val link = li.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val num = li.attr("data-num").toFloatOrNull() ?: return@mapNotNull null
                val name = link.selectFirst("span.chapternum")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                    ?: "Chapter $num"
                val dateText = link.selectFirst("span.chapterdate")?.text()?.trim()
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = num, dateUpload = parseChapterDate(dateText),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseChapterDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val relative = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(text)
        if (relative != null) {
            val value = relative.groupValues[1].toLongOrNull() ?: 1L
            val deltaMs = when (relative.groupValues[2].lowercase()) {
                "second" -> value * 1_000L
                "minute" -> value * 60_000L
                "hour"   -> value * 3_600_000L
                "day"    -> value * 86_400_000L
                "week"   -> value * 7 * 86_400_000L
                "month"  -> value * 30 * 86_400_000L
                "year"   -> value * 365 * 86_400_000L
                else     -> 0L
            }
            return System.currentTimeMillis() - deltaMs
        }
        return try {
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // Stranky kapitoly jsou v JS blobu `ts_reader.run({...})` - `sources[0].images`
    // pole s primymi URL, zadne dalsi rozlousknuti netreba (overeno zive).
    private val readerJsonRegex = Regex("""ts_reader\.run\((\{.*?\})\);""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val json = readerJsonRegex.find(html)?.groupValues?.get(1) ?: return@withContext emptyList()
            val sources = JSONObject(json).optJSONArray("sources") ?: return@withContext emptyList()
            if (sources.length() == 0) return@withContext emptyList()
            val images = sources.getJSONObject(0).optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapIndexedNotNull { i, _ ->
                val url = images.optString(i)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

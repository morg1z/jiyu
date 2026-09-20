package com.haise.jiyu.source.manhwasusu

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * manhwasusu.com - postaveny na Qwik frameworku (SSR + "resumability"), ale
 * na rozdil od typicke SPA je uz prvni HTML odpoved (i s markerem
 * `q:container="paused"`) plne server-rendered vcetne odkazu, cover URL
 * i (na detailu) JSON-LD `schema.org/ComicSeries` bloku - funguje bez
 * spousteni JS. Vypisove stranky (/popular, /manhwa-list, /search/{q}) ale
 * nemaji klasickou "stranka N = jen N-ta davka" strankovaci logiku - `?page=N`
 * vraci KUMULATIVNE prvnich N*18 polozek (overeno zivě: page=1 -> 18 polozek,
 * page=2 -> 36 = puvodnich 18 + 18 novych na konci, page=3 -> 54), takze
 * getPopular/search rucne oriznou odpovidajici "stranku" z kumulativniho
 * vysledku podle poradi v DOM (ktere je mezi requesty stabilni).
 */
@Singleton
class ManhwaSusuSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "manhwasusu"
    override val name = "ManhwaSusu"
    override val homepageUrl get() = base
    override val isAdult = true
    override val contentType = "MANHWA"

    private val base = "https://manhwasusu.com"
    private val itemsPerPage = 18

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    private fun parseCards(doc: Document): List<SManga> =
        doc.select("a[href^=/read/]:has(img)").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (href.trimEnd('/').endsWith("/read")) return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
            val cover = img.attr("data-src").trim().ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = contentType)
        }.distinctBy { it.url }

    /** Web vraci na "?page=N" kumulativne prvnich N*itemsPerPage polozek (viz komentar u tridy) - oriznuti na skutecnou stranku. */
    private fun slicePage(items: List<SManga>, page: Int): List<SManga> {
        val from = (page - 1) * itemsPerPage
        if (from >= items.size) return emptyList()
        return items.subList(from, minOf(items.size, from + itemsPerPage))
    }

    // Detaily titulu odkazuji na "/tax/genre/{slug}/" archivni stranky (napr.
    // "/tax/genre/action/"), ktere maji stejnou kumulativni "?page=N" strankovaci
    // logiku jako /popular (overeno zive: action ?page=2 obsahuje puvodnich 18 +
    // 18 novych titulu). Web nema zadnou dedikovanou "seznam vsech zanru"
    // stranku ani filtr UI v poc atecnim HTML, takze seznam je rucne sestaveny
    // ze zanru pozorovanych na realnych titulech a kazdy slug je overeny zive
    // (vraci >0 polozek na /tax/genre/{slug}/).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = listOf(
            "action" to "Action",
            "adult" to "Adult",
            "romance" to "Romance",
            "drama" to "Drama",
            "comedy" to "Comedy",
            "fantasy" to "Fantasy",
            "mature" to "Mature",
            "webtoon" to "Webtoon",
            "full-color" to "Full Color",
            "explicit-sex" to "Explicit Sex",
            "borderline-h" to "Borderline H",
            "school-life" to "School Life",
            "harem" to "Harem",
            "supernatural" to "Supernatural",
            "bl" to "BL",
            "yuri" to "Yuri",
            "horror" to "Horror",
            "thriller" to "Thriller",
            "ntr" to "NTR",
        ).map { (slug, label) -> FilterTag(id = slug, label = label) }
        cachedTags = tags
        tags
    }

    private fun genreUrl(slug: String, page: Int) = "$base/tax/genre/$slug/?page=$page"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                val doc = Jsoup.parse(get(genreUrl(filter.genres.first(), page)), base)
                return@withContext slicePage(parseCards(doc), page)
            }
            // "Nejnovejsi" nema na tomhle webu vlastni strankovanou cestu (na rozdil od
            // /popular) - jen pevnou sekci "Latest Updates" na uvodni strance (overeno
            // zivě, jine tituly nez /popular). Stranka 1 ji tedy parsuje primo z domovske
            // stranky, dalsi stranky uz nemaji odkud - prazdny seznam ukonci "nacist dal"
            // stejne jako u vycerpane strankovane odpovedi.
            if (filter.sortBy == "latest") {
                if (page > 1) return@withContext emptyList()
                return@withContext parseCards(Jsoup.parse(get("$base/"), base))
            }
            val doc = Jsoup.parse(get("$base/popular?page=$page"), base)
            slicePage(parseCards(doc), page)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                val doc = Jsoup.parse(get(genreUrl(filter.genres.first(), page)), base)
                return@withContext slicePage(parseCards(doc), page)
            }
            // Query je soucast cesty (/search/{term}), ne query stringu - proto
            // rucni prevod na %20 mista "+" z URLEncoder (overeno zive: %20 funguje, "+" ne).
            val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val doc = Jsoup.parse(get("$base/search/$q?page=$page"), base)
            slicePage(parseCards(doc), page)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── Detail mangy ────────────────────────────────────────────────────────

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            val ld = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { el ->
                try {
                    JSONObject(el.data()).takeIf { it.optString("@type") == "ComicSeries" }
                } catch (e: Exception) { e.rethrowIfControl(); null }
            } ?: return@withContext manga

            val genres = ld.optJSONArray("genre")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            val authors = ld.optJSONArray("author")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            // Stav vydavani ("on-going"/"completed"/...) neni v JSON-LD, jen v barevnem
            // stitku vedle nazvu - hleda se primo podle textu stitku, ne podle (nestabilni) Tailwind tridy.
            val status = doc.select("div:matchesOwn((?i)^on-?going$|^completed$|^hiatus$|^dropped$|^cancelled$)")
                .firstOrNull()?.text()?.trim()

            manga.copy(
                title = ld.optString("name").ifBlank { manga.title },
                description = ld.optString("description").takeIf { it.isNotBlank() },
                coverUrl = ld.optString("image").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                genres = genres,
                author = authors.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
                status = status,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            // Odkazy na "First Chapter"/"Last Chapter" rychlou navigaci taky miri na
            // /chapter-N/, ale nemaji uvnitr <p>Chapter N</p> strukturu jako skutecne
            // polozky seznamu kapitol - timto filtrem se vyradi.
            doc.select("a[href*=/chapter-]").mapNotNull { a ->
                val paragraphs = a.select("p")
                val name = paragraphs.getOrNull(0)?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                if (!name.startsWith("Chapter", ignoreCase = true)) return@mapNotNull null
                val url = a.absUrl("href").ifBlank { return@mapNotNull null }
                val num = parseChapterNumber(name) ?: 0f
                val dateText = paragraphs.getOrNull(1)?.text()?.trim()
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = url, name = name,
                    chapterNumber = num, dateUpload = parseRelativeDate(dateText),
                )
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** "3 hour ago", "1 week ago", "2 mth ago", "1 yr ago" apod. */
    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val m = Regex("""(\d+)\s*(min|hour|day|week|mth|month|yr|year)""", RegexOption.IGNORE_CASE).find(text)
            ?: return System.currentTimeMillis()
        val value = m.groupValues[1].toLongOrNull() ?: 1L
        val unit = m.groupValues[2].lowercase()
        val deltaMs = when {
            unit.startsWith("min") -> value * 60_000L
            unit.startsWith("hour") -> value * 3_600_000L
            unit.startsWith("day") -> value * 86_400_000L
            unit.startsWith("week") -> value * 7 * 86_400_000L
            unit.startsWith("mth") || unit.startsWith("month") -> value * 30 * 86_400_000L
            unit.startsWith("yr") || unit.startsWith("year") -> value * 365 * 86_400_000L
            else -> 0L
        }
        return System.currentTimeMillis() - deltaMs
    }

    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val slug = chapter.url.trimEnd('/').substringAfterLast('/')
            val html = get(chapter.url)
            // Obrazky stranek jsou primo v HTML (src i data-src), jen se hleda podle
            // slugu aktualni kapitoly v ceste, aby se vyfiltrovaly nesouvisejici
            // obrazky (cover, related sekce), ktere slug kapitoly v URL nemaji.
            Regex("""https://[^"'\s]+/${Regex.escape(slug)}/[^"'\s]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value }
                .distinct()
                .toList()
                .mapIndexed { i, url -> Page(i, url, url) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

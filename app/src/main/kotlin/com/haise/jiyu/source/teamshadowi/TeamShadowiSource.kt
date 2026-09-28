package com.haise.jiyu.source.teamshadowi

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.normalizeContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * team-shadowi.com - bespoke server-rendered Next.js aplikace (NE klientska SPA -
 * overeno zive, "/series" i "/series/{slug}" vraci plny HTML obsah bez JS).
 * Maly katalog (8 serii, overeno zive - "/series?page=2" vraci identickou sadu
 * jako strana 1, zadne dalsi strankovani), server-side "?search=" parametr
 * appka tise ignoruje (overeno zive - identicka sada pro ruzne dotazy), proto
 * vypis "/series" jen scrapuje karty `a[href^=/series/]` (titulek z `img[alt]`,
 * obalka primo z `img[src]`, zadny proxy trik) a hledani filtruje lokalne.
 *
 * Detail stranky "/series/{slug}" ukazuje jen poslednich 10 kapitol staticky a
 * zbytek dotahuje tlacitkem "Show More" - metadata serie appka cte z JSON REST
 * endpointu GET "/api/series/{slug}" (title, popis, genres, origination, status,
 * authors/artists). Endpoint uz ale kapitoly NEVRACI (audit 2026-09: klic
 * "chapters" z odpovedi zmizel). Kompletni seznam kapitol VCETNE `image_paths`
 * (hotove absolutni URL vsech stranek) je dnes embedovany v RSC payloadu
 * detailu (`self.__next_f.push` - JSON s escapovanymi uvozovkami \"), jedno
 * HTTP volani tedy pokryva kapitoly i stranky. Kazda kapitola ma i vlastni
 * `is_locked`/`unlock_at` (predplacene predbezne kapitoly) - pokud je zamcena,
 * `image_paths` appka nezkousi cist a vrati prazdny seznam stranek misto pádu
 * (na vsech aktualne testovanych 224+67+104 kapitolach ale `is_locked` vzdy
 * false, zadna zamcena kapitola zatim nenalezena).
 *
 * Zadny zdrojovy seznam zanru pro tag-filter nenalezen (filtr na "/series" je
 * skryty React combobox bez options v initial HTML). Katalog ma ale jen 8
 * serii a kazda nese zanry v "/api/series/{slug}", takze se tag-filter resi
 * LOKALNE: jeden pruchod po seriich vytvori mapu slug->zanry (cachovano) a
 * z ni se odvodi i seznam dostupnych tagu.
 */
@Singleton
class TeamShadowiSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "teamshadowi"
    override val name = "TeamShadowi"
    override val contentType = "MANHWA"
    override val homepageUrl get() = base
    override val supportsTagFilter: Boolean get() = true
    private val base = "https://www.team-shadowi.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=/series/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val slug = href.removePrefix("/series/").trim('/')
            if (slug.isBlank() || slug.contains('/')) return@mapNotNull null
            val img = a.selectFirst("img")
            // img[alt] web nove necha prazdny - titul je v <h3> uvnitr karty
            // (audit 2026-09: article > a > div > img[alt=""] + h3 s nazvem).
            val title = img?.attr("alt")?.trim()?.ifBlank { null }
                ?: a.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = img?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = slug, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    /** Slugy v pořadí, v jakém je web ukazuje na dané stránce (`/popular`, `/latest`) - ty samy karty, jiné řazení. */
    internal fun slugOrder(html: String): List<String> =
        Jsoup.parse(html).select("a[href^=/series/]")
            .map { it.attr("href").removePrefix("/series/").trim('/') }
            .filter { it.isNotBlank() && !it.contains('/') }
            .distinct()

    // Katalog má celkem jen 8 titulů (ověřeno i přes sitemap.xml) a "/series" je vrací v pevném pořadí.
    // "Populární" a "Nejnovější" se liší jen pořadím: web má vlastní stránky "/popular" (všech 8 podle
    // popularity) a "/latest" (jen nedávno aktualizované) - z nich se bere pořadí, obsah karet (titulek,
    // obálka) zůstává z "/series". Tituly, které v pořadí nejsou, jdou na konec v původním pořadí.
    // Mapa slug->zanry z "/api/series/{slug}" pro cely (maly, 8 titulu) katalog -
    // zaklad lokalniho tag-filtru i nabidky tagu. Cachuje se, protoze jeden
    // pruchod znamena N requestu (po jednom na serii).
    @Volatile private var cachedTags: List<FilterTag>? = null
    @Volatile private var genreMap: Map<String, List<String>>? = null

    private fun fetchGenreMap(): Map<String, List<String>> {
        genreMap?.let { return it }
        val map = try {
            parseList(get("$base/series")).associate { card ->
                val genres = try {
                    fetchSeriesJson(card.url).optJSONArray("genres")?.toStringList() ?: emptyList()
                } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
                card.url to genres
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyMap() }
        if (map.isNotEmpty()) genreMap = map
        return map
    }

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            fetchGenreMap().values.flatten().distinct().sorted()
                .map { FilterTag(id = it, label = it) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page > 1) return@withContext emptyList()
            val cards = parseList(get("$base/series")).let { list ->
                if (filter.genres.isEmpty()) list else {
                    val wanted = filter.genres.toSet()
                    val genres = fetchGenreMap()
                    list.filter { m -> genres[m.url].orEmpty().containsAll(wanted) }
                }
            }
            val orderPage = if (filter.sortBy == "latest") "latest" else "popular"
            val order = try {
                slugOrder(get("$base/$orderPage"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
            if (order.isEmpty()) cards
            else cards.sortedBy { card -> order.indexOf(card.url).let { if (it < 0) Int.MAX_VALUE else it } }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Server-side "?search=" parametr nefunguje (viz komentar u tridy) - hledani
    // proto stahne cely (maly, 8 titulu) katalog a filtruje podle titulku lokalne.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            val q = query.trim()
            parseList(get("$base/series")).let { list ->
                val byTitle = if (q.isBlank()) list else list.filter { it.title.contains(q, ignoreCase = true) }
                if (filter.genres.isEmpty()) byTitle else {
                    val wanted = filter.genres.toSet()
                    val genres = fetchGenreMap()
                    byTitle.filter { m -> genres[m.url].orEmpty().containsAll(wanted) }
                }
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun fetchSeriesJson(slug: String): JSONObject =
        JSONObject(get("$base/api/series/$slug")).getJSONObject("series")

    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { i ->
        optString(i).trim().ifBlank { null }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val s = fetchSeriesJson(manga.url)
            manga.copy(
                title = s.optString("title").trim().ifBlank { manga.title },
                coverUrl = s.optString("thumbnail_url").trim().ifBlank { null } ?: manga.coverUrl,
                description = s.optString("description").trim().ifBlank { null },
                genres = s.optJSONArray("genres")?.toStringList() ?: manga.genres,
                alternateTitles = s.optJSONArray("alt_titles")?.toStringList() ?: manga.alternateTitles,
                status = s.optString("status").trim().lowercase().ifBlank { null },
                contentType = normalizeContentType(s.optString("origination"), default = "MANHWA"),
                author = s.optJSONArray("authors")?.toStringList()?.joinToString(", ")?.ifBlank { null },
                artist = s.optJSONArray("artists")?.toStringList()?.joinToString(", ")?.ifBlank { null },
                rating = if (s.has("average_rating")) s.optDouble("average_rating") else null,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    /** Kapitola vytezena z RSC payloadu detailu serie (escapovany JSON v __next_f). */
    private data class EmbeddedChapter(
        val number: Float,
        val title: String?,
        val createdAt: Long,
        val isLocked: Boolean,
        val imagePaths: List<String>,
    )

    // RSC payload drzi cely JSON s escapovanymi uvozovkami (\"). Objekt kapitoly
    // zacina vzdy {"id":"<uuid>"} a number/title/image_paths maji pevne klice -
    // regex po objektech je bezpecnejsi nez se pokouset unescapovat cely chunk.
    private val chapterRe = Regex(
        """\\"number\\":\\"([0-9.]+)\\",\\"title\\":\\"(.*?)\\",\\"volume\\":[^,]*?,\\"image_paths\\":\[(.*?)\]""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val imageUrlRe = Regex("""\\"(https?://[^\\"]+)\\"""")
    private val isLockedRe = Regex("""\\"is_locked\\":(true|false)""")
    // RSC datum ma prefix "$D" (React Server Components date encoding).
    private val createdAtRe = Regex("""\\"created_at\\":\\"(?:\${'$'}D)?([^\\"]+)\\"""")

    private fun extractChapters(html: String): List<EmbeddedChapter> {
        // Vazeme se na pole "chapters" uvnitr payloadu - stejne klice se muzou
        // vyskytovat i jinde ve strance.
        val start = html.indexOf("""\"chapters\":[""")
        if (start < 0) return emptyList()
        val scope = html.substring(start)
        val matches = chapterRe.findAll(scope).toList()
        return matches.mapIndexedNotNull { i, m ->
            // Text za koncem shody az do dalsi kapitoly - z nej se ctou is_locked/created_at.
            val tailEnd = matches.getOrNull(i + 1)?.range?.first ?: m.range.last + 80_000
            val tail = scope.substring(m.range.last + 1, minOf(tailEnd, scope.length))
            EmbeddedChapter(
                number = m.groupValues[1].toFloatOrNull() ?: return@mapIndexedNotNull null,
                title = m.groupValues[2].ifBlank { null },
                createdAt = createdAtRe.find(tail)?.groupValues?.get(1)?.let(::parseIsoDate) ?: 0L,
                isLocked = isLockedRe.find(tail)?.groupValues?.get(1) == "true",
                imagePaths = imageUrlRe.findAll(m.groupValues[3]).map { it.groupValues[1] }.toList(),
            )
        }
    }

    private fun fetchEmbeddedChapters(slug: String): List<EmbeddedChapter> =
        extractChapters(get("$base/series/$slug"))

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            fetchEmbeddedChapters(manga.url).map { c ->
                val num = c.number
                // "url" musi byt globalne unikatni napric CELYM zdrojem (pouziva se jako
                // "$sourceId::$url" DB klic v MangaRepository.chapterId) - samotne cislo
                // kapitoly by kolidovalo mezi ruznymi seriemi, proto prefix slugem serie.
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = "${manga.url}/$num",
                    name = c.title?.let { "Chapter $num: $it" } ?: "Chapter $num",
                    chapterNumber = num, dateUpload = c.createdAt,
                )
            }.sortedByDescending { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseIsoDate(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val match = fetchEmbeddedChapters(chapter.mangaUrl)
                .firstOrNull { it.number == chapter.chapterNumber }
                ?: return@withContext emptyList()
            if (match.isLocked) return@withContext emptyList()
            match.imagePaths.mapIndexed { i, url -> Page(i, url, url) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

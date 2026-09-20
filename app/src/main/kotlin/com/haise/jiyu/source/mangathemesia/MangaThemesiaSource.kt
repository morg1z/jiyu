package com.haise.jiyu.source.mangathemesia

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.source.comments.ChapterComment
import com.haise.jiyu.source.comments.parseWpDiscuzComments
import com.haise.jiyu.util.SourceParseException
import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.normalizeContentType
import com.haise.jiyu.util.parseChapterDate
import com.haise.jiyu.util.resolveSourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CSS selektory MangaThemesia/MangaStream markupu. Výchozí hodnoty odpovídají nezměněnému tématu;
 * web, který téma upravil, přepíše jen to, co mu nesedí.
 */
data class MangaThemesiaSelectors(
    val listItem: String = ".bsx",
    val listTitle: String = ".tt",
    /** Kontejner seznamu na stránce výpisu - jeho úplná absence u neprázdné odpovědi znamená změněné HTML. */
    val listContainer: String = ".listupd, .bixbox, .postbody",
    val genreInput: String = "input[name='genre[]']",
    val title: String = "h1.entry-title",
    val cover: String = "div.thumb img",
    val description: String = ".entry-content-single, [itemprop=description]",
    val genres: String = ".mgen a, .seriestugenre a",
    val chapterContainer: String = "#chapterlist, div.eplister",
    val chapterItem: String = "li",
    val chapterNumber: String = "span.chapternum",
    val chapterDate: String = "span.chapterdate",
    val readerImages: String = "#readerarea img",
)

/**
 * Generický zdroj pro weby na WordPress tématu MangaThemesia (dříve MangaStream): karty `.bsx`, seznam
 * kapitol `div.eplister`/`#chapterlist`, stránky kapitoly v `ts_reader.run({... "images":[...]})`.
 * Vzor je stejný jako u [com.haise.jiyu.source.madara.MadaraSource]: nový web = jeden řádek v `SourceManager`,
 * odchylky se řeší parametry a [MangaThemesiaSelectors], ne novou třídou.
 *
 * Adresy titulů a kapitol se ukládají tak, jak je web dává (absolutní URL), aby zůstaly shodné s tím, co už
 * mají uživatelé v knihovně z dřívějších samostatných zdrojů.
 *
 * Chybějící očekávaný kontejner (výpis, seznam kapitol, stránky) je [SourceParseException], ne tichý prázdný
 * seznam - viz `SourceExceptions.kt`. Legitimně prázdný výsledek (hledání bez shody, titul bez kapitol) zůstává
 * prázdný seznam.
 */
class MangaThemesiaSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val selectors: MangaThemesiaSelectors = MangaThemesiaSelectors(),
    private val contentTypeOverride: String = "MANGA",
    private val isAdultOverride: Boolean = false,
    private val languageOverride: String = "en",
    private val userAgent: String = SourceHttp.USER_AGENT_DESKTOP,
    private val sendReferer: Boolean = false,
    /** `/manga/page/N/?order=` místo `/manga/?page=N&order=` (obě varianty WordPress umí, weby preferují jednu). */
    private val pathPagination: Boolean = false,
    /** Žánry jako archiv `/genres/{slug}/` (id filtru = slug) místo `/manga/?genre[]=id`. */
    private val genreArchive: Boolean = false,
    private val hasChapterComments: Boolean = false,
    /** Cesta výpisu titulů ("manga" u většiny webů, jinde "comics", "series" ...). */
    private val listPath: String = "manga",
    /** Vzor data kapitoly konkrétního webu (např. "dd/MM/yyyy"); zkouší se před obecným parserem. */
    private val datePattern: String? = null,
    private val inGlobalSearch: Boolean = true,
    /** Web používá lehkou JS výzvu NetShield (viz [NetShieldSolver]); vyžaduje [jsRunner]. */
    private val netShield: Boolean = false,
    private val jsRunner: com.haise.jiyu.util.JsRunner? = null,
) : MangaSource {

    override val includeInGlobalSearch: Boolean get() = inGlobalSearch

    override val contentType: String get() = contentTypeOverride
    override val language: String get() = languageOverride
    override val isAdult: Boolean get() = isAdultOverride
    override val homepageUrl: String get() = baseUrl
    override val supportsTagFilter: Boolean get() = true
    override val availableSorts: Set<String> get() = setOf("popular", "latest", "title")
    override val supportsChapterComments: Boolean get() = hasChapterComments

    private val root get() = baseUrl.trimEnd('/')

    private fun rawGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .apply { if (sendReferer) header("Referer", root) }
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // Lehká JS výzva NetShield (viz NetShieldSolver): když web vrátí skript místo stránky, spočítá se cookie a
    // požadavek se jednou zopakuje. Bez zapnutého netShield/JsRunneru se chová jako dřív.
    private val netShieldSolver: NetShieldSolver? =
        if (netShield && jsRunner != null) NetShieldSolver(client, jsRunner) else null

    private suspend fun get(url: String): String {
        val html = rawGet(url)
        val solver = netShieldSolver
        if (solver != null && solver.isChallenge(html) && solver.solve(root, html)) return rawGet(url)
        return html
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    // ─── Výpis a hledání ─────────────────────────────────────────────────────

    private fun parseList(html: String, url: String, mustHaveContainer: Boolean): List<SManga> {
        val doc = Jsoup.parse(html, root)
        val cards = doc.select(selectors.listItem)
        if (cards.isEmpty() && mustHaveContainer && html.isNotBlank() && doc.selectFirst(selectors.listContainer) == null) {
            throw SourceParseException("Výpis titulů nenalezen (změněné HTML?)", url)
        }
        return cards.mapNotNull { el ->
            val link = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { el.selectFirst(selectors.listTitle)?.text().orEmpty() }
                .trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.lazySrc()?.let { resolveSourceUrl(root, it) }
            SManga(
                sourceId = id,
                url = resolveSourceUrl(root, link.attr("href")),
                title = title,
                coverUrl = cover,
                contentType = contentTypeOverride,
            )
        }.distinctBy { it.url }
    }

    private fun orderFor(filter: MangaFilter) = when (filter.sortBy) {
        "latest" -> "update"
        "title" -> "title"
        else -> "popular"
    }

    private fun genreUrl(genres: List<String>, page: Int): String =
        if (genreArchive) {
            val slug = genres.first()
            if (page <= 1) "$root/genres/$slug/" else "$root/genres/$slug/page/$page/"
        } else {
            archiveUrl(page, orderQuery = null, genres = genres)
        }

    private fun archiveUrl(page: Int, orderQuery: String?, genres: List<String>): String {
        val params = buildList {
            orderQuery?.let { add("order=$it") }
            genres.forEach { add("genre%5B%5D=${encode(it)}") }
            if (!pathPagination) add("page=$page")
        }
        val path = if (pathPagination && page > 1) "$root/$listPath/page/$page/" else "$root/$listPath/"
        return if (params.isEmpty()) path else "$path?${params.joinToString("&")}"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val genres = filter.genres
        val url = if (genres.isNotEmpty()) genreUrl(genres, page) else archiveUrl(page, orderFor(filter), emptyList())
        parseList(get(url), url, mustHaveContainer = genres.isEmpty() && page <= 1)
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Genre filtr se s "?s=" fulltextem nekombinuje (WP search ho tiše ignoruje) - při vybraném žánru se
        // proto použije rovnou filtrovaný výpis, stejně jako u MadaraSource.
        val url = if (filter.genres.isNotEmpty()) {
            genreUrl(filter.genres, page)
        } else {
            val q = encode(query)
            if (page <= 1) "$root/?s=$q" else "$root/page/$page/?s=$q"
        }
        parseList(get(url), url, mustHaveContainer = false)
    }

    // ─── Žánry ───────────────────────────────────────────────────────────────

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val url = "$root/$listPath/"
        val doc = Jsoup.parse(get(url), root)
        val tags = doc.select(selectors.genreInput).mapNotNull { input ->
            val value = input.attr("value").trim().ifBlank { return@mapNotNull null }
            val id = input.attr("id")
            val label = (if (id.isNotBlank()) doc.selectFirst("label[for=$id]") else null)?.text()
                ?: input.parent()?.selectFirst("label")?.text()
                ?: input.parent()?.text()
            FilterTag(id = value, label = label?.trim()?.ifBlank { null } ?: value)
        }.distinctBy { it.id }
        if (tags.isEmpty()) throw SourceParseException("Seznam žánrů nenalezen", url)
        cachedTags = tags
        tags
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    /** Pole z info karty: řádek tabulky (`<td>Label</td><td>Hodnota</td>`) nebo `div.imptdt` (`Label <i>Hodnota</i>`). */
    private fun infoField(doc: Document, label: String): String? {
        doc.select("tr").firstOrNull { tr ->
            tr.select("td").let { it.size >= 2 && it[0].text().trim().equals(label, ignoreCase = true) }
        }?.select("td")?.get(1)?.text()?.trim()?.ifBlank { null }?.let { return it }
        doc.select("div.imptdt").firstOrNull { d ->
            val head = d.selectFirst("h1")?.text() ?: d.ownText()
            head.trim().startsWith(label, ignoreCase = true)
        }?.selectFirst("i")?.text()?.trim()?.ifBlank { null }?.let { return it }
        return null
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val url = resolveSourceUrl(root, manga.url)
        val doc = Jsoup.parse(get(url), url)
        val genres = doc.select(selectors.genres).map { it.text().trim() }.filter { it.isNotBlank() }
            .ifEmpty { doc.select("a[href*=/genres/]").map { it.text().trim() }.filter { it.isNotBlank() } }
            .distinct()
        manga.copy(
            title = doc.selectFirst(selectors.title)?.text()?.trim()?.ifBlank { null } ?: manga.title,
            coverUrl = doc.selectFirst(selectors.cover)?.lazySrc()?.let { resolveSourceUrl(root, it) } ?: manga.coverUrl,
            description = doc.selectFirst(selectors.description)?.text()?.trim()?.ifBlank { null },
            genres = genres.ifEmpty { manga.genres },
            author = infoField(doc, "Author"),
            artist = infoField(doc, "Artist"),
            status = infoField(doc, "Status")?.lowercase(),
            contentType = normalizeContentType(infoField(doc, "Type"), default = contentTypeOverride),
        )
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    private val numberRegex = Regex("""(\d+(?:\.\d+)?)""")

    private fun chapterDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val locale = java.util.Locale.forLanguageTag(languageOverride)
        datePattern?.let { pattern ->
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, locale).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(text.trim())?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return parseChapterDate(text, locale)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val url = resolveSourceUrl(root, manga.url)
        val doc = Jsoup.parse(get(url), url)
        val container = doc.selectFirst(selectors.chapterContainer)
            ?: throw SourceParseException("Seznam kapitol nenalezen (změněné HTML?)", url)
        val items = container.select(selectors.chapterItem).filter { it.selectFirst("a[href]") != null }
        items.mapIndexedNotNull { i, li ->
            val link = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val href = resolveSourceUrl(root, link.attr("href")).ifBlank { return@mapIndexedNotNull null }
            val rawName = (li.selectFirst(selectors.chapterNumber) ?: link).text().replace(Regex("""\s+"""), " ").trim()
            val num = li.attr("data-num").toFloatOrNull()
                ?: numberRegex.find(rawName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: (items.size - i).toFloat()
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = href,
                name = rawName.ifBlank { "Chapter $num" },
                chapterNumber = num,
                dateUpload = chapterDate(li.selectFirst(selectors.chapterDate)?.text()),
            )
        }.distinctBy { it.url }
    }

    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    // Závorky `}` a `]` jsou escapované záměrně: regex engine Androidu (ICU) na rozdíl od JVM (kde běží testy) odmítne
    // neescapovanou uzavírací závorku a appka by spadla už při vytvoření zdroje.
    private val tsReaderRegex = Regex("""ts_reader\.run\((\{.*?\})\);""", RegexOption.DOT_MATCHES_ALL)
    private val imagesRegex = Regex(""""images":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = resolveSourceUrl(root, chapter.url)
        val html = get(url)

        tsReaderRegex.find(html)?.groupValues?.get(1)?.let { json ->
            val images = runCatching {
                JSONObject(json).optJSONArray("sources")?.optJSONObject(0)?.optJSONArray("images")
            }.getOrNull()
            if (images != null) {
                return@withContext (0 until images.length()).mapNotNull { i ->
                    images.optString(i).takeIf { it.isNotBlank() }
                }.mapIndexed { i, u -> Page(i, u, u) }
            }
        }
        imagesRegex.find(html)?.groupValues?.get(1)?.let { raw ->
            val urls = Regex(""""([^"]+)"""").findAll(raw).map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.isNotBlank() }.toList()
            return@withContext urls.mapIndexed { i, u -> Page(i, u, u) }
        }

        val doc = Jsoup.parse(html, url)
        val imgs = doc.select(selectors.readerImages)
        if (imgs.isEmpty() && doc.selectFirst("#readerarea") == null) {
            throw SourceParseException("Stránky kapitoly nenalezeny (změněné HTML?)", url)
        }
        imgs.mapNotNull { it.lazySrc()?.let { u -> resolveSourceUrl(root, u) } }
            .distinct().mapIndexed { i, u -> Page(i, u, u) }
    }

    override suspend fun getChapterComments(chapter: SChapter): List<ChapterComment> {
        if (!hasChapterComments) return emptyList()
        return withContext(Dispatchers.IO) {
            parseWpDiscuzComments(Jsoup.parse(get(resolveSourceUrl(root, chapter.url))))
        }
    }
}

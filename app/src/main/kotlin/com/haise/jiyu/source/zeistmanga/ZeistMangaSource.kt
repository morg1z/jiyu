package com.haise.jiyu.source.zeistmanga

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.SourceParseException
import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.resolveSourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * Generický zdroj pro weby na Blogger šabloně "ZeistManga". Web je obyčejný blog: seznam titulů, hledání i
 * kapitoly se berou z JSON feedu Bloggeru (`/feeds/posts/default/-/<štítek>?alt=json`), ne z HTML výpisu - titul
 * je příspěvek se štítkem [mangaCategory] ("Series"), kapitoly jsou příspěvky se štítkem názvu titulu.
 *
 * Detail a stránky kapitoly se čtou z HTML příspěvku; šablon je hodně variant, proto se zkouší několik míst
 * (skript s polem obrázků, skript s HTML v backticích, jinak `<img>` podle [selectPage]). Web s vlastní úpravou
 * přepíše jen [selectPage] / [selectTags].
 *
 * Feed umí jen řazení podle data vydání, takže zdroj nabízí jediné řazení "latest" a stavový filtr nemá.
 * Do globálního hledání se nepřidává (každé hledání je samostatný dotaz na Blogger).
 *
 * Adresy titulů a kapitol se ukládají absolutní (tak je dává feed).
 */
class ZeistMangaSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val languageOverride: String = "en",
    private val contentTypeOverride: String = "MANGA",
    private val isAdultOverride: Boolean = false,
    /** Štítek, kterým web označuje tituly (u překladů "Series", "Seri", "Seriler"...). */
    private val mangaCategory: String = "Series",
    private val selectPage: String = DEFAULT_SELECT_PAGE,
    private val selectTags: String = DEFAULT_SELECT_TAGS,
    private val pageSize: Int = 20,
    private val userAgent: String = SourceHttp.USER_AGENT_DESKTOP,
) : MangaSource {

    override val contentType: String get() = contentTypeOverride
    override val language: String get() = languageOverride
    override val isAdult: Boolean get() = isAdultOverride
    override val homepageUrl: String get() = baseUrl
    override val supportsTagFilter: Boolean get() = true
    override val availableSorts: Set<String> get() = setOf("latest")
    override val includeInGlobalSearch: Boolean get() = false

    private val root get() = baseUrl.trimEnd('/')

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ─── Výpis a hledání ─────────────────────────────────────────────────────

    private fun feedUrl(label: String, maxResults: Int, startIndex: Int, query: String? = null): String = buildString {
        append(root).append("/feeds/posts/default/-/").append(encode(label))
        append("?alt=json&orderby=published&max-results=").append(maxResults)
        append("&start-index=").append(startIndex)
        if (!query.isNullOrBlank()) append("&q=label:").append(encode(label)).append('+').append(encode(query))
    }

    private fun startIndex(page: Int) = pageSize * (page.coerceAtLeast(1) - 1) + 1

    private fun entries(json: String, url: String): List<JSONObject> {
        val feed = try {
            JSONObject(json).getJSONObject("feed")
        } catch (e: org.json.JSONException) {
            throw SourceParseException("Feed není platný JSON (změněný web?)", url)
        }
        val arr = feed.optJSONArray("entry") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun JSONObject.alternateLink(): String? {
        val links = optJSONArray("link") ?: return null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            if (l.optString("rel") == "alternate") return l.optString("href").ifBlank { null }
        }
        return null
    }

    private fun JSONObject.text(key: String): String = optJSONObject(key)?.optString("\$t").orEmpty()

    /** Blogger vrací miniaturu jako `.../s72-c/x.jpg` (nebo `=s72-c`) - nahradí se za 600 px širokou verzi. */
    internal fun bigCover(raw: String): String = raw
        .replace(Regex("""/s\d+(?:-[a-z]+)*/"""), "/w600/")
        .replace(Regex("""=s\d+(?:-[a-z]+)*$"""), "=w600")

    internal fun parseList(json: String, url: String): List<SManga> = entries(json, url).mapNotNull { e ->
        val href = e.alternateLink() ?: return@mapNotNull null
        val title = e.text("title").trim().ifBlank { return@mapNotNull null }
        val cover = e.optJSONObject("media\$thumbnail")?.optString("url")?.ifBlank { null }?.let(::bigCover)
            ?: Jsoup.parse(e.text("content")).selectFirst("img")?.lazySrc()
        SManga(sourceId = id, url = href, title = title, coverUrl = cover?.let { resolveSourceUrl(root, it) },
            contentType = contentTypeOverride)
    }.distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val label = filter.genres.firstOrNull() ?: mangaCategory
        val url = feedUrl(label, pageSize, startIndex(page))
        parseList(get(url), url)
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val url = feedUrl(mangaCategory, pageSize, startIndex(page), query)
        parseList(get(url), url)
    }

    // ─── Žánry ───────────────────────────────────────────────────────────────

    @Volatile private var cachedTags: List<FilterTag>? = null

    /** Žánry jsou v boxu `div.filter` na hlavní stránce (checkboxy); web bez něj žádný filtr nenabídne. */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val doc = Jsoup.parse(get(root), root)
        val tags = doc.select("div.filter ul li").mapNotNull { li ->
            val value = li.selectFirst("input")?.attr("value")?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val label = li.selectFirst("label")?.text()?.trim().orEmpty().ifBlank { value }
            FilterTag(id = value, label = label)
        }.distinctBy { it.id }
        cachedTags = tags
        tags
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    private val statusLabels = mapOf(
        "ongoing" to "ongoing", "en curso" to "ongoing", "ativo" to "ongoing", "lançando" to "ongoing",
        "مستمر" to "ongoing", "مستمرة" to "ongoing", "devam ediyor" to "ongoing", "güncel" to "ongoing",
        "en emisión" to "ongoing", "activo" to "ongoing",
        "completed" to "completed", "completo" to "completed", "tamamlandı" to "completed",
        "finalizado" to "completed", "مكتمل" to "completed", "مكتملة" to "completed",
        "cancelled" to "cancelled", "dropped" to "cancelled", "dropado" to "cancelled", "abandonado" to "cancelled",
        "cancelado" to "cancelled", "suspendido" to "cancelled", "متوقفة" to "cancelled",
        "hiatus" to "hiatus",
    )

    private fun firstText(doc: Document, vararg selectors: String): String? =
        selectors.firstNotNullOfOrNull { s -> doc.selectFirst(s)?.text()?.trim()?.ifBlank { null } }

    internal fun parseDetails(doc: Document, manga: SManga): SManga {
        val status = firstText(
            doc, "div.y6x11p:contains(Status) .dt", "div.y6x11p:contains(Estado) .dt",
            "ul.infonime li:contains(Status) span", "ul.infonime li:contains(Estado) span",
            "span.status-novel", "span[data-status]",
        )?.let { statusLabels[it.lowercase()] }
        val author = firstText(
            doc, "div.y6x11p:contains(Author) .dt", "div.y6x11p:contains(Autor) .dt",
            "div.y6x11p:contains(Yazar) .dt", "div.y6x11p:contains(الكاتب) .dt",
            "dl:contains(Author) dd", "ul.infonime li:contains(Author) span",
        )
        val description = (doc.getElementById("synopsis") ?: doc.getElementById("Sinopse") ?: doc.getElementById("sinopas")
            ?: doc.selectFirst(".sinopsis") ?: doc.selectFirst(".sinopas"))?.text()?.trim()?.ifBlank { null }
        val genres = doc.select(selectTags).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        return manga.copy(
            description = description ?: manga.description,
            author = author ?: manga.author,
            status = status ?: manga.status,
            genres = genres.ifEmpty { manga.genres },
        )
    }

    private fun detailDoc(url: String): Document = Jsoup.parse(get(url), url)

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val url = resolveSourceUrl(root, manga.url)
        parseDetails(detailDoc(url), manga)
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    private val labelRegex = Regex("""label\s*=\s*'([^']+)'""")
    private val clwdRegex = Regex("""clwd\.run\('([^']+)'""")
    private val numberRegex = Regex("""(\d+(?:\.\d+)?)""")

    /** Štítek, pod kterým jsou kapitoly titulu - každá varianta šablony ho ukládá jinam. */
    internal fun chapterLabel(doc: Document, url: String): String {
        doc.getElementById("myUL")?.selectFirst("script")?.attr("src")?.takeIf { it.contains("/-/") }?.let { src ->
            return URLDecoder.decode(src.substringAfterLast("/-/").substringBefore("?"), "UTF-8")
        }
        doc.selectFirst("#latest > script")?.let { s ->
            labelRegex.find(s.html())?.groupValues?.get(1)?.let { return it }
        }
        doc.selectFirst("#clwd > script")?.let { s ->
            clwdRegex.find(s.html())?.groupValues?.get(1)?.let { return it }
        }
        doc.selectFirst("#chapterlist")?.attr("data-post-title")?.takeIf { it.isNotBlank() }?.let { return it }
        doc.selectFirst("script:containsData(var label_chapter)")?.data()
            ?.substringAfter("label_chapter = \"", "")?.substringBefore("\"")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        throw SourceParseException("Štítek kapitol nenalezen (změněná šablona?)", url)
    }

    internal fun parseChapters(json: String, feedUrl: String, mangaUrl: String): List<SChapter> {
        val mangaSlug = mangaUrl.trimEnd('/').substringAfterLast('/').substringBefore('.')
        val items = entries(json, feedUrl).mapNotNull { e ->
            val href = e.alternateLink() ?: return@mapNotNull null
            // Samotný titul má štítek taky - v seznamu kapitol být nemá.
            if (href.trimEnd('/').substringAfterLast('/').substringBefore('.') == mangaSlug) return@mapNotNull null
            Triple(e.text("title").trim(), href, e.text("published"))
        }
        // Feed je od nejnovějšího; pořadí kapitol se tím pádem dá použít jako záložní číslo (nejstarší = 1).
        return items.mapIndexed { i, (title, href, published) ->
            SChapter(
                sourceId = id,
                mangaUrl = mangaUrl,
                url = href,
                name = title.ifBlank { "Chapter ${items.size - i}" },
                chapterNumber = numberRegex.findAll(title).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (items.size - i).toFloat(),
                dateUpload = parsePublished(published),
            )
        }
    }

    private fun parsePublished(text: String): Long =
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrDefault(0L)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val url = resolveSourceUrl(root, manga.url)
        val label = chapterLabel(detailDoc(url), url)
        val feed = "$root/feeds/posts/default/-/${encode(label)}?alt=json&orderby=published&max-results=9999"
        parseChapters(get(feed), feed, manga.url)
    }

    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    internal fun parsePages(html: String, url: String): List<Page> {
        val doc = Jsoup.parse(html, url)
        val urls: List<String> = run {
            doc.selectFirst("script:containsData(chapterImage =)")?.data()?.let { data ->
                val arr = data.substringAfter("[").substringBefore("]")
                return@run arr.split(',').map { it.replace(" ", "").replace("\"", "").replace("'", "") }
            }
            doc.selectFirst("script:containsData(const content = )")?.data()?.let { data ->
                val body = data.substringAfter("`").substringBefore("`;")
                return@run body.split("src=\"").drop(1).map { it.substringBefore("\"") }
            }
            doc.select(selectPage).mapNotNull { it.lazySrc() }
        }
        val pages = urls.filter { it.isNotBlank() }.map { resolveSourceUrl(root, it) }.distinct()
        if (pages.isEmpty()) throw SourceParseException("Stránky kapitoly nenalezeny (změněná šablona?)", url)
        return pages.mapIndexed { i, u -> Page(i, u, u) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = resolveSourceUrl(root, chapter.url)
        parsePages(get(url), url)
    }

    companion object {
        const val DEFAULT_SELECT_PAGE =
            "div.check-box img, article#reader .separator img, article.container .separator img, #readarea img, #reader img, #readerarea img"
        const val DEFAULT_SELECT_TAGS = "article div.mt-15 a, .info-genre a, dl:contains(Genre) dd a"
    }
}

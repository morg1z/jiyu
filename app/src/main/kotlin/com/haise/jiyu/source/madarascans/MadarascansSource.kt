package com.haise.jiyu.source.madarascans

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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Madarascans" (uvedeno v zadani jako madarascans.com) - tahle domena presmerovava
 * (301, overeno zive) na skutecny web "madascans.com". NENI to genuine Madara motiv
 * (zadny "Powered by Madara"/wp-manga struktura) - jde o silne prebrandovany fork
 * stejneho "ts_reader"/"ts_configs" enginu jako EvaScansSource ("mangareader" WP
 * tema misto Madara, skript-id "evascans_features-js" na tomhle webu prozrazuje
 * spolecny puvod sablony), ale s uplne jinymi CSS tridami/markupem ("Legendary/Magma"
 * reskin) - proto vlastni trida, ne MadaraSource.
 *
 * Katalog "/browse-manga/" (+ "/browse-manga/page/N/" strankovani) je server-rendered
 * HTML, karty `article.snap-card` (`a.snap-poster img` = obalka, `h3.snap-title a` =
 * titulek+odkaz). Razeni "?order=new|popular|title" i zanrovy filtr "&genre=slug" jsou
 * FUNKCNI a kombinovatelne (overeno zive - ruzne "order"/"genre" kombinace vraci ruzne
 * sady titulu). Seznam zanru je v modalu na strance `button.genre-select-item[data-slug]`
 * (46 zanru, overeno zive). Fulltextove hledani je genuine WordPress `/?s=query` (overeno
 * zive - vraci stejne `snap-card` karty), ale nekombinuje se s zanrovym filtrem - pri
 * zvolenem zanru appka proto (stejne jako MadaraSource/EvaScans) prepne na archiv misto
 * fulltextu.
 *
 * Detail mangy nema zadne oznaceni typu (Manga/Manhwa/Manhua) nikde v markupu (overeno
 * zive) - contentType tak zustava na vychozi hodnote SManga. Status je primo text v
 * `.lh-meta-item` badge s ikonou "fa-info-circle" (ne label/hodnota dvojice), hodnoceni
 * v badge s ikonou "fa-star". Zanry `.lh-genres a.lh-genre-tag`, popis `#manga-story`.
 *
 * Kompletni seznam kapitol je primo v detailu (`#chapters-list-container .ch-item[data-id]
 * [data-ch]`, "nejnovejsi napred") - zadne dalsi strankovani/AJAX netreba, i "skryte" (za
 * tlacitkem "Load More") polozky jsou uz v puvodnim HTML, jen s CSS tridou "ch-hidden"
 * (overeno zive na 103-kapitolove serii - Jsoup je najde vsechny bez ohledu na tuhle
 * tridu). Zamcene (za mincemi) kapitoly maji navic tridu "locked" a `span.coin-price`.
 *
 * Stranky kapitoly NEJSOU v HTML jako <img> tagy - jsou v JS bloku `ts_reader.run({...})`
 * primo na strance jako JSON ("sources":[{"images":[...]}]), appka ho proto vytahne
 * balancovanim zavorek a naparsuje jako JSON (org.json). POZOR: nekterym kapitolam (i
 * volne pristupnym, overeno zive napr. na "yakuza-reincarnation-chapter-50") web sam
 * vraci prazdne "images":[] - jde o mezeru v datech zdroje samotneho (ne o
 * bot-detekci/blokaci - vetsina kapitol obrazky ma, overeno zive na nekolika ruznych
 * seriich), getPageList v takovem pripade prirozene vrati prazdny seznam bez pádu.
 */
@Singleton
class MadarascansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "madarascans"
    override val name = "Madarascans"
    override val homepageUrl get() = base
    private val base = "https://madascans.com"

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/browse-manga/"))
            val tags = doc.select("button.genre-select-item[data-slug]").mapNotNull { btn ->
                val slug = btn.attr("data-slug").trim().ifBlank { return@mapNotNull null }
                val label = btn.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun orderParam(sortBy: String): String = when (sortBy) {
        "popular" -> "popular"
        "title" -> "title"
        else -> "new"
    }

    private fun browseUrl(page: Int, sortBy: String, genre: String?): String {
        val path = if (page <= 1) "$base/browse-manga/" else "$base/browse-manga/page/$page/"
        val params = mutableListOf("order=${orderParam(sortBy)}")
        if (genre != null) params += "genre=${URLEncoder.encode(genre, "UTF-8")}"
        return "$path?" + params.joinToString("&")
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.snap-card").mapNotNull { card ->
            val href = card.selectFirst("a.snap-poster")?.attr("href")?.ifBlank { null }
                ?: return@mapNotNull null
            val title = card.selectFirst("h3.snap-title a")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            parseList(get(browseUrl(page, filter.sortBy, genre)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // "/?s=" fulltext nepodporuje kombinaci se zanrovym filtrem - pri zvolenem zanru
    // appka proto (stejne jako MadaraSource/EvaScans) prepne na archiv "/browse-manga/".
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            if (genre != null) return@withContext parseList(get(browseUrl(page, filter.sortBy, genre)))
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Badge v `.lh-meta-item` s danou ikonovou tridou (napr. "fa-star") - text badge
     * bez ikony (Jsoup .text() ikonu s prazdnym obsahem preskoci sam). */
    private fun metaItemText(doc: Document, iconClass: String): String? =
        doc.select(".lh-meta-item").firstOrNull { it.selectFirst("i")?.hasClass(iconClass) == true }
            ?.text()?.trim()?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1.lh-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("#manga-story")?.text()?.trim()?.ifBlank { null },
                genres = doc.select(".lh-genres a.lh-genre-tag").map { it.text().trim() }.filter { it.isNotBlank() },
                status = metaItemText(doc, "fa-info-circle")?.lowercase(),
                rating = metaItemText(doc, "fa-star")?.toDoubleOrNull(),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun parseChapterDate(text: String?): Long = try {
        SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH).parse(text ?: "")?.time ?: 0L
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("#chapters-list-container .ch-item[data-id][data-ch]").mapNotNull { el ->
                val href = el.selectFirst("a.ch-main-anchor")?.attr("href")?.ifBlank { null }
                    ?: return@mapNotNull null
                val num = el.attr("data-ch").toFloatOrNull() ?: return@mapNotNull null
                val name = el.selectFirst("span.ch-num")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                    ?.ifBlank { null } ?: "Chapter $num"
                val date = el.selectFirst("span.ch-date")?.text()?.trim()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = num, dateUpload = parseChapterDate(date))
            }.distinctBy { it.chapterNumber }.sortedByDescending { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Vytahne kompletni JSON argument volani `fn(` z HTML - vybalancuje zavorky a
     * respektuje retezcove literaly (aby "}" uvnitr textu popisu apod. neprerusilo
     * parsovani drive). Pouzito pro `ts_reader.run({...})` blob se strankami kapitoly. */
    private fun extractJsonArg(html: String, marker: String): String? {
        val start = html.indexOf(marker)
        if (start < 0) return null
        val openIdx = html.indexOf('{', start)
        if (openIdx < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in openIdx until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return html.substring(openIdx, i + 1) }
                }
            }
        }
        return null
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val json = extractJsonArg(html, "ts_reader.run(") ?: return@withContext emptyList()
            val images = JSONObject(json).optJSONArray("sources")?.optJSONObject(0)?.optJSONArray("images")
                ?: return@withContext emptyList()
            (0 until images.length()).mapIndexedNotNull { i, _ ->
                val url = images.optString(i).takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

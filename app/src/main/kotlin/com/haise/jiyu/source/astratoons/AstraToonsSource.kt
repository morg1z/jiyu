package com.haise.jiyu.source.astratoons

import com.haise.jiyu.util.resolveSourceUrl
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
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * astratoons.com - brazilská (pt-BR) Laravel + Alpine.js aplikace. Katalog i vyhledávání
 * mají čisté JSON API (`/api/comics?page=N` a `/api/comics?search=q&page=N`, oboje vrací
 * stejnou bohatou strukturu - id/title/slug/cover_image/description/status/type/tags/
 * author/artist/chapters_count), takže appka nepotřebuje žádné HTML parsování pro
 * seznam ani detail - ověřeno živě (PowerShell).
 *
 * Seznam kapitol ALE žádné čisté API nemá - `/api/comics/{numericId}/chapters?page=N`
 * vrací už předrenderovaný HTML fragment (Alpine.js `x-data` šablona), který se musí
 * parsovat přes Jsoup. Numerické ID (na rozdíl od slugu ve viditelné URL) appka získá
 * jedním extra fetchem detailu mangy (regex na `comicId: N` v embedded Alpine datech).
 *
 * Stránky kapitoly jsou server-rendered `<canvas data-src="...">` (ne `<img>` - zjevně
 * kvůli ochraně proti snadnému stahování), URL je ale v `data-src` čitelná stejně.
 */
@Singleton
class AstraToonsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "astratoons"
    override val name = "AstraToons"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://astratoons.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun normalizeStatus(text: String?): String? = when (text?.trim()?.lowercase()) {
        "em andamento" -> "ongoing"
        "completo", "concluído", "concluido" -> "completed"
        "pausado", "hiato" -> "hiatus"
        "cancelado" -> "cancelled"
        else -> text
    }

    private fun itemToSManga(o: JSONObject): SManga? {
        val slug = o.optString("slug").ifBlank { return null }
        val cover = o.optString("cover_image").ifBlank { null }?.let { "$base/storage/$it" }
        val description = o.optString("description").ifBlank { null }?.let { Jsoup.parse(it).text().trim() }?.ifBlank { null }
        val genres = o.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("name")?.optString("pt_BR")?.ifBlank { null } }
        } ?: emptyList()
        return SManga(
            sourceId = id,
            url = "$base/comics/$slug",
            title = o.optString("title"),
            coverUrl = cover,
            description = description,
            genres = genres,
            author = o.optString("author").ifBlank { null },
            artist = o.optString("artist").ifBlank { null },
            status = normalizeStatus(o.optString("status").ifBlank { null }),
            contentType = normalizeContentType(o.optString("type")),
        )
    }

    private fun parseListing(body: String): List<SManga> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { itemToSManga(data.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseComicsPageJson(get(genreFilteredUrl(filter.genres, page))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        try {
            parseListing(get("$base/api/comics?page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseComicsPageJson(get(genreFilteredUrl(filter.genres, page))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseListing(get("$base/api/comics?search=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── Filtrování podle tagů/žánrů ───────────────────────────────────────
    //
    // `/api/comics` (JSON API použité výše) parametr filtru tagů tise ignoruje
    // (overeno zive - `?tag=cokoliv` vraci porad stejny (neomezeny) pocet
    // vysledku). Skutecne filtrovani podle tagu dela az server-rendered
    // `/comics` stranka (Alpine.js aplikace), kde formular s checkboxy
    // `<input type="checkbox" name="tags[]" value="{slug}">` posila GET na
    // `/comics?tags[]=slug1&tags[]=slug2...` - stranka pak vrati JIZ
    // PREFILTROVANY seznam vlozeny primo v HTML jako
    // `comics: JSON.parse('...')` (stejna struktura jako polozky z /api/comics).
    // Overeno zive: `/comics?tags[]=isekai` vraci uplne jiny seznam nazvu
    // (12 titulu) nez neflitrovany vypis (16 titulu na prvni strance) a
    // kombinace dvou tagu (`tags[]=acao&tags[]=isekai`) vraci dalsi odlisnou
    // mnozinu (OR napric tagy).

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    // Kompletni seznam tagu je take vlozeny primo v HTML `/comics` stranky jako
    // `allTags: JSON.parse('...')` - malý, staticky seznam (~36 zanru, overeno
    // zive), zadne dalsi strankovani/API neni potreba.
    private val allTagsRegex = Regex("""allTags: JSON\.parse\('(.*?)'\)""")
    private val comicsDataRegex = Regex("""comics: JSON\.parse\('(.*?)'\),""")

    /** Oba embedded JSON bloky jsou JS string literaly, kde vnitrni uvozovky
     * jsou escapovane jako `"` (aby nekolidovaly s obalujicimi `'...'`) -
     * stejny trik jako ColoredMangaSource.extractComicSeriesJson: obalit zpet
     * do JSON retezce a nechat JSONTokener odescapovat na skutecny JSON text. */
    private fun decodeJsParseLiteral(raw: String): String? =
        runCatching { JSONTokener("\"$raw\"").nextValue() as String }.getOrNull()

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val html = get("$base/comics")
            val raw = allTagsRegex.find(html)?.groupValues?.get(1) ?: return@withContext emptyList()
            val decoded = decodeJsParseLiteral(raw) ?: return@withContext emptyList()
            val arr = JSONArray(decoded)
            val tags = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optJSONObject("slug")?.optString("pt_BR")?.ifBlank { null } ?: return@mapNotNull null
                val label = o.optJSONObject("name")?.optString("pt_BR")?.ifBlank { null } ?: slug
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseComicsPageJson(html: String): List<SManga> {
        val raw = comicsDataRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
        val decoded = decodeJsParseLiteral(raw) ?: return emptyList()
        val arr = JSONArray(decoded)
        return (0 until arr.length()).mapNotNull { itemToSManga(arr.getJSONObject(it)) }
    }

    private fun genreFilteredUrl(genres: List<String>, page: Int): String {
        val params = genres.joinToString("&") { "tags%5B%5D=" + URLEncoder.encode(it, "UTF-8") }
        return "$base/comics?$params&page=$page"
    }

    // Listovaci/hledaci API uz vraci vsechna pole detailu (popis, zanry, autor, stav,
    // typ) - pro cerstvejsi data se hleda podle titulku a paruje podle URL/slugu; kdyz
    // se nenajde, vrati se puvodni manga beze zmeny.
    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(manga.title, "UTF-8")
            parseListing(get("$base/api/comics?search=$q")).firstOrNull { it.url == manga.url } ?: manga
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private val comicIdRegex = Regex("""comicId:\s*(\d+)""")

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val detailHtml = get(manga.url)
            val comicId = comicIdRegex.find(detailHtml)?.groupValues?.get(1) ?: return@withContext emptyList()
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page <= 50) {
                val json = JSONObject(get("$base/api/comics/$comicId/chapters?page=$page"))
                val fragment = json.optString("html")
                val doc = Jsoup.parse(fragment, base)
                doc.select("a[href*=/capitulo/]").forEach { a ->
                    val href = a.absUrl("href").ifBlank { return@forEach }
                    val num = Regex("""/capitulo/([\d.]+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: return@forEach
                    val titleText = a.selectFirst("span.text-lg, span.font-medium")?.text()?.trim()
                    val name = titleText?.ifBlank { null } ?: "Capítulo $num"
                    val dateText = a.selectFirst("time")?.text()?.trim()
                    chapters += SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = href,
                        name = name,
                        chapterNumber = num,
                        dateUpload = parseRelativeDatePt(dateText),
                    )
                }
                if (!json.optBoolean("hasMore", false)) break
                page++
            }
            chapters.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseRelativeDatePt(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val m = Regex("""(\d+)\s+(segundo|minuto|hora|dia|semana|m[eê]s|ano)s?""", RegexOption.IGNORE_CASE).find(text)
            ?: return System.currentTimeMillis()
        val value = m.groupValues[1].toLongOrNull() ?: 1L
        val unit = m.groupValues[2].lowercase()
        val deltaMs = when {
            unit.startsWith("segundo") -> value * 1_000L
            unit.startsWith("minuto")  -> value * 60_000L
            unit.startsWith("hora")    -> value * 3_600_000L
            unit.startsWith("dia")     -> value * 86_400_000L
            unit.startsWith("semana")  -> value * 7 * 86_400_000L
            unit.startsWith("m")       -> value * 30 * 86_400_000L
            unit.startsWith("ano")     -> value * 365 * 86_400_000L
            else -> 0L
        }
        return System.currentTimeMillis() - deltaMs
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url), base)
            doc.select("canvas[data-src]").mapIndexedNotNull { i, el ->
                val src = el.attr("data-src").trim().ifBlank { return@mapIndexedNotNull null }
                val abs = resolveSourceUrl(base, src)
                Page(i, abs, abs)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

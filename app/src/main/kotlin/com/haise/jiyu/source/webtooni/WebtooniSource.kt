package com.haise.jiyu.source.webtooni

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
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
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * webtooni.net - "daycomics" sablona (Cloudflare je pritomna, ale bez
 * interaktivniho challenge, takze obycejny GET stac). "Popularni" vypis jede
 * z `/en/ranking` - stranka nema funkcni server-side strankovani (`?page=N`
 * vraci bit-identicky obsah, overeno zive), takze `getPopular` vraci vysledky
 * jen pro `page == 1`. Hledani naopak jede pres cisty JSON endpoint
 * (`/api/complete-search?keyword=...`), ktery vraci i popis/zanry/autora -
 * `getMangaDetails` uz jen doplni to, co v JSON chybelo.
 */
@Singleton
class WebtooniSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "webtooni"
    override val name = "Webtooni"
    override val contentType = "MANHWA"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://webtooni.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseDocument(url: String): Document = Jsoup.parse(get(url), url)

    private fun parseCardList(doc: Document): List<SManga> =
        doc.select("div.comicItemCon a[href]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
            val cover = img.attr("data-src").trim().ifBlank { img.attr("src").trim() }.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANHWA")
        }.distinctBy { it.url }

    // /en/genres ma seznam vsech zanru (odkazy na /en/genres/{Nazev}) - kazda genre
    // stranka pouziva stejnou "div.comicItemCon" kartu jako /en/ranking a /en/new,
    // overeno zive (Action vs. Ranking maji jine tituly). Zadne dalsi strankovani
    // (stejne jako u ranking/new - appka proto vraci vysledky jen pro page == 1).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = parseDocument("$base/en/genres")
            val tags = doc.select("a[href*=/en/genres/]").mapNotNull { a ->
                val href = a.absUrl("href")
                val slug = href.substringAfter("/en/genres/").trim('/').ifBlank { null } ?: return@mapNotNull null
                if (slug.equals("All", ignoreCase = true) || slug.equals("New", ignoreCase = true)) return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList()
            try {
                if (filter.genres.isNotEmpty()) {
                    return@withContext parseCardList(parseDocument("$base/en/genres/${filter.genres.first()}"))
                }
                // /en/new ma stejnou strukturu karet jako /en/ranking, jen jinak razenou
                // (overeno zive - odlisna prvni polozka) - pro "Nejnovejsi" tab.
                val path = if (filter.sortBy == "latest") "/en/new" else "/en/ranking"
                parseCardList(parseDocument("$base$path"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList()
            if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val json = JSONArray(get("$base/api/complete-search?keyword=$q"))
                (0 until json.length()).mapNotNull { i ->
                    val o = json.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("linkComic").ifBlank { return@mapNotNull null }
                    val title = o.optString("title").ifBlank { return@mapNotNull null }
                    val cover = o.optString("thumb").takeIf { it.isNotBlank() }?.let { "https://hcgcontent.com$it" }
                        ?: o.optString("raw_thumb").takeIf { it.isNotBlank() }
                    val description = o.optString("description").takeIf { it.isNotBlank() }
                    val author = o.optString("writer").takeIf { it.isNotBlank() }
                    val genres = o.optJSONArray("category")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                    SManga(
                        sourceId = id, url = url, title = title, coverUrl = cover,
                        description = description, author = author, genres = genres, contentType = "MANHWA",
                    )
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            try {
                val doc = parseDocument(manga.url)
                val description = doc.selectFirst("div.des_area p")?.text()?.trim()?.ifBlank { null } ?: manga.description
                val author = doc.selectFirst("p.authorInfo")?.text()?.trim()?.ifBlank { null } ?: manga.author
                val genres = doc.select("#keywordArea a, #genresArea a")
                    .map { it.text().trim().removePrefix("#").trim() }
                    .filter { it.isNotBlank() }
                    .distinct()

                manga.copy(
                    description = description,
                    author = author,
                    genres = genres.ifEmpty { manga.genres },
                )
            } catch (e: Exception) { e.rethrowIfControl(); manga }
        }

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            try {
                val doc = parseDocument(manga.url)
                // Kazdy episodni radek ma vic <p> tagu (cislo, podtitulek, datum) se
                // stejne dlouhymi Tailwind tridami - spolehlivejsi je najit ten,
                // jehoz text zacina "Episode", nez se spolehat na poradi/tridu.
                doc.select("a#episodeItemCon").mapNotNull { a ->
                    val url = a.absUrl("href").ifBlank { return@mapNotNull null }
                    val name = a.select("p").firstOrNull { it.text().trim().startsWith("Episode") }
                        ?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    val chapterNumber = parseChapterNumber(name) ?: 0f
                    val dateText = a.selectFirst("p.episodeDate")?.text()?.trim()

                    SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = url,
                        name = name,
                        chapterNumber = chapterNumber,
                        dateUpload = parseDate(dateText),
                    )
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun parseDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            try {
                val doc = parseDocument(chapter.url)
                doc.select("#comicContent div.imgSubWrapper img").mapIndexedNotNull { i, img ->
                    val src = img.attr("src").trim().ifBlank { return@mapIndexedNotNull null }
                    Page(index = i, url = src, imageUrl = src)
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
}

package com.haise.jiyu.source.kuramanga

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
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
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KuraManga (kuramanga.com) - vlastni custom-built web (ne WordPress).
 * Browse i vyhledavani bezi pres stejny "ajax=1" JSON endpoint
 * (/search?name=...&offset=...&ajax=1), detail mangy vcetne seznamu
 * kapitol i vsech stranek kapitoly je uz kompletne server-rendered v
 * HTML (zadne dalsi API volani na cteni neni potreba).
 */
@Singleton
class KuraMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "kuramanga"
    override val name = "KuraManga"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://kuramanga.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // Overeno zive: "/search" HTML stranka obsahuje "div.genres-grid#genresDropdown"
    // s kompletnim seznamem odkazu "<a href=/search?genre={Nazev}>{Nazev}</a>" (182
    // polozek). Stejny JSON endpoint "/search?genre={Nazev}&offset=N&ajax=1" pouzity
    // v getPopular/search uz genre parametr podporuje - zivym porovnanim potvrzeno
    // (genre=Action vrati jen tituly, ktere maji "Action" mezi svymi "genres").
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/search"))
            val tags = doc.select("div.genres-grid a[href*=\"/search?genre=\"]").mapNotNull { a ->
                val name = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = name, label = name)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun StringBuilder.appendGenreFilter(filter: MangaFilter) {
        filter.genres.firstOrNull()?.let { append("&genre=${URLEncoder.encode(it, "UTF-8")}") }
    }

    private fun parseListJson(json: String): List<SManga> {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            val slug = m.optString("normalized_title").ifBlank { return@mapNotNull null }
            val title = m.optString("title").ifBlank { return@mapNotNull null }
            SManga(sourceId = id, url = "/$slug", title = title, coverUrl = m.optString("thumb").ifBlank { null }, contentType = "MANHWA")
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // "/search" JSON API nema zadny sort parametr (overeno zive - kazda
            // vyzkousena kombinace sort=/orderby=/order= vratila bajtove identicky
            // vysledek jako bez parametru). Homepage ale ma samostatnou HTML sekci
            // "Latest Updates" (div.update-row), kterou API nevraci - proto se pro
            // "latest" parsuje primo HTML homepage, ne JSON endpoint. Neni strankovana
            // (fixni pocet polozek na homepage), stejny vzor jako KScansSource.getPopular.
            if (filter.genres.isNotEmpty()) {
                val offset = (page - 1) * 10
                val url = buildString {
                    append("$base/search?offset=$offset&ajax=1")
                    appendGenreFilter(filter)
                }
                return@withContext parseListJson(get(url))
            }
            if (filter.sortBy == "latest") {
                if (page > 1) return@withContext emptyList()
                parseLatestUpdates(get(base))
            } else {
                val offset = (page - 1) * 10
                parseListJson(get("$base/search?offset=$offset&ajax=1"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseLatestUpdates(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("div.update-row").mapNotNull { row ->
            val link = row.selectFirst("a.update-series-link") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val cover = row.selectFirst("a.update-thumb img")?.attr("src")?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANHWA")
        }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val offset = (page - 1) * 10
            val url = buildString {
                append("$base/search?name=$q&offset=$offset&ajax=1")
                appendGenreFilter(filter)
            }
            parseListJson(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            // Vazba na strong tag s presnym textem "Status:"/"Author:" a jeho
            // primy rodic (ne obecne div:contains, ktere by nasly i predky
            // vyssi v DOM stromu a vratily text celeho zbytku stranky).
            val status = doc.select("strong").firstOrNull { it.text().trim() == "Status:" }
                ?.parent()?.text()?.substringAfter("Status:")?.trim()
            val author = doc.select("strong").firstOrNull { it.text().trim() == "Author:" }
                ?.parent()?.text()?.substringAfter("Author:")?.trim()

            manga.copy(
                title = doc.selectFirst("h1.manga-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.summary-inner")?.text()?.trim(),
                genres = doc.select(".genre-list a.genre-chip").map { it.text().trim() },
                status = status,
                author = author,
                contentType = "MANHWA",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("div.chapter-list div.chapter-item").mapIndexedNotNull { i, el ->
                val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Chapter ${i + 1}" }
                val num = Regex("""chapter-([\d.]+)""").find(href)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat()
                val dateText = el.selectFirst("time")?.text()?.trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseDate(dateText),
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("div.reader-width img[src*=/chapters/]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

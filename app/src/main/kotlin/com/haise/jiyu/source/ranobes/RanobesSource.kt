package com.haise.jiyu.source.ranobes

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
 * Ranobes (ranobes.net) - server-rendered pro browse/detail, ale seznam
 * kapitol na dedikovane strance "/chapters/{id}/" je Vue.js aplikace,
 * jejiz pocatecni data jsou vlozena primo v HTML jako
 * `window.__DATA__ = {...,"chapters":[...],"pages_count":N}` (ne
 * samostatny XHR) - staci tedy regex vytahnout a strankovat pres
 * "/chapters/{id}/page/{n}/".
 */
@Singleton
class RanobesSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "ranobes"
    override val name = "Ranobes"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://ranobes.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.story").mapNotNull { el ->
            val link = el.selectFirst("h2.title a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val style = el.selectFirst("a.poster figure")?.attr("style").orEmpty()
            val cover = Regex("""url\(([^)]+)\)""").find(style)?.groupValues?.get(1)
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/novels/" else "$base/novels/page/$page/"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    // Vlastni interni vyhledavani na webu odkazuje na Yandex site-search,
    // ale existuje DLE endpoint "index.php?do=search" ktery presmeruje
    // na "/search/{dotaz}/" se stejnou strukturou jako browse listing.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search/$q/"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val infoItems = doc.select("li")
            val author = infoItems.firstOrNull { it.text().trim().startsWith("Authors:") }
                ?.select(".tag_list a")?.joinToString(", ") { it.text().trim() }
            val status = infoItems.firstOrNull { it.text().contains("Status in COO") }
                ?.selectFirst("a")?.text()?.trim()
            val genres = doc.select("#mc-fs-genre a").map { it.text().trim() }
            val description = doc.selectFirst("div.r-desription div.cont-text")?.text()?.trim()

            manga.copy(
                title = doc.selectFirst("h1.title")?.ownText()?.trim()?.ifBlank { null } ?: manga.title,
                author = author,
                status = status,
                genres = genres,
                description = description,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val bookId = Regex("""/novels/(\d+)-""").find(manga.url)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val chapters = mutableListOf<SChapter>()
            var page = 1
            var totalPages = 1
            do {
                val url = if (page == 1) "$base/chapters/$bookId/" else "$base/chapters/$bookId/page/$page/"
                val html = get(url)
                val dataJson = extractBalancedJson(html, "__DATA__") ?: break
                val json = org.json.JSONObject(dataJson)
                totalPages = json.optInt("pages_count", 1)
                val arr = json.optJSONArray("chapters") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val link = c.optString("link")
                    if (link.isBlank()) continue
                    val title = c.optString("title").ifBlank { "Chapter" }
                    val num = Regex("""[\d.]+""").find(title)?.value?.toFloatOrNull() ?: 0f
                    chapters.add(
                        SChapter(
                            sourceId = id,
                            mangaUrl = manga.url,
                            url = link,
                            name = title,
                            chapterNumber = num,
                            dateUpload = parseDate(c.optString("date")),
                        )
                    )
                }
                page++
            } while (page <= totalPages)
            chapters
        } catch (_: Exception) { emptyList() }
    }

    // window.__DATA__ = {...} muze mit dalsi klice (count_all, cstart, ...)
    // za "pages_count", takze jednoduchy non-greedy regex konci na prvni
    // "}" moc brzy - misto toho se pocita skutecna hloubka zavorek (a
    // ignoruji se ty uvnitr retezcovych hodnot), aby se nasel spravny konec.
    private fun extractBalancedJson(html: String, marker: String): String? {
        val markerIdx = html.indexOf(marker)
        if (markerIdx == -1) return null
        val start = html.indexOf('{', markerIdx)
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun parseDate(text: String?): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).parse(text.orEmpty())?.time ?: 0L
    } catch (_: Exception) { 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            val text = doc.selectFirst("#arrticle")?.text()?.trim().orEmpty()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

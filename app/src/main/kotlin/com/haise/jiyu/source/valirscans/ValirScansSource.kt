package com.haise.jiyu.source.valirscans

import com.haise.jiyu.source.bodyOrThrow

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
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * valirscans.org - Next.js App Router (React Server Components), ale katalog i hledani
 * maji ciste JSON API - `/api/series?page=N` (+ `?q=`) - overeno zive.
 *
 * Detail mangy se bere z `<script type="application/ld+json">` bloku typu "Book"
 * (jeden na strance detailu) - normalni nezanorene JSON, zadne RSC escapovani.
 *
 * Seznam kapitol ale v ld+json neni - je jen v RSC payloadu
 * (`self.__next_f.push([1,"..."])`) jako opakujici se `{"id":...,"number":N,
 * "title":"...","isLocked":bool,...,"publishedAt":"..."}` objekty, escapovane jednou
 * urovni JSON stringu - vytahuje se regexem primo z HTML stranky detailu.
 *
 * Stranky manga/manhwa/manhua kapitoly jsou obycejne https URL na `media.valirscans.org`
 * primo v HTML (poradi vyskytu v textu = poradi stranek, overeno zive).
 *
 * Stranky NOVEL kapitoly jsou surovy HTML text (`<div><p>...</p>...</div>`) v
 * samostatnem RSC "text" chunku, unicode-escapovany (`\u003cdiv\u003e...`) - najde se
 * podle `\u003cdiv\u003e...\u003c/div\u003e` a rozbali pres JSONTokener (jednorazove
 * JSON-string odescapovani - spravne zvladne i \n, \", \uXXXX na rozdil od naivniho
 * .replace()).
 *
 * Zamcene/predplacene kapitoly (`isLocked:true`, placeny "coin" system) proste nemaji
 * zadne obrazky/text v odpovedi - getPageList pak vrati prazdny seznam, stejne jako u
 * jineho placeneho zdroje.
 */
@Singleton
class ValirScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "valirscans"
    override val name = "ValirScans"
    override val homepageUrl get() = base
    private val base = "https://valirscans.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private val novelTypes = setOf(
        "NOVEL", "LIGHT NOVEL", "WEB NOVEL", "PUBLISHED NOVEL", "FANFICTION", "ORIGINAL FICTION", "ONE SHOT",
    )

    private fun typeSegment(type: String?): String =
        if (type?.trim()?.uppercase() in novelTypes) "novel" else "comic"

    private fun normalizeContentType(type: String?): String {
        val t = type?.trim()?.uppercase()
        return when {
            t == "MANHWA" -> "MANHWA"
            t == "MANHUA" -> "MANHUA"
            t in novelTypes -> "NOVEL"
            else -> "MANGA"
        }
    }

    private fun itemToSManga(o: JSONObject): SManga? {
        val urlSlug = o.optString("urlSlug").ifBlank { o.optString("slug") }.ifBlank { return null }
        val type = o.optString("type").ifBlank { null }
        val cover = o.optString("coverImage").ifBlank { null }?.let { if (it.startsWith("http")) it else "$base$it" }
        return SManga(
            sourceId = id,
            url = "$base/series/${typeSegment(type)}/$urlSlug",
            title = o.optString("title"),
            coverUrl = cover,
            status = o.optString("status").ifBlank { null }?.lowercase(),
            contentType = normalizeContentType(type),
        )
    }

    private fun parseListing(body: String): List<SManga> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { itemToSManga(data.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseListing(get("$base/api/series?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseListing(get("$base/api/series?q=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    private fun extractLdJsonBlocks(html: String): List<String> {
        val marker = "application/ld+json"
        val result = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val mi = html.indexOf(marker, searchFrom)
            if (mi < 0) break
            val s = html.indexOf('>', mi) + 1
            val e = html.indexOf("</script>", s)
            if (s <= 0 || e < 0) break
            result += html.substring(s, e)
            searchFrom = e + 1
        }
        return result
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val book = extractLdJsonBlocks(html)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.optString("@type") == "Book" } ?: return@withContext manga
            val authorName = book.optJSONObject("author")?.optString("name")?.takeUnless { it.isBlank() || it == "N/A" }
            val genres = book.optJSONArray("genre")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?.ifEmpty { null } ?: manga.genres
            manga.copy(
                title = book.optString("name").ifBlank { manga.title },
                description = book.optString("description").ifBlank { null },
                author = authorName,
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    // Pole kapitol v RSC payloadu ma pevne poradi klicu (id, number, title, coverImage,
    // isLocked, coinPrice, ...) - neni potreba plny JSON parser, staci non-greedy regex
    // od "number" po "publishedAt" (mezilehla pole se preskoci pres ".*?").
    private val chapterRegex = Regex(
        """\\"number\\":(\d+(?:\.\d+)?),\\"title\\":\\"(.*?)\\".*?\\"isLocked\\":(true|false).*?\\"publishedAt\\":\\"([^\\]*)"""
    )

    private fun parseIsoDate(text: String): Long =
        try { Instant.parse(text).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            chapterRegex.findAll(html).mapNotNull { m ->
                val num = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
                val isLocked = m.groupValues[3] == "true"
                val rawTitle = m.groupValues[2].ifBlank { "Chapter $num" }
                val name = if (isLocked) "🔒 $rawTitle" else rawTitle
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "${manga.url}/chapter/${if (num == num.toLong().toFloat()) num.toLong().toString() else num.toString()}",
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseIsoDate(m.groupValues[4]),
                )
            }.toList().distinctBy { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    private val chapterImageRegex = Regex("""https://media\.valirscans\.org/[^"\\]+\.(?:webp|jpg|jpeg|png|avif)""")

    private fun extractNovelText(html: String): String? {
        val startMarker = "\\u003cdiv\\u003e"
        val endMarker = "\\u003c/div\\u003e"
        val start = html.indexOf(startMarker)
        if (start < 0) return null
        val end = html.indexOf(endMarker, start)
        if (end < 0) return null
        val raw = html.substring(start, end + endMarker.length)
        val decoded = runCatching { JSONTokener("\"$raw\"").nextValue() as String }.getOrNull() ?: return null
        val doc = Jsoup.parse(decoded)
        val paragraphs = doc.select("p").eachText().filter { it.isNotBlank() }
        return paragraphs.joinToString("\n\n").ifBlank { doc.text().trim() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val images = chapterImageRegex.findAll(html).map { it.value }.distinct().toList()
            if (images.isNotEmpty()) {
                return@withContext images.mapIndexed { i, url -> Page(i, url, url) }
            }
            val text = extractNovelText(html) ?: return@withContext emptyList()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

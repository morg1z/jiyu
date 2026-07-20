package com.haise.jiyu.source.hentai20

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
 * WordPress "TS/MangaStream" rodina šablon (stejná jako RawKuma/Galaxy Manga),
 * ne klasický Madara markup - proto vlastní třída místo MadaraSource.
 */
@Singleton
class Hentai20Source @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "hentai20"
    override val name = "Hentai20.io"
    override val contentType = "MANHWA"
    override val homepageUrl get() = base

    private val base = "https://hentai20.io"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Chyba ${response.code} pri nacitani $url" }
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseMangaList(doc: Document): List<SManga> =
        doc.select("div.bsx").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = item.selectFirst(".tt")?.text()?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val cover = item.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.trim()?.ifBlank { null }

            SManga(sourceId = id, url = url, title = title, coverUrl = cover)
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val order = if (filter.sortBy == "latest") "update" else "popular"
            parseMangaList(fetchDocument("$base/manga/?page=$page&order=$order"))
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseMangaList(fetchDocument(url))
        }

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            val description = doc.selectFirst("[itemprop=description]")?.text()?.trim()?.ifBlank { null }

            var status: String? = null
            doc.selectFirst("table.infotable")?.select("tr")?.forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 2 && cells[0].text().trim().startsWith("Status")) {
                    status = cells[1].text().trim().ifBlank { null }
                }
            }

            // Genre odkazy na strance jsou ve sdilenem "browse all genres" widgetu, ne
            // specificke pro tuhle mangu - zamerne se nezpracovavaji, aby se predeslo
            // spatnemu prirazeni genru vsem titulum.
            manga.copy(description = description, status = status)
        }

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            doc.select(".eplister li").mapNotNull { row ->
                val link = row.selectFirst("a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href").ifBlank { return@mapNotNull null }
                val name = link.selectFirst(".chapternum")?.text()?.trim()
                    ?: link.text().trim().ifBlank { return@mapNotNull null }
                val chapterNumber = row.attr("data-num").toFloatOrNull()
                    ?: Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                val dateText = link.selectFirst(".chapterdate")?.text()?.trim()

                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = url,
                    name = name,
                    chapterNumber = chapterNumber,
                    dateUpload = parseDate(dateText),
                )
            }
        }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            // "June 25, 2026"
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).parse(text)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val html = fetchHtml(chapter.url)
            val direct = Jsoup.parse(html, chapter.url).select("img.ts-main-image").mapIndexedNotNull { i, img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }.trim().ifBlank { return@mapIndexedNotNull null }
                Page(index = i, url = src, imageUrl = src)
            }
            if (direct.isNotEmpty()) return@withContext direct

            // Stranky nejsou ve statickem HTML - stranka je vklada az JS z JSON objektu
            // "ts_reader.run({...})" primo ve <script> (stejny vzor jako RawKuma/Galaxy Manga).
            extractTsReaderImages(html).mapIndexed { i, url -> Page(index = i, url = url, imageUrl = url) }
        }

    private fun extractTsReaderImages(html: String): List<String> {
        val start = html.indexOf("ts_reader.run(")
        val jsonStart = if (start == -1) -1 else html.indexOf('{', start)
        if (jsonStart == -1) return emptyList()

        var depth = 0
        var end = -1
        var inString = false
        var escaped = false
        for (i in jsonStart until html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end == -1) return emptyList()

        val sources = JSONObject(html.substring(jsonStart, end + 1)).optJSONArray("sources") ?: return emptyList()
        if (sources.length() == 0) return emptyList()
        val images = sources.getJSONObject(0).optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).map { images.getString(it) }
    }
}

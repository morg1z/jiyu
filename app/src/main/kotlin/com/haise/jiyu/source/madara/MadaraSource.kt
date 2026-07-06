package com.haise.jiyu.source.madara

import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Generický zdroj pro weby postavené na Madara - WordPress šabloně,
 * kterou používá velké množství manga/manhwa/manhua webů bez oficiálního API.
 *
 * Nekonfiguruje se natvrdo na konkrétní web - `baseUrl` a `name` zadává
 * uživatel v Nastavení ("Vlastní zdroje"). CSS selektory níže odpovídají
 * výchozímu, nezměněnému Madara markupu; pokud konkrétní web téma
 * upravil, může se parsování rozejít - to je omezení generické šablony,
 * ne něco co appka umí zaručit pro každý web.
 */
class MadaraSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): List<SManga> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "$root/page/$page/?s=$q&post_type=wp-manga"
            parseMangaList(fetchDocument(url))
        }

    override suspend fun getPopular(page: Int): List<SManga> =
        withContext(Dispatchers.IO) {
            val url = "$root/manga/page/$page/?m_orderby=views"
            parseMangaList(fetchDocument(url))
        }

    private fun parseMangaList(doc: Document): List<SManga> {
        val items = doc.select("div.page-item-detail, div.c-tabs-item__content")
        return items.mapNotNull { item ->
            val link = item.selectFirst("a[href], .post-title a") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { link.text() }.ifBlank { return@mapNotNull null }
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val cover = item.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            }?.trim()?.ifBlank { null }

            SManga(sourceId = id, url = url, title = title, coverUrl = cover)
        }
    }

    // ─── Detail mangy ────────────────────────────────────────────────────────

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            val desc = doc.selectFirst("div.summary__content, div.description-summary")
                ?.text()?.ifBlank { null }
            val status = doc.selectFirst("div.post-status .summary-content, .post-content_item .summary-content")
                ?.text()?.trim()?.ifBlank { null }

            manga.copy(description = desc, status = status)
        }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            // Madara u vetsiny webu nacita seznam kapitol pres AJAX endpoint,
            // protoze na hlavni strance mangy je jen limitovany vypis.
            val ajaxDoc = try {
                val request = Request.Builder()
                    .url("${manga.url.trimEnd('/')}/ajax/chapters/")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Jsoup.parse(response.body?.string().orEmpty(), manga.url) else null
                }
            } catch (_: Exception) {
                null
            }

            val doc = ajaxDoc ?: fetchDocument(manga.url)
            val rows = doc.select("li.wp-manga-chapter")

            rows.mapNotNull { row -> chapterFromRow(row, manga.url) }
        }

    private fun chapterFromRow(row: Element, mangaUrl: String): SChapter? {
        val link = row.selectFirst("a") ?: return null
        val url = link.absUrl("href").ifBlank { return null }
        val name = link.text().trim().ifBlank { return null }
        val chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
        val dateText = row.selectFirst("span.chapter-release-date, i")?.text()?.trim()

        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = url,
            name = name,
            chapterNumber = chapterNumber,
            dateUpload = parseRelativeOrAbsoluteDate(dateText),
        )
    }

    private fun parseRelativeOrAbsoluteDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(chapter.url)
            val images = doc.select("div.reading-content img, div.page-break img")

            images.mapIndexedNotNull { i, img ->
                val src = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
                    .trim().ifBlank { return@mapIndexedNotNull null }
                Page(index = i, url = src, imageUrl = src)
            }
        }

    // ─── Pomocné funkce ──────────────────────────────────────────────────────

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Chyba ${response.code} pri nacitani $url" }
            val body = response.body?.string().orEmpty()
            return Jsoup.parse(body, url)
        }
    }
}

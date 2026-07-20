package com.haise.jiyu.source.madara

import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * CSS selektory pro parsování Madara markupu. Výchozí hodnoty odpovídají
 * nezměněnému Madara tématu; pole s `null` v [CustomSourceEntity] použijí
 * odpovídající výchozí hodnotu z [DEFAULT] - přepis je potřeba jen pokud
 * konkrétní web téma upravil a výchozí selektor tam nesedí.
 */
data class MadaraSelectors(
    val listItem: String = "div.page-item-detail, div.c-tabs-item__content",
    val titleLink: String = "a[href], .post-title a",
    val description: String = "div.summary__content, div.description-summary",
    val status: String = "div.post-status .summary-content, .post-content_item .summary-content",
    val chapterList: String = "li.wp-manga-chapter",
    val pageImage: String = "div.reading-content img, div.page-break img",
) {
    companion object {
        val DEFAULT = MadaraSelectors()
    }
}

/**
 * Generický zdroj pro weby postavené na Madara - WordPress šabloně,
 * kterou používá velké množství manga/manhwa/manhua webů bez oficiálního API.
 *
 * Nekonfiguruje se natvrdo na konkrétní web - `baseUrl` a `name` zadává
 * uživatel v Nastavení ("Vlastní zdroje"). CSS selektory v [MadaraSelectors]
 * odpovídají výchozímu, nezměněnému Madara markupu, ale lze je pro
 * konkrétní web přepsat, pokud tam téma upravilo strukturu.
 */
class MadaraSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val selectors: MadaraSelectors = MadaraSelectors.DEFAULT,
    private val contentTypeOverride: String = "MANGA",
    // Vetsina Madara webu pouziva vychozi WordPress permalink strukturu
    // ("/manga/page/N/?m_orderby=", "/page/N/?s=...&post_type=wp-manga"),
    // ale nektere weby maji vlastni post-type/taxonomy slug (napr.
    // manhwaz.com pouziva pro archiv "/genre/manga?page=N" misto
    // "/manga/page/N/"). Tyhle dvě lambdy jdou pro takove weby přepsat,
    // vychozi hodnota odpovida standardnimu Madara motivu beze zmeny.
    private val popularUrl: (root: String, page: Int, orderby: String) -> String =
        { root, page, orderby -> "$root/manga/page/$page/?m_orderby=$orderby" },
    private val searchUrl: (root: String, query: String, page: Int) -> String =
        { root, query, page -> "$root/page/$page/?s=$query&post_type=wp-manga" },
) : MangaSource {

    override val contentType: String get() = contentTypeOverride
    override val homepageUrl: String get() = baseUrl

    private val root get() = baseUrl.trimEnd('/')

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int, filter: com.haise.jiyu.source.MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = searchUrl(root, q, page)
            parseMangaList(fetchDocument(url))
        }

    override suspend fun getPopular(page: Int, filter: com.haise.jiyu.source.MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val orderby = when (filter.sortBy) {
                "latest" -> "latest"
                "title"  -> "alphabet"
                else     -> "views"
            }
            val url = popularUrl(root, page, orderby)
            parseMangaList(fetchDocument(url))
        }

    private fun parseMangaList(doc: Document): List<SManga> {
        val items = doc.select(selectors.listItem)
        return items.mapNotNull { item ->
            val link = item.selectFirst(selectors.titleLink) ?: return@mapNotNull null
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
            val desc = doc.selectFirst(selectors.description)
                ?.text()?.ifBlank { null }
            val status = doc.selectFirst(selectors.status)
                ?.text()?.trim()?.ifBlank { null }

            manga.copy(description = desc, status = status)
        }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            // Madara u vetsiny webu nacita seznam kapitol pres AJAX endpoint,
            // protoze na hlavni strance mangy je jen limitovany vypis.
            // Nektere (obvykle upravene) motivy vraci na ajax endpoint HTTP 200
            // s nesouvisejicim fragmentem (napr. jiny widget) misto kapitol -
            // takovy "uspesny" ale prazdny vysledek se proto zahodi a pouzije
            // se rovnou hlavni stranka mangy, kde uz je chapterList casto
            // kompletni server-rendered (jen skryty pres CSS/"Show more").
            val ajaxDoc = try {
                val request = Request.Builder()
                    .url("${manga.url.trimEnd('/')}/ajax/chapters/")
                    .post(FormBody.Builder().add("action", "manga_get_chapters").build())
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Jsoup.parse(response.body?.string().orEmpty(), manga.url) else null
                }
            } catch (_: Exception) {
                null
            }

            val doc = ajaxDoc?.takeIf { it.select(selectors.chapterList).isNotEmpty() } ?: fetchDocument(manga.url)
            val rows = doc.select(selectors.chapterList)

            rows.mapNotNull { row -> chapterFromRow(row, manga.url) }
        }

    private fun chapterFromRow(row: Element, mangaUrl: String): SChapter? {
        // U nekterych motivu je radek kapitoly primo <a> (ne obal s vnorenym
        // odkazem) - selectFirst hleda jen potomky, takze sebe sama nenajde.
        val link = row.takeIf { it.tagName() == "a" && it.hasAttr("href") } ?: row.selectFirst("a") ?: return null
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
        // "2 days ago", "3 hours ago", "1 week ago", etc.
        val relativeMatch = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(text)
        if (relativeMatch != null) {
            val value = relativeMatch.groupValues[1].toLongOrNull() ?: 1L
            val unit = relativeMatch.groupValues[2].lowercase()
            val deltaMs = when (unit) {
                "second" -> value * 1_000L
                "minute" -> value * 60_000L
                "hour"   -> value * 3_600_000L
                "day"    -> value * 86_400_000L
                "week"   -> value * 7 * 86_400_000L
                "month"  -> value * 30 * 86_400_000L
                "year"   -> value * 365 * 86_400_000L
                else     -> 0L
            }
            return System.currentTimeMillis() - deltaMs
        }
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
            val images = doc.select(selectors.pageImage)

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

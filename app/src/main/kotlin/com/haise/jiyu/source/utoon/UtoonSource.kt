package com.haise.jiyu.source.utoon

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * utoon.us - WordPress na vlastnim (ne Madara) motivu "mangaverse". Prvni davka karet
 * (katalog na homepage, hledani, seznam kapitol na detailu) je server-rendered primo v
 * HTML, ale "Nacist dalsi" tlacitko dotahuje dalsi stranky pres AJAX
 * (`wp-admin/admin-ajax.php`, action `mangaverse_load_more`) - vyzaduje WP nonce, ktery
 * appka vytahne regexem z `mangaverse_ajax` JS promenne vlozene primo do HTML - overeno
 * zive (PowerShell).
 *
 * Stranky kapitoly pouzivaji standardni lazysizes vzor (`<img class="lazyload"
 * data-src="...">` s placeholder data: URI v `src`) - realna URL je v `data-src`.
 *
 * Web nema zjevne strukturovane pole Status/Type/Autor na detailu - appka je proto
 * nechava prazdna/vychozi, misto hadani ze spatneho selektoru.
 */
@Singleton
class UtoonSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "utoon"
    override val name = "Utoon"
    override val homepageUrl get() = base
    private val root = "https://www.utoon.us"
    private val base = "$root/en"
    private val ajaxUrl = "$root/wp-admin/admin-ajax.php"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun loadMore(params: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply {
            add("action", "mangaverse_load_more")
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder().url(ajaxUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .post(form)
            .build()
        val body = client.newCall(req).execute().use { it.bodyOrThrow(ajaxUrl) }
        return JSONObject(body)
    }

    private val nonceRegex = Regex(""""nonce":"([a-f0-9]+)"""")
    private fun nonceOf(html: String): String? = nonceRegex.find(html)?.groupValues?.get(1)

    private val coverStyleRegex = Regex("""url\(['"]?([^'")]+)['"]?\)""")

    private fun parseCardList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("div.series-card").mapNotNull { card ->
            val link = card.selectFirst("a.series-card-link") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = card.selectFirst("h3.series-card-title")?.text()?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val style = card.selectFirst("div.series-card-thumb")?.attr("style").orEmpty()
            val cover = coverStyleRegex.find(style)?.groupValues?.get(1)?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page <= 1) {
                parseCardList(get("$base/"))
            } else {
                val homeHtml = get("$base/")
                val nonce = nonceOf(homeHtml) ?: return@withContext emptyList()
                val json = loadMore(mapOf("nonce" to nonce, "page" to page.toString(), "type" to "series_grid", "lang" to "en"))
                parseCardList(json.optJSONObject("data")?.optString("html").orEmpty())
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            if (page <= 1) {
                parseCardList(get("$base/?s=$q&lang=en"))
            } else {
                val searchHtml = get("$base/?s=$q&lang=en")
                val nonce = nonceOf(searchHtml) ?: return@withContext emptyList()
                val json = loadMore(mapOf("nonce" to nonce, "page" to page.toString(), "type" to "search", "search_query" to query, "lang" to "en"))
                parseCardList(json.optJSONObject("data")?.optString("html").orEmpty())
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            manga.copy(
                title = doc.selectFirst("h1.series-title")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.series-description")?.text()?.trim()?.ifBlank { null },
            )
        } catch (_: Exception) { manga }
    }

    private fun chapterFromArticle(article: org.jsoup.nodes.Element, mangaUrl: String): SChapter? {
        val link = article.selectFirst("a.chapter-link") ?: return null
        val href = link.absUrl("href").ifBlank { return null }
        val num = Regex("""chapter-([\d.]+)\.html""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val name = link.selectFirst("h3.chapter-title")?.text()?.trim()?.ifBlank { null } ?: "Chapter $num"
        return SChapter(sourceId = id, mangaUrl = mangaUrl, url = href, name = name, chapterNumber = num, dateUpload = System.currentTimeMillis())
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val doc: Document = Jsoup.parse(html, manga.url)
            val chapters = mutableListOf<SChapter>()
            doc.select("article.chapter-item").forEach { a -> chapterFromArticle(a, manga.url)?.let { chapters += it } }

            val nonce = nonceOf(html)
            val categoryId = doc.selectFirst("div.chapters-list")?.attr("data-category")?.ifBlank { null }
            if (nonce != null && categoryId != null) {
                var page = 1
                while (page <= 50) {
                    page++
                    val json = loadMore(
                        mapOf(
                            "nonce" to nonce,
                            "page" to page.toString(),
                            "type" to "series",
                            "category_id" to categoryId,
                            "order" to "desc",
                            "lang" to "en",
                        ),
                    )
                    val data = json.optJSONObject("data") ?: break
                    val fragmentDoc = Jsoup.parse(data.optString("html"), manga.url)
                    val pageChapters = fragmentDoc.select("article.chapter-item").mapNotNull { chapterFromArticle(it, manga.url) }
                    if (pageChapters.isEmpty()) break
                    chapters += pageChapters
                    if (!data.optBoolean("has_more", false)) break
                }
            }
            chapters.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url), chapter.url)
            doc.select("img[data-src]").mapIndexedNotNull { i, img ->
                val src = img.attr("data-src").trim().ifBlank { return@mapIndexedNotNull null }
                if (src.startsWith("data:")) return@mapIndexedNotNull null
                Page(i, src, src)
            }
        } catch (_: Exception) { emptyList() }
    }
}

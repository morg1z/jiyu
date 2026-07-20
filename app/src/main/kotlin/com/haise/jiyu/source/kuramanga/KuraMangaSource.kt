package com.haise.jiyu.source.kuramanga

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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseListJson(json: String): List<SManga> {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            val slug = m.optString("normalized_title").ifBlank { return@mapNotNull null }
            val title = m.optString("title").ifBlank { return@mapNotNull null }
            SManga(sourceId = id, url = "/$slug", title = title, coverUrl = m.optString("thumb").ifBlank { null })
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val offset = (page - 1) * 10
            parseListJson(get("$base/search?offset=$offset&ajax=1"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val offset = (page - 1) * 10
            parseListJson(get("$base/search?name=$q&offset=$offset&ajax=1"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
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
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
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
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("div.reader-width img[src*=/chapters/]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

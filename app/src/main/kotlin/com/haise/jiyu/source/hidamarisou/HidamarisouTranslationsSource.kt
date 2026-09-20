package com.haise.jiyu.source.hidamarisou

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
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
import org.json.JSONArray
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * hidamarisoutranslations.com - obycejny WordPress blog (NE manga tema), kazda
 * WP kategorie = jedna prekladana light novel serie (overeno zive: 19 kategorii,
 * napr. "Isekai walking" ma 778 prispevku = kapitol). Appka proto mapuje kategorii
 * na SManga a jednotlive prispevky v ni na SChapter - NOVEL typ obsahu.
 *
 * Web ma zapnute standardni WP REST API (`/wp-json/wp/v2/...`), pouzite misto
 * HTML scrapovani (Vzor A jako MangaDexSource) - `/categories` vraci cisty seznam
 * serii vcetne poctu kapitol, `/posts?categories={id}` vraci kapitoly vcetne
 * `content.rendered` (HTML text kapitoly), overeno zive na kategorii "big-ship".
 * `manga.url` je primo REST endpoint "$base/wp-json/wp/v2/posts?categories=$id"
 * (ne HTML stranka), aby getChapterList nemusel znovu hledat ID kategorie.
 */
@Singleton
class HidamarisouTranslationsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "hidamarisou"
    override val name = "Hidamarisou Translations"
    override val supportsSortOrder: Boolean get() = false
    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://hidamarisoutranslations.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun decodeHtmlEntities(text: String): String = Jsoup.parse(text).text()

    private fun categoryToManga(id: Int, name: String, count: Int): SManga = SManga(
        sourceId = this.id,
        url = "$base/wp-json/wp/v2/posts?categories=$id",
        title = decodeHtmlEntities(name),
        coverUrl = null,
        lastChapter = count.toFloat(),
    )

    private fun fetchCategories(): List<SManga> {
        val json = JSONArray(get("$base/wp-json/wp/v2/categories?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count"))
        return (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.optString("slug")
            if (slug == "uncategorized" || o.optInt("count") <= 0) return@mapNotNull null
            categoryToManga(o.optInt("id"), o.optString("name"), o.optInt("count"))
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page > 1) return@withContext emptyList()
            fetchCategories()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page > 1) return@withContext emptyList()
            fetchCategories().filter { it.title.contains(query, ignoreCase = true) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = manga

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (true) {
                val json = try {
                    JSONArray(get("${manga.url}&per_page=100&page=$page&_fields=id,date,link,title"))
                } catch (e: Exception) { e.rethrowIfControl(); break }
                if (json.length() == 0) break
                for (i in 0 until json.length()) {
                    val o = json.optJSONObject(i) ?: continue
                    val link = o.optString("link").ifBlank { continue }
                    val title = decodeHtmlEntities(o.optJSONObject("title")?.optString("rendered").orEmpty())
                        .ifBlank { "Post ${o.optInt("id")}" }
                    val dateMillis = parseIsoDate(o.optString("date"))
                    chapters += SChapter(
                        sourceId = id, mangaUrl = manga.url,
                        url = "$base/wp-json/wp/v2/posts/${o.optInt("id")}",
                        name = title, chapterNumber = (chapters.size + 1).toFloat(),
                        dateUpload = dateMillis,
                    )
                }
                if (json.length() < 100) break
                page++
                if (page > 20) break
            }
            // WP REST vraci od nejnovejsiho - appka chce od nejstarsiho pro spravne
            // cislovani kapitol, proto se seznam otoci a precisluje.
            chapters.reversed().mapIndexed { idx, c -> c.copy(chapterNumber = (idx + 1).toFloat()) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseIsoDate(iso: String): Long = try {
        java.time.LocalDateTime.parse(iso)
            .atZone(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); System.currentTimeMillis() }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val json = org.json.JSONObject(get("${chapter.url}?_fields=content"))
            val html = json.optJSONObject("content")?.optString("rendered").orEmpty()
            val text = Jsoup.parse(html).text().trim()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

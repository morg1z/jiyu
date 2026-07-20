package com.haise.jiyu.source.mangapark

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangapark.net/.org/.io presmerovavaji na malvertising/anti-adblock stenu
 * (mangapark.io, stejna rodina domen jako zabaveny bato.to), takze puvodni
 * GraphQL API (/apo/) uz neni dostupne. mangapark.page je samostatna zivá
 * domena bez GraphQL - misto toho server-rendered HTML (/series listing,
 * /series/{slug}.{hash} detail se schema.org microdata) plus dva
 * pomocne JSON endpointy zjistene z bundlovaneho JS:
 *  - /api/search?search={query} - vyhledavani (slug_hash je uz plna cast URL)
 *  - /get-chapter-list?slug={slug} - kompletni seznam kapitol (detailni
 *    stranka v prvotnim HTML ukazuje jen kapitolu 1 + poslednich ~18)
 */
@Singleton
class MangaParkSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangapark"
    override val name = "MangaPark"
    override val homepageUrl get() = base

    private val base = "https://mangapark.page"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.comic-item").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/series/]") ?: return@mapNotNull null
            val href = link.attr("href")
            val img = card.selectFirst("img.series-card-img")
            val title = card.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: img?.attr("alt")?.removePrefix("Cover of ")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/series?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val body = get("$base/api/search?search=$q&page=$page")
            val comics = JSONObject(body).optJSONArray("comics") ?: return@withContext emptyList()
            (0 until comics.length()).map { i ->
                val c = comics.getJSONObject(i)
                SManga(
                    sourceId = id,
                    url = "/series/${c.optString("slug_hash")}",
                    title = c.optString("title"),
                    coverUrl = c.optString("image").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Slug bez hashe pouzity v /get-chapter-list?slug=... - "gachiakuta.NTE9Qw" -> "gachiakuta". */
    private fun slugOf(manga: SManga) = manga.url.substringAfterLast("/").substringBefore(".")

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("[itemprop=image] img")?.let {
                    it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                }?.takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = doc.selectFirst("[itemprop=description]")?.text()?.trim(),
                genres = doc.select("a[itemprop=genre]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugOf(manga)
            val json = JSONObject(get("$base/get-chapter-list?slug=$slug"))
            if (!json.optBoolean("success")) return@withContext emptyList()
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).map { i ->
                val d = data.getJSONObject(i)
                val num = d.optDouble("chapter_num", 0.0).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "${manga.url}/${d.optString("chapter_slug")}",
                    name = d.optString("chapter_name", "Chapter $num"),
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.sortedByDescending { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img[data-number]").sortedBy { it.attr("data-number").toIntOrNull() ?: 0 }
                .mapIndexedNotNull { i, img ->
                    val url = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    Page(i, url, url)
                }
        } catch (_: Exception) { emptyList() }
    }
}

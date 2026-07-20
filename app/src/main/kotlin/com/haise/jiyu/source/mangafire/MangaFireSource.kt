package com.haise.jiyu.source.mangafire

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
 * mangafire.to je cisty klientsky (Vite/rolldown) SPA shell - `<div
 * id="app-root"></div>`, zadny obsah v HTML. Skutecna data se dotahuji az
 * po hydrataci z verejneho JSON API (zadne specialni hlavicky/auth), ktere
 * se nepodarilo najit statickou analyzou stazenych JS bundlu (hlavni
 * bundle obsahuje jen auth endpointy, zbytek je v lazy-loaded chunku) -
 * teprve inspekce network requestu v realnem prohlizeci ho odhalila:
 *  - listing/search: /api/titles?keyword=...&content_rating[]=safe&...
 *  - detail: /api/titles/{hid}  (hid = kratky kod pred prvni pomlckou v URL slugu)
 *  - kapitoly: /api/titles/{hid}/chapters?language=en&sort=number&order=desc
 *  - stranky kapitoly: /api/chapters/{chapterId}
 */
@Singleton
class MangaFireSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangafire"
    override val name = "MangaFire"
    override val homepageUrl get() = base

    private val base = "https://mangafire.to"
    private val apiBase = "$base/api"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun itemToSManga(o: JSONObject): SManga {
        val poster = o.optJSONObject("poster")
        val cover = poster?.optString("large")?.takeIf { it.isNotBlank() }
            ?: poster?.optString("medium")?.takeIf { it.isNotBlank() }
        return SManga(
            sourceId = id,
            url = o.optString("url"),
            title = o.optString("title"),
            coverUrl = cover,
        )
    }

    private fun parseList(body: String): List<SManga> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { itemToSManga(items.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get("$apiBase/titles?content_rating[]=safe&content_rating[]=suggestive&order[chapter_updated_at]=desc&hot=1&page=$page&limit=30"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$apiBase/titles?keyword=$q&content_rating[]=safe&content_rating[]=suggestive&page=$page&limit=30"))
        } catch (_: Exception) { emptyList() }
    }

    /** hid je kratky kod na zacatku URL slugu, napr. "/title/3x369-dragon-fragment" -> "3x369". */
    private fun hidOf(manga: SManga) = manga.url.substringAfterLast("/").substringBefore("-")

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val data = JSONObject(get("$apiBase/titles/${hidOf(manga)}")).optJSONObject("data") ?: return@withContext manga
            val poster = data.optJSONObject("poster")
            val cover = poster?.optString("large")?.takeIf { it.isNotBlank() } ?: manga.coverUrl
            val genres = data.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("title") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val author = data.optJSONArray("authors")?.let { arr ->
                if (arr.length() > 0) arr.getJSONObject(0).optString("title").takeIf { it.isNotBlank() } else null
            }
            val description = data.optString("synopsisHtml").takeIf { it.isNotBlank() }
                ?.let { Jsoup.parse(it).text() }
            manga.copy(
                title = data.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
                coverUrl = cover,
                description = description,
                genres = genres,
                author = author,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val hid = hidOf(manga)
            // API odmita limit > 200 (422 Unprocessable Entity); u serii s vice
            // nez 200 kapitolami by chybely ty nejstarsi (zadna dalsi strankovaci
            // logika zatim neni implementovana).
            val json = JSONObject(get("$apiBase/titles/$hid/chapters?language=en&sort=number&order=desc&page=1&limit=200"))
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).map { i ->
                val c = items.getJSONObject(i)
                val num = c.optDouble("number", 0.0).toFloat()
                val name = c.optString("name").takeIf { it.isNotBlank() } ?: "Chapter $num"
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = c.optLong("id").toString(),
                    name = name,
                    chapterNumber = num,
                    dateUpload = c.optLong("createdAt") * 1000L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val data = JSONObject(get("$apiBase/chapters/${chapter.url}")).optJSONObject("data") ?: return@withContext emptyList()
            val pages = data.optJSONArray("pages") ?: return@withContext emptyList()
            (0 until pages.length()).map { i ->
                val url = pages.getJSONObject(i).optString("url")
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

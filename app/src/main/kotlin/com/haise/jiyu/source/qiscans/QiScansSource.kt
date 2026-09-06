package com.haise.jiyu.source.qiscans

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * qimanga.com (puvodni "qimanhwa.com" URL z uzivatelovy tabulky na ni jen
 * 301 presmerovava, overeno zive) - bespoke Angular web, ale s VLASTNIM
 * plnohodnotnym JSON REST API na subdomene "api.qimanga.com/api/v1"
 * (overeno zive primo v HTML uvodni stranky, kde je API URL natvrdo v
 * embeddovanem skriptu) - zadne HTML scrapovani netreba, Vzor A jako
 * MangaDexSource.
 *
 * "/series" (paginovany vypis, max "perPage=100", overeno zive) podporuje
 * primo server-side "sort" (jedna z hodnot "latest"|"newest"|"popular"|
 * "alphabetical", overeno zive na ruznych sadach vysledku), "genre" (slug,
 * overeno zive - jiny slug = jina sada) a "type" (MANGA|MANHWA|MANHUA).
 * Zanrovy seznam je primo na "/series/genres" (bez auth, kompletni JSON
 * pole {id,name,slug}, overeno zive). Fulltextove hledani je SAMOSTATNY
 * server-side endpoint "/series/search?q=..." (min. 2 znaky, jina query
 * schema nez "/series" - genre/type/sort tady API tvrde odmita jako
 * neznamou property, overeno zive), ne parametr na "/series".
 *
 * Detail mangy "/series/{slug}" vraci genres/description/author/artist/
 * status primo v JSON, popis obsahuje HTML tagy (<p>), proto Jsoup.parse().text()
 * na strip. Seznam kapitol "/series/{slug}/chapters?page=N&perPage=100"
 * (max perPage=100, overeno zive - vetsi hodnota vraci 400) - vraci
 * nejnovejsi napřed, "totalPages" v odpovedi rika, kdy skoncit strankovani.
 * Stranky kapitoly primo v "/series/{slug}/chapters/{chapterSlug}" jako pole
 * "images" (uz serazene podle "order") - placene/zamknute kapitoly (price>0,
 * "requiresPurchase":true) vraci prazdne "images":[] (overeno zive), getPageList
 * pak proste vrati prazdny seznam bez pádu.
 */
@Singleton
class QiScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "qiscans"
    override val name = "QiScans"
    override val homepageUrl get() = base
    private val base = "https://qimanga.com"
    private val api = "https://api.qimanga.com/api/v1"

    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val arr = JSONArray(get("$api/series/genres"))
            val tags = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug").ifBlank { return@mapNotNull null }
                val label = o.optString("name").trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.uppercase()) {
        "MANHWA" -> "MANHWA"
        "MANHUA" -> "MANHUA"
        "NOVEL" -> "NOVEL"
        else -> "MANGA"
    }

    private fun seriesFromJson(o: JSONObject): SManga = SManga(
        sourceId = id,
        url = o.optString("slug"),
        title = o.optString("title"),
        coverUrl = o.optString("cover").ifBlank { null },
        status = o.optString("status").ifBlank { null }?.lowercase(),
        contentType = normalizeContentType(o.optString("type")),
        rating = o.optDouble("avgRating").takeIf { !it.isNaN() },
    )

    private fun parseListJson(json: String): List<SManga> {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("slug").isBlank() || o.optString("title").isBlank()) return@mapNotNull null
            seriesFromJson(o)
        }
    }

    private fun sortParam(sortBy: String): String = when (sortBy) {
        "latest", "newest", "popular", "alphabetical" -> sortBy
        else -> "popular"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder("$api/series?page=$page&perPage=24&sort=${sortParam(filter.sortBy)}")
            filter.genres.firstOrNull()?.let { sb.append("&genre=").append(URLEncoder.encode(it, "UTF-8")) }
            parseListJson(get(sb.toString()))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = query.trim()
            if (q.length < 2) return@withContext getPopular(page, filter)
            val url = "$api/series/search?q=${URLEncoder.encode(q, "UTF-8")}&page=$page&perPage=24"
            parseListJson(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(get("$api/series/${manga.url}"))
            val genres = o.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.trim()?.ifBlank { null } }
            } ?: manga.genres
            val descriptionHtml = o.optString("description").ifBlank { null }
            manga.copy(
                title = o.optString("title").ifBlank { manga.title },
                coverUrl = o.optString("cover").ifBlank { manga.coverUrl },
                description = descriptionHtml?.let { Jsoup.parse(it).text().trim().ifBlank { null } },
                genres = genres,
                author = o.optString("author").ifBlank { null },
                artist = o.optString("artist").ifBlank { null },
                status = o.optString("status").ifBlank { null }?.lowercase(),
                contentType = normalizeContentType(o.optString("type")),
            )
        } catch (_: Exception) { manga }
    }

    private fun parseIsoDate(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) { 0L }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (true) {
                val o = JSONObject(get("$api/series/${manga.url}/chapters?page=$page&perPage=100"))
                val arr = o.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val slug = c.optString("slug").ifBlank { continue }
                    val num = c.optDouble("number").takeIf { !it.isNaN() }?.toFloat() ?: continue
                    // "slug" je jen obecny retezec jako "chapter-52" (overeno zive), NENI
                    // globalne unikatni napric ruznymi seriemi - MangaRepository.chapterId
                    // pouziva "$sourceId::$url" jako DB klic bez ohledu na mangaUrl, proto
                    // se musi prefixovat slugem serie, jinak by dve ruzne serie se stejnym
                    // cislem kapitoly kolidovaly.
                    chapters += SChapter(
                        sourceId = id, mangaUrl = manga.url, url = "${manga.url}/$slug",
                        name = "Chapter ${if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()}",
                        chapterNumber = num,
                        dateUpload = parseIsoDate(c.optString("createdAt")),
                    )
                }
                val totalPages = o.optInt("totalPages", page)
                if (page >= totalPages) break
                page++
            }
            chapters.distinctBy { it.chapterNumber }.sortedByDescending { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterSlug = chapter.url.removePrefix("${chapter.mangaUrl}/")
            val o = JSONObject(get("$api/series/${chapter.mangaUrl}/chapters/$chapterSlug"))
            val images = o.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapIndexedNotNull { i, _ ->
                val url = images.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

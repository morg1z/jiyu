package com.haise.jiyu.source.hivetoons

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HiveToons (hivetoons.org) - nastupce Hive Scans (hivescans.com).
 *
 * 2026-10 audit: frontend hivetoons.org je mrtvy (307 redirect smycka na sebe
 * sama), ale backend `api.hivetoons.org` zije a obsluhuje kompletni data -
 * cely zdroj proto bezi ciste pres JSON API (drive se kombinovalo scrapovani
 * HTML frontendu + API pro zanry):
 *
 *  - GET /api/posts?page=N&perPage=M            vypis (max ~200/str., ~313 titulu)
 *  - GET /api/posts?slug={slug}                detail titulu (genres, status, rating)
 *  - GET /api/genres                           plocha taxonomie (~95 zanru)
 *  - GET /api/genres/{slug}/posts?page=N       zanrovy filtr
 *  - GET /api/chapters?postId={id}             seznam kapitol
 *  - GET /api/chapter?chapterId={id}           stranky kapitoly (images[].url/order)
 *
 * API nepodporuje server-side fulltext ani razeni (vsechny sort/search parametry
 * jsou ignorovany - overeno zive), proto se search i "latest" razeni resi lokalne
 * nad stazenym katalogem (je maly, cele se vejde do 2 requestu).
 */
@Singleton
class HiveToonsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hivetoons"
    override val name = "HiveToons"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://hivetoons.org"
    private val apiBase = "https://api.hivetoons.org"

    private fun getBody(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun getJson(url: String): JSONObject = JSONObject(getBody(url))

    // ─── parsovani post objektu ──────────────────────────────────────────────

    private fun contentTypeOf(post: JSONObject): String {
        val isNovel = post.optBoolean("isNovel", false)
        return when {
            isNovel -> "NOVEL"
            post.optString("seriesType").equals("MANHUA", ignoreCase = true) -> "MANHUA"
            post.optString("seriesType").equals("MANGA", ignoreCase = true) -> "MANGA"
            else -> "MANHWA"
        }
    }

    private fun postToSManga(post: JSONObject): SManga? {
        val slug = post.optString("slug").ifBlank { return null }
        val title = post.optString("postTitle").trim().ifBlank { return null }
        val cover = post.optString("featuredImage").ifBlank { null }
        return SManga(
            sourceId = id,
            url = "$base/series/$slug",
            title = title,
            coverUrl = cover,
            contentType = contentTypeOf(post),
        )
    }

    private fun parsePosts(json: JSONObject): List<SManga> {
        val posts = json.optJSONArray("posts") ?: return emptyList()
        return (0 until posts.length()).mapNotNull { i -> posts.optJSONObject(i)?.let(::postToSManga) }
    }

    // Vychozi API feed je uz razeny podle lastChapterAddedAt desc = "latest" je
    // identicky s "popular" (audit 2026-10) - prepinac razeni nema smysl.
    override val supportsSortOrder: Boolean get() = false

    // /api/query prijima seriesStatus= (ONGOING|COMPLETED|HIATUS|DROPPED) a
    // seriesType= (MANGA|MANHWA|MANHUA|NOVEL) a kombinuje je s genre={jmeno} -
    // overeno zive. /api/posts tyto parametry ignoruje.
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus", "cancelled")
    override val availableComicTypes: List<FilterTag> get() = listOf(
        FilterTag(id = "MANGA", label = "Manga"),
        FilterTag(id = "MANHWA", label = "Manhwa"),
        FilterTag(id = "MANHUA", label = "Manhua"),
        FilterTag(id = "NOVEL", label = "Novel"),
    )

    private val statusValues = mapOf(
        "ongoing" to "ONGOING", "completed" to "COMPLETED",
        "hiatus" to "HIATUS", "cancelled" to "DROPPED",
    )
    private val siteTypes = setOf("MANGA", "MANHWA", "MANHUA", "NOVEL")

    private fun hasExtraFilters(filter: MangaFilter): Boolean =
        filter.status != null || filter.comicTypes.isNotEmpty()

    /** /api/query - podporuje genre={jmeno} + seriesStatus + seriesType dohromady. */
    private fun queryList(page: Int, filter: MangaFilter): List<SManga> {
        val sb = StringBuilder("$apiBase/api/query?page=$page&perPage=24")
        filter.genres.firstOrNull()?.let { sb.append("&genre=").append(URLEncoder.encode(it, "UTF-8")) }
        statusValues[filter.status]?.let { sb.append("&seriesStatus=").append(it) }
        filter.comicTypes.firstOrNull()?.takeIf { it in siteTypes }?.let { sb.append("&seriesType=").append(it) }
        return try { parsePosts(getJson(sb.toString())) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── tagy ────────────────────────────────────────────────────────────────

    // /api/genres vraci plochou taxonomii bez "slug" pole - klientsky JS si slug
    // odvozuje jako encodeURIComponent(name.toLowerCase()), stejny vzorec tady.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val arr = JSONArray(getBody("$apiBase/api/genres"))
            val tags = (0 until arr.length()).mapNotNull { i ->
                val name = arr.optJSONObject(i)?.optString("name")?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null
                FilterTag(id = name, label = name)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreSlug(name: String): String =
        URLEncoder.encode(name.lowercase(), "UTF-8").replace("+", "%20")

    // ─── listing / search ────────────────────────────────────────────────────

    private fun genrePosts(page: Int, genre: String): List<SManga> = try {
        parsePosts(getJson("$apiBase/api/genres/${genreSlug(genre)}/posts?page=$page&perPage=20&filter="))
    } catch (e: Exception) { e.rethrowIfControl(); emptyList() }

    /** Cely katalog (~313 titulu) ve 2 requestech - pro search a latest razeni. */
    private fun catalog(): List<JSONObject> {
        val first = getJson("$apiBase/api/posts?page=1&perPage=200")
        val posts = first.optJSONArray("posts") ?: return emptyList()
        val total = first.optInt("totalCount", posts.length())
        val out = (0 until posts.length()).mapNotNull { posts.optJSONObject(it) }.toMutableList()
        if (out.size < total) {
            val rest = getJson("$apiBase/api/posts?page=2&perPage=200").optJSONArray("posts")
            if (rest != null) for (i in 0 until rest.length()) rest.optJSONObject(i)?.let(out::add)
        }
        return out
    }

    private val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** "2026-09-24T01:25:45.893Z" -> millis; neparsovatelnne = 0. */
    private fun parseDate(raw: String?): Long = runCatching {
        isoDate.parse(raw.orEmpty().substringBefore('.').substringBefore('Z'))?.time ?: 0L
    }.getOrDefault(0L)

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (hasExtraFilters(filter)) return@withContext queryList(page, filter)
        filter.genres.firstOrNull()?.let { return@withContext genrePosts(page, it) }
        try {
            if (filter.sortBy == "latest") {
                // API razeni ignoruje - "latest" = lokalni sort podle lastChapterAddedAt.
                val all = catalog().sortedByDescending {
                    parseDate(it.optString("lastChapterAddedAt"))
                }.mapNotNull(::postToSManga)
                return@withContext all.drop((page - 1) * 24).take(24)
            }
            parsePosts(getJson("$apiBase/api/posts?page=$page&perPage=24"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (hasExtraFilters(filter)) return@withContext queryList(page, filter)
        filter.genres.firstOrNull()?.let { return@withContext genrePosts(page, it) }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            catalog().filter {
                it.optString("postTitle").contains(query, ignoreCase = true) ||
                    it.optString("alternativeTitles").contains(query, ignoreCase = true)
            }.mapNotNull(::postToSManga).drop((page - 1) * 24).take(24)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── detail / kapitoly / stranky ─────────────────────────────────────────

    private fun slugOf(manga: SManga) = manga.url.substringAfterLast("/")

    /** post JSON pres slug lookup + interni id pro chapters endpoint. */
    private fun fetchPost(slug: String): JSONObject? =
        getJson("$apiBase/api/posts?slug=${URLEncoder.encode(slug, "UTF-8")}")
            .optJSONArray("posts")?.optJSONObject(0)

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val post = fetchPost(slugOf(manga)) ?: return@withContext manga
            val genres = post.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.trim()?.ifBlank { null } }
            } ?: emptyList()
            manga.copy(
                title = post.optString("postTitle").trim().ifBlank { manga.title },
                coverUrl = post.optString("featuredImage").ifBlank { manga.coverUrl },
                genres = genres,
                status = post.optString("seriesStatus").lowercase().ifBlank { null },
                contentType = contentTypeOf(post),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val postId = fetchPost(slugOf(manga))?.optLong("id", -1)?.takeIf { it >= 0 }
                ?: return@withContext emptyList()
            val chapters = getJson("$apiBase/api/chapters?postId=$postId")
                .optJSONObject("post")?.optJSONArray("chapters") ?: return@withContext emptyList()
            (0 until chapters.length()).mapNotNull { i ->
                val c = chapters.optJSONObject(i) ?: return@mapNotNull null
                // Zamcene kapitoly (coin/unlock) preskocit - pages by stejne nevratily obsah.
                if (c.optBoolean("isLocked") || !c.optBoolean("isAccessible", true)) return@mapNotNull null
                val cid = c.optLong("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val num = c.optDouble("number", 0.0).toFloat()
                val date = parseDate(c.optString("createdAt"))
                val name = c.optString("title").trim().ifBlank {
                    "Chapter ${if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()}"
                }
                SChapter(sourceId = id, mangaUrl = manga.url, url = cid.toString(),
                    name = name, chapterNumber = num, dateUpload = date)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val images = getJson("$apiBase/api/chapter?chapterId=${chapter.url}")
                .optJSONObject("chapter")?.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapNotNull { i ->
                val img = images.optJSONObject(i) ?: return@mapNotNull null
                val url = img.optString("url").ifBlank { return@mapNotNull null }
                Page(img.optInt("order", i), url, url)
            }.sortedBy { it.index }.mapIndexed { i, p -> p.copy(index = i) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

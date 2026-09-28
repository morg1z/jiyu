package com.haise.jiyu.source.globalcomix

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GlobalComix (globalcomix.com) - oficialni platforma s komiksy: indie tituly zdarma
 * (cele cistelne) + placene licencovane tituly (DC, Marvel, Image…) s ~10 stranami
 * nahledu zdarma. Frontend je SPA, ale pod nim sedi verejne JSON API, ktere si web
 * autorizuje klicem z `window.gc.global.api_key` (v HTML kazde stranky):
 *
 *  - GET /v1/search/query?q=&context=series&p=N&perpage=24&enrich=1&sort=S&comic_genre_id=G
 *    vypis/hledani/filtry (sorty: popular|featured|recent|new|views; zanr = ciselne id)
 *  - GET /v1/comics/{slug}                    detail serie (popis, status, autor, zanr)
 *  - GET /v1/comics/{id}/releases?lang_id=en&all=true   seznam cisel/kapitol
 *  - GET /v1/readV3/{releaseKey}?readerV=2    stranky: reader_cdn_access.url_template
 *    + Set-Cookie gc_reader_auth (JWT scoped na /r/{key}/) - do CookieManageru ji ulozi
 *    WebViewCookieInterceptor, k obrazkum na reader-cdn.globalcomix.com ji prilepi
 *    GlobalComixCdnCookieInterceptor v image klientovi (AppModule).
 *  - GET /v1/app/init                         taxonomie (comic_genres -> tagy)
 *
 * API hlavicky: `X-Gc-Client: {api_key}` + `X-Gc-Identmode: cookie`. Klic rotuje s
 * deployi webu - tahame ho z homepage HTML a cachujeme; hardcoded hodnota je jen
 * fallback pro prvni request (overeno 2026-10).
 */
@Singleton
class GlobalComixSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "globalcomix"
    override val name = "GlobalComix"
    override val contentType = "COMIC"
    override val homepageUrl = "https://globalcomix.com"
    override val supportsTagFilter: Boolean get() = true
    override val availableSorts: Set<String> get() = SORTS.keys

    private val api = "https://api.globalcomix.com/v1"

    @Volatile private var cachedApiKey: String? = null
    private val apiKeyMutex = Mutex()

    private suspend fun apiKey(): String {
        cachedApiKey?.let { return it }
        return apiKeyMutex.withLock {
            cachedApiKey?.let { return@withLock it }
            val fetched = withContext(Dispatchers.IO) {
                try {
                    val html = plainGet(homepageUrl)
                    API_KEY_RE.find(html)?.groupValues?.get(1)
                } catch (e: Exception) {
                    e.rethrowIfControl(); null
                }
            }
            (fetched ?: FALLBACK_API_KEY).also { cachedApiKey = it }
        }
    }

    private fun plainGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private suspend fun apiGet(path: String): JSONObject = withContext(Dispatchers.IO) {
        var key = apiKey()
        var json = requestJson(path, key)
        // Klic se po deployi webu muze zmenit - jednou ho obnovime a zopakujeme.
        if (json.optString("error") == "CLIENT_HEADER_MISSING") {
            cachedApiKey = null
            key = apiKey()
            json = requestJson(path, key)
        }
        json
    }

    private fun requestJson(path: String, key: String): JSONObject {
        val req = Request.Builder().url("$api$path")
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "application/json")
            .header("X-Gc-Client", key)
            .header("X-Gc-Identmode", "cookie")
            .build()
        return client.newCall(req).execute().use { JSONObject(it.bodyOrThrow("$api$path")) }
    }

    // ─── Listing / hledani ───────────────────────────────────────────────────

    private fun seriesToSManga(o: JSONObject): SManga? {
        val slug = o.optString("slug").ifBlank { return null }
        val title = o.optString("name").trim().ifBlank { return null }
        return SManga(
            sourceId = id,
            url = "/c/$slug",
            title = title,
            // search/query items nesou cover_image_url (image_url maji az detail/Comic- objekty)
            coverUrl = o.optString("cover_image_url").ifBlank { null }
                ?: o.optString("image_url").ifBlank { null }
                ?: o.optString("image_small_url").ifBlank { null },
            contentType = "COMIC",
            lastChapter = o.optInt("total_releases").takeIf { it > 0 }?.toFloat(),
            author = o.optString("artist_name").ifBlank { null },
        )
    }

    private fun sortParam(sortBy: String): String = SORTS[sortBy] ?: "popular"

    private fun listingPath(page: Int, filter: MangaFilter, query: String): String {
        val q = StringBuilder("/search/query?q=${URLEncoder.encode(query, "UTF-8")}")
            .append("&context=series&p=").append(page).append("&perpage=24&enrich=1")
            .append("&sort=").append(sortParam(filter.sortBy))
        filter.genres.firstOrNull()?.let { q.append("&comic_genre_id=").append(it) }
        return q.toString()
    }

    private suspend fun fetchListing(page: Int, filter: MangaFilter, query: String): List<SManga> {
        val results = apiGet(listingPath(page, filter, query))
            .optJSONObject("payload")?.optJSONObject("results") ?: return emptyList()
        val items = results.optJSONObject("series")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { seriesToSManga(items.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = try {
        fetchListing(page, filter, "")
    } catch (e: Exception) { e.rethrowIfControl(); emptyList() }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = try {
        fetchListing(page, filter, query)
    } catch (e: Exception) { e.rethrowIfControl(); emptyList() }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val slug = manga.url.substringAfter("/c/").trimEnd('/')
        val d = apiGet("/comics/$slug").optJSONObject("payload")?.optJSONObject("results")
            ?: return@withContext manga
        manga.copy(
            title = d.optString("name").ifBlank { manga.title },
            description = d.optString("parsed_description").ifBlank { null }
                ?: d.optString("description").ifBlank { null },
            coverUrl = d.optString("image_url").ifBlank { null } ?: manga.coverUrl,
            status = d.optString("status_name").ifBlank { null },
            author = d.optJSONObject("artist")?.optString("name")?.ifBlank { null },
            genres = listOfNotNull(d.optString("category_name").ifBlank { null }),
            year = d.optInt("year").takeIf { it > 0 },
        )
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    private val publishDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = manga.url.substringAfter("/c/").trimEnd('/')
            val comicId = apiGet("/comics/$slug")
                .optJSONObject("payload")?.optJSONObject("results")?.optInt("id") ?: return@withContext emptyList()
            val releases = apiGet("/comics/$comicId/releases?lang_id=en&all=true")
                .optJSONObject("payload")?.optJSONArray("results") ?: return@withContext emptyList()
            (0 until releases.length()).mapNotNull { i ->
                val r = releases.getJSONObject(i)
                if (!r.optBoolean("is_published", true) || !r.optBoolean("is_public_now", true)) return@mapNotNull null
                val key = r.optString("key").ifBlank { return@mapNotNull null }
                val num = r.optString("chapter").toFloatOrNull() ?: (i + 1).toFloat()
                val title = r.optString("title").trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/r/$key",
                    name = title.ifBlank { "Chapter ${r.optString("chapter")}" },
                    chapterNumber = num,
                    dateUpload = r.optString("published_time").let {
                        try { publishDate.parse(it)?.time ?: 0L } catch (_: Exception) { 0L }
                    },
                )
            }.sortedByDescending { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── Stranky ─────────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val key = chapter.url.substringAfter("/r/").substringBefore('/')
            val results = apiGet("/readV3/$key?readerV=2")
                .optJSONObject("payload")?.optJSONObject("results") ?: return@withContext emptyList()
            val access = results.optJSONObject("reader_cdn_access") ?: return@withContext emptyList()
            val baseUrl = access.optString("base_url").trimEnd('/')
            val releaseKey = access.optString("release_key").ifBlank { key }
            val template = access.optString("url_template")
                .ifBlank { "{base_url}/r/{release_key}/p/{order}/{quality}.webp" }
                .replace("{base_url}", baseUrl).replace("{release_key}", releaseKey)

            // ranges [[od,do],...] = stranky, ke kterym cookie gc_reader_auth povoluje pristup
            // (u placenych cisel jen free nahled). page_objects s is_page_paid preskocime.
            val paidOrders = results.optJSONArray("page_objects")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    arr.getJSONObject(it).takeIf { p -> p.optBoolean("is_page_paid") }?.optInt("order")
                }.toSet()
            } ?: emptySet()
            val orders = mutableListOf<Int>()
            access.optJSONArray("ranges")?.let { ranges ->
                for (i in 0 until ranges.length()) {
                    val r = ranges.getJSONArray(i)
                    for (o in r.getInt(0)..r.getInt(1)) orders += o
                }
            }
            orders.sorted().filter { it !in paidOrders }.mapIndexed { idx, order ->
                Page(idx, template.replace("{order}", order.toString()).replace("{quality}", "desktop"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── Tagy (zanry) ────────────────────────────────────────────────────────

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> {
        cachedTags?.let { return it }
        val tags = try {
            apiGet("/app/init").optJSONObject("payload")?.optJSONObject("results")
                ?.optJSONArray("comic_genres")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val g = arr.getJSONObject(i)
                        val gid = g.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                        val label = g.optString("name").ifBlank { return@mapNotNull null }
                        FilterTag(id = gid.toString(), label = label)
                    }
                } ?: emptyList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        cachedTags = tags
        return tags
    }

    private companion object {
        /** app sortBy -> API sort param ("latest" odpovida webovemu "recent"). */
        val SORTS = mapOf(
            "popular" to "popular",
            "latest" to "recent",
            "new" to "new",
            "views" to "views",
            "featured" to "featured",
        )
        val API_KEY_RE = Regex("""["']api_key["']\s*:\s*["'](gck_[0-9a-f]+)["']""")
        /** Verejny klic weboveho klienta (z window.gc.global) - fallback, cerstvy se taha z HTML. */
        const val FALLBACK_API_KEY = "gck_b4d492261ec541eda44ce41de79da424"
    }
}

package com.haise.jiyu.source.nyxscans

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.normalizeContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * nyxscans.com - bespoke Next.js web, NE Madara/WordPress. Frontend fetchuje data
 * primo z verejne pristupneho JSON API na subdomene "api.nyxscans.com" (nalezeno
 * primo v HTML jako `window.__API_URL__`, overeno zive) - appka jde primo na API
 * misto HTML scrapovani (Vzor A jako MangaDexSource), zadna autentizace netreba.
 *
 * Vypis "GET /api/posts?page=N&perPage=20&searchTerm=..." vraci `{posts:[...],
 * novelPosts:[...], totalCount, novelTotalCount}` - "searchTerm" FUNKCNI server-side
 * (overeno zive - ruzne dotazy vraci ruzne, spravne filtrovane sady napric strankami),
 * ale "totalCount" v odpovedi je vzdy CELKOVY pocet vsech titulu bez ohledu na filtr
 * (chyba/nedodelek API, overeno zive) - appka ho proto ignoruje a konec strankovani
 * pozna jen podle prazdneho seznamu. Bez "searchTerm" vraci vychozi razeni webu
 * (nejnovejsi aktivita napřed, pripnute tituly navrch).
 *
 * Detail "GET /api/post/public?postSlug={slug}" -> `{post:{...}, firstChapter,
 * lastChapter, totalChapterCount}` (parametr je "postSlug", NE "slug" - overeno
 * zive, jinak 400). Popis je HTML v "postContent" (Jsoup na text), zanry
 * `"genres":[{id,name,color}]`.
 *
 * Seznam kapitol "GET /api/post/chapters?postSlug={slug}" -> proste pole objektu,
 * jiz serazene od nejstarsi po nejnovejsi (overeno zive). Zamcene kapitoly za mincemi
 * maji "isLocked":true/"isAccessible":false, ale porad se vraci v seznamu (appka je
 * necha byt, jen jejich getPageList vrati prazdno).
 *
 * Zadne API pro obrazky stranek kapitoly nebylo nalezeno (neni v zadnem JS bundlu
 * pro cist stranku) - stranky kapitoly jsou misto toho primo server-rendered v HTML
 * detailu kapitoly jako `storage.nyxscans.com/.../page-{cislo}_...` (overeno zive),
 * appka je proto pro tenhle jeden krok scrapuje regexem misto API volani. Zamcene
 * kapitoly maji v HTML jen nahledovy obrazek serie (ne skutecne stranky) - ten
 * regex diky pevnemu vzoru "page-" v nazvu souboru prirozene neodchyti.
 */
@Singleton
class NyxScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "nyxscans"
    override val name = "Nyx Scans"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://nyxscans.com"
    private val api = "https://api.nyxscans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun mangaFromJson(o: JSONObject): SManga? {
        val slug = o.optString("slug").ifBlank { return null }
        val title = o.optString("postTitle").ifBlank { return null }
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.trim()?.ifBlank { null } }
        } ?: emptyList()
        return SManga(
            sourceId = id, url = "/series/$slug", title = title,
            coverUrl = o.optString("featuredImage").ifBlank { null },
            status = o.optString("seriesStatus").ifBlank { null }?.lowercase(),
            contentType = if (o.optBoolean("isNovel")) "NOVEL" else normalizeContentType(o.optString("seriesType"), default = "MANHWA"),
            genres = genres,
            rating = if (o.has("averageRating")) o.optDouble("averageRating").takeIf { !it.isNaN() } else null,
        )
    }

    private fun parseListJson(json: String): List<SManga> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("posts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { mangaFromJson(it) } }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseListJson(get("$api/api/posts?page=$page&perPage=20"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseListJson(get("$api/api/posts?page=$page&perPage=20&searchTerm=$q"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun slugFromMangaUrl(mangaUrl: String) = mangaUrl.removePrefix("/series/")

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val slug = slugFromMangaUrl(manga.url)
            val root = JSONObject(get("$api/api/post/public?postSlug=$slug"))
            val p = root.optJSONObject("post") ?: return@withContext manga
            val genres = p.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.trim()?.ifBlank { null } }
            } ?: manga.genres
            val descriptionHtml = p.optString("postContent")
            manga.copy(
                title = p.optString("postTitle").ifBlank { manga.title },
                coverUrl = p.optString("featuredImage").ifBlank { null } ?: manga.coverUrl,
                description = if (descriptionHtml.isBlank()) null else Jsoup.parse(descriptionHtml).text().trim().ifBlank { null },
                status = p.optString("seriesStatus").ifBlank { null }?.lowercase() ?: manga.status,
                author = p.optString("author").ifBlank { null },
                artist = p.optString("artist").ifBlank { null },
                genres = genres,
                contentType = if (p.optBoolean("isNovel")) "NOVEL" else normalizeContentType(p.optString("seriesType").ifBlank { null }, default = "MANHWA"),
                alternateTitles = p.optString("alternativeTitles").ifBlank { null }?.let { listOf(it.trim()) } ?: manga.alternateTitles,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun parseIsoDate(iso: String): Long = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugFromMangaUrl(manga.url)
            val arr = JSONArray(get("$api/api/post/chapters?postSlug=$slug"))
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val chapterSlug = c.optString("slug").ifBlank { return@mapNotNull null }
                val num = c.optDouble("number").takeIf { !it.isNaN() }?.toFloat() ?: return@mapNotNull null
                val title = c.optString("title").ifBlank { null }
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = "/series/$slug/$chapterSlug",
                    name = title ?: "Chapter $num",
                    chapterNumber = num,
                    dateUpload = parseIsoDate(c.optString("createdAt")),
                )
            }.sortedByDescending { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private val pageImageRegex = Regex("""https://storage\.nyxscans\.com/[a-zA-Z0-9_/.-]*/page-(\d+)[a-zA-Z0-9_.-]*\.(?:webp|jpe?g|png)""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(resolveSourceUrl(base, chapter.url))
            pageImageRegex.findAll(html)
                .map { it.value to (it.groupValues[1].toIntOrNull() ?: 0) }
                .distinctBy { it.first }
                .sortedBy { it.second }
                .mapIndexed { i, (url, _) -> Page(i, url, url) }
                .toList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

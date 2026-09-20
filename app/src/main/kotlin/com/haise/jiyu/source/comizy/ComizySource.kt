package com.haise.jiyu.source.comizy

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
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comizy (comizy.io) - nastupce MangaBuddy (mangabuddy.com), ktery mezitim
 * kompletne prepsali na Next.js. Misto HTML selektoru se parsuje JSON ze
 * `<script id="__NEXT_DATA__">` - je stabilnejsi nez CSS selektory a obsahuje
 * uplna strukturovana data (nazev/popis/zanry/kapitoly/obrazky stranek).
 *
 * Zname omezeni: `initialManga.chapters` na detailu titulu vraci jen ~50
 * nejnovejsich kapitol (stranka nema server-rendered plnou historii) - u
 * dlouhych serialu tak nemusi jit dohledat uplne prvni kapitoly.
 */
@Singleton
class ComizySource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "comizy"
    override val name = "Comizy"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    override val supportsChapterComments: Boolean get() = true
    private val base = "https://comizy.io"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun nextData(html: String): JSONObject? {
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        return try { JSONObject(json) } catch (e: Exception) { e.rethrowIfControl(); null }
    }

    private fun pageProps(root: JSONObject): JSONObject =
        root.getJSONObject("props").getJSONObject("pageProps")

    private fun itemToManga(o: JSONObject): SManga = SManga(
        sourceId = id,
        url = base + o.optString("url"),
        title = o.optString("name"),
        coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
        contentType = "MANHWA",
    )

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreArchive(filter.genres.first(), page)
            }
            // Puvodni kod vzdy cetl /latest, i pro "Popularni" - web ale ma i samostatnou
            // /popular cestu se stejnym __NEXT_DATA__ tvarem (overeno zive, jine tituly).
            val path = if (filter.sortBy == "latest") "latest" else "popular"
            val props = pageProps(nextData(get("$base/$path?page=$page")) ?: return@withContext emptyList())
            val items = props.getJSONArray("items")
            (0 until items.length()).map { itemToManga(items.getJSONObject(it)) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreArchive(filter.genres.first(), page)
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val props = pageProps(nextData(get("$base/search?q=$q&page=$page")) ?: return@withContext emptyList())
            val items = props.getJSONArray("ssrItems")
            (0 until items.length()).map { itemToManga(items.getJSONObject(it)) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── Filtrování podle žánru ────────────────────────────────────────────────

    override val supportsTagFilter: Boolean get() = true

    // "/genres" stranka vrati kompletni (~70 polozek), staticky seznam zanru primo
    // v __NEXT_DATA__ - overeno zive 2026-09-04, stejny vzor kesovani jako u
    // ostatnich zdroju s vlastnim seznamem zanru.
    @Volatile private var cachedGenres: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedGenres?.let { return@withContext it }
        try {
            val props = pageProps(nextData(get("$base/genres")) ?: return@withContext emptyList())
            val arr = props.getJSONArray("genres")
            val tags = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val slug = o.optString("slug").ifBlank { return@mapNotNull null }
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = name)
            }.sortedBy { it.label }
            cachedGenres = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /**
     * "/genres/{slug}" archivni stranka - overeno zive, ze vraci jiny vysledek
     * (a jinou pocitanou strankovaci strukturu) nez populardni/latest, a stranka
     * respektuje "?page=N". Kombinace vice zanru najednou web nepodporuje - pri
     * vice vybranych se pouzije prvni (stejny vzor jako u MadaraSource).
     */
    private fun parseGenreArchive(slug: String, page: Int): List<SManga> {
        val props = pageProps(nextData(get("$base/genres/$slug?page=$page")) ?: return emptyList())
        val items = props.getJSONArray("items")
        return (0 until items.length()).map { itemToManga(items.getJSONObject(it)) }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get(manga.url)) ?: return@withContext manga)
            val im = props.getJSONObject("initialManga")
            val genresArr = im.optJSONArray("genres")
            val genres = genresArr?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name").takeIf(String::isNotBlank) }
            } ?: emptyList()
            manga.copy(
                title = im.optString("name", manga.title),
                coverUrl = im.optString("cover").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = im.optString("summary").takeIf { it.isNotBlank() },
                status = im.optString("status").takeIf { it.isNotBlank() },
                genres = genres,
                contentType = "MANHWA",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get(manga.url)) ?: return@withContext emptyList())
            val chapters = props.getJSONObject("initialManga").getJSONArray("chapters")
            (0 until chapters.length()).map { i ->
                val c = chapters.getJSONObject(i)
                val number = c.optDouble("number", 0.0)
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = base + c.optString("url"),
                    name = c.optString("name").ifBlank { "Chapter $number" },
                    chapterNumber = number.toFloat(),
                    dateUpload = parseIsoDate(c.optString("updatedAt")),
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseIsoDate(text: String): Long = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get(chapter.url)) ?: return@withContext emptyList())
            val images = props.getJSONObject("initialChapter").getJSONArray("images")
            (0 until images.length()).map { i -> Page(i, images.getString(i), images.getString(i)) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                val ic = pageProps(nextData(get(chapter.url)) ?: return@withContext emptyList())
                    .getJSONObject("initialChapter")
                com.haise.jiyu.source.comments.parseMangaReaderJsonComments(ic)
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
}

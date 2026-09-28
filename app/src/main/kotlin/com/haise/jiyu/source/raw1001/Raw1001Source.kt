package com.haise.jiyu.source.raw1001

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.absoluteMediaUrl
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
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * raw1001.net - RAW (japonske/cinske) manga, vlastni sablona. Kapitoly maji
 * cislo potrebne pro cteni (`/ajax/image/list/chap/{numericId}`) schovane v
 * JSON-LD breadcrumb datech na detailu (`/chapters/{slug}/{chapterSlug}/{id}`),
 * ne v samotnem odkazu na kapitolu (ten pouziva jen `{slug}` bez `{id}`).
 */
@Singleton
class Raw1001Source @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "raw1001"
    override val name = "raw1001"
    override val language = "ja" // japonsky raw web (html lang="ja", overeno zive)
    override val homepageUrl get() = base
    override val supportsTagFilter: Boolean get() = true
    private val base = "https://raw1001.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = when {
                filter.genres.isNotEmpty() -> "$base/genres/${filter.genres.first()}?page=$page"
                // Bez parametru web razeni vraci podle posledni aktualizace (overeno zivě) -
                // to je presne "Nejnovejsi", ne "Populární". Skutecnou popularitu dava az
                // explicitni sort=views.
                filter.sortBy == "latest" -> "$base/all-manga/$page"
                else -> "$base/all-manga/$page?sort=views"
            }
            val doc = Jsoup.parse(get(url))
            val mangaHref = Regex("""^https://raw1001\.net/manga/[a-zA-Z0-9-]+$""")
            doc.select("a:has(img)").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                if (!mangaHref.matches(href)) return@mapNotNull null
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
                // Bug fix - cover URL je na webu relativni cesta ("/uploads/..."), ne
                // absolutni URL - "startsWith(http)" test vsechno vyfiltroval, coverUrl
                // vzdy vyslo null (nahlaseno jako "covery se nenacitaji").
                val rawCover = img.lazySrc().orEmpty().trim()
                val cover = when {
                    rawCover.startsWith("http") -> rawCover
                    rawCover.startsWith("/") -> "$base$rawCover"
                    else -> null
                }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Zanrovych odkazu "/genres/{slug}" je na homepage pres 700 (nazev v "title"
    // atributu). Archiv "/genres/{slug}?page=N" vraci stejny layout jako
    // /all-manga/. Vice zanru najednou web nepodporuje - pri vice vybranych se
    // pouzije prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            Jsoup.parse(get("$base/")).select("a[href*=/genres/]").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/genres/").trim('/').ifBlank { return@mapNotNull null }
                val label = a.attr("title").trim().ifBlank { a.text().trim() }.ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    // Web zadny search endpoint nema - lokalni fallback: projde omezeny pocet
    // stranek katalogu /all-manga/ a filtruje tituly podle dotazu (vzor
    // Manhwa210Source). Katalog ma ~16 titulu/stranu, strop 15 stranek.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val needle = query.trim().lowercase()
            if (page > 1) return@withContext emptyList()
            val out = mutableListOf<SManga>()
            try {
                for (p in 1..15) {
                    val items = getPopular(p, MangaFilter(sortBy = "latest"))
                    if (items.isEmpty()) break
                    out += items.filter { it.title.lowercase().contains(needle) }
                }
            } catch (e: Exception) { e.rethrowIfControl() }
            out.distinctBy { it.url }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(genres = doc.select("a[href*=/genres/]").map { it.text().trim() }.filter { it.isNotBlank() })
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val mangaSlug = manga.url.trimEnd('/').substringAfterLast('/')
            Regex("""raw1001\.net\\/chapters\\/$mangaSlug\\/([a-zA-Z0-9]+)\\/(\d+)""")
                .findAll(html)
                .map { it.groupValues[1] to it.groupValues[2] }
                .distinct()
                .map { (chapterSlug, chapterId) ->
                    val num = Regex("""\d+(?:\.\d+)?""").find(chapterSlug)?.value?.toFloatOrNull() ?: 0f
                    SChapter(sourceId = id, mangaUrl = manga.url, url = chapterId, name = chapterSlug, chapterNumber = num, dateUpload = 0L)
                }
                .toList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$base/ajax/image/list/chap/${chapter.url}"))
            if (!json.optBoolean("status")) return@withContext emptyList()
            val fragmentHtml = json.optString("html")
            val doc = Jsoup.parse(fragmentHtml)
            doc.select("a.readImg").mapIndexedNotNull { i, a ->
                val url = a.attr("href").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

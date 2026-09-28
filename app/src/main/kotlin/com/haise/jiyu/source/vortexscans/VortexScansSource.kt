package com.haise.jiyu.source.vortexscans

import com.haise.jiyu.util.resolveSourceUrl
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
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * vortexscans.org - drive Astro s hydratovanymi "islands", dnes React SPA
 * (react-aria komponenty, data-rac atributy). Seznam kapitol si detailni
 * stranka natahuje cely az na zalozce Kapitoly - ta je server-renderovana
 * na `?tab=chapters`, takze staci jeden GET navic a postId (drive tahane
 * regexem z hydration props) se vubec nepotrebuje. Stranky kapitoly jsou
 * server-renderovane <img> se src ".../page-0001_...webp".
 *
 * Zdrojova API pro fulltextove vyhledavani (/api/posts?search=...) parametr
 * search tise ignoruje, proto se search resi filtrovanim vysledku getPopular.
 */
@Singleton
class VortexScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "vortexscans"
    override val name = "Vortex Scans"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://vortexscans.org"
    private val apiBase = "https://api.vortexscans.org"

    // Frontend je hydratovany Astro island (viz komentar vyse) - filtrovaci UI vola
    // vlastni JSON API `GET /api/query?view=archive&genreIds={id}&page=..&perPage=..`
    // (zjisteno z bundlovaneho JS `_vcomics/DP1_5tmv.js`), zatimco seznam vsech
    // dostupnych zanru je na `GET /api/genres`. Overeno zive: genreIds=9 (Horror)
    // vraci jiny seznam titulu nez nefiltrovany dotaz.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val json = JSONArray(get("$apiBase/api/genres"))
            val tags = (0 until json.length()).mapNotNull { i ->
                val o = json.getJSONObject(i)
                val id = o.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val name = o.optString("name").trim().ifBlank { return@mapNotNull null }
                FilterTag(id = id.toString(), label = name)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseQueryList(json: String): List<SManga> {
        val posts = JSONObject(json).optJSONArray("posts") ?: return emptyList()
        return (0 until posts.length()).mapNotNull { i ->
            val p = posts.getJSONObject(i)
            val slug = p.optString("slug").ifBlank { return@mapNotNull null }
            val title = p.optString("postTitle").ifBlank { return@mapNotNull null }
            val cover = p.optString("featuredImage").ifBlank { null }
            SManga(sourceId = id, url = "/series/$slug", title = title, coverUrl = cover)
        }
    }

    // /api/query prijima i seriesStatus= (ONGOING|COMPLETED|HIATUS|DROPPED) a
    // seriesType= (MANGA|MANHWA|MANHUA) - overeno zive: seriesStatus=COMPLETED
    // vraci jen COMPLETED, seriesType=MANGA jen MANGA.
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus", "cancelled")
    override val availableComicTypes: List<FilterTag> get() = listOf(
        FilterTag(id = "MANGA", label = "Manga"),
        FilterTag(id = "MANHWA", label = "Manhwa"),
        FilterTag(id = "MANHUA", label = "Manhua"),
    )

    private val statusValues = mapOf(
        "ongoing" to "ONGOING", "completed" to "COMPLETED",
        "hiatus" to "HIATUS", "cancelled" to "DROPPED",
    )
    private val siteTypes = setOf("MANGA", "MANHWA", "MANHUA")

    private fun queryUrl(page: Int, filter: MangaFilter): String = buildString {
        append("$apiBase/api/query?page=").append(page).append("&perPage=20&view=archive")
        filter.genres.firstOrNull()?.let { append("&genreIds=").append(it) }
        statusValues[filter.status]?.let { append("&seriesStatus=").append(it) }
        filter.comicTypes.firstOrNull()?.takeIf { it in siteTypes }?.let { append("&seriesType=").append(it) }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=/series/]").toList().filter { it.selectFirst("img") != null }.mapNotNull { link ->
            val href = link.attr("href")
            val title = link.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Bez koncoveho lomitka web posle 301 na "http://..." (ne https) - Android to
        // spravne odmitne jako cleartext (viz network_security_config.xml) a appka pak
        // tise skonci na prazdnem seznamu. Overeno logem site pripojeni na realnem
        // telefonu (stejna pricina jako u HiveToonsSource - oba bezi na Astro). S
        // lomitkem uz web odpovi rovnou 200, zadne presmerovani.
        try {
            if (filter.genres.isNotEmpty() || filter.status != null || filter.comicTypes.isNotEmpty()) {
                return@withContext parseQueryList(get(queryUrl(page, filter)))
            }
            parseList(get("$base/series/?page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            getPopular(page, filter).filter { it.title.contains(query, ignoreCase = true) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get(resolveSourceUrl(base, manga.url))
            val doc = Jsoup.parse(html)
            val artist = Regex("""&quot;artist&quot;:\[0,&quot;([^&]*)&quot;]""").find(html)?.groupValues?.get(1)
            manga.copy(
                title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("img[itemprop=image]")?.attr("src")?.takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = doc.selectFirst("[itemprop=description]")?.text()?.trim(),
                genres = doc.select("[itemprop=genre]").map { it.text().trim() }.filter { it.isNotBlank() },
                author = artist?.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            // Zalozka Kapitoly je server-renderovana cela na ?tab=chapters (vychozi
            // detailni stranka ukazuje jen prvni + posledni davku). Cislo kapitoly se
            // cte z href "/series/{slug}/chapter-{num}", nazev neni - jen "Chapter N".
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url) + "?tab=chapters"))
            doc.select("a[href*=/chapter-]").mapNotNull { a ->
                val href = a.attr("href")
                val num = Regex("""chapter-([0-9.]+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                val label = if (num == num.toLong().toFloat()) num.toLong().toString() else num.toString()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href,
                    name = "Chapter $label", chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            // Stranky jsou <img> v adresari kapitoly na storage: upload/series/{slug}/
            // {chapterDir}/{soubor} - soubor je bud "page-0001_x.webp" nebo jen
            // "01.webp" (format se lisi podle serie, proto basename zacinajici
            // cislici nebo "page"). Vynechavat featured/chapter-featured/cp.webp.
            // Poradi = poradi v DOM (reader je rendruje v poradi cteni).
            val slug = chapter.url.split("/").getOrNull(2) ?: ""
            val prefix = "/upload/series/$slug/"
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[src]").map { it.attr("src") }
                .filter { it.contains(prefix) && !it.contains("featured") }
                .filter {
                    val base2 = it.substringAfterLast('/')
                    base2.firstOrNull()?.isDigit() == true || base2.startsWith("page")
                }
                .mapIndexed { i, url -> Page(i, url, url) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

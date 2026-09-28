package com.haise.jiyu.source.mangaworld

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MangaWorld (IT) - domena se v roce 2026 zmenila z mangaworld.ac na
 * mangaworld.mx (stejny vlastni Laravel-ovy frontend jako driv, ne Madara -
 * puvodni MadaraSource zaznam proto nikdy nefungoval spravne, jen nahodou
 * vracel 200 s neprazdnym HTML).
 *
 * Poznamka ke ctecce (getPageList): stranky kapitoly nejsou vsechny na
 * jedne URL (server renderuje vzdy jen aktualni stranku, dalsi se meni pres
 * JS select). Misto natahovani kazde stranky zvlast se z <select class=page>
 * zjisti celkovy pocet stranek a URL obrazku 2..N se odvodi ze vzoru prvni
 * stranky (".../1.jpg" -> ".../2.jpg" atd.) - stejne cislovani pouziva i
 * CDN. Riziko: pokud by nektera stranka mela jinou priponu nez prvni, jeji
 * URL by nebyla spravna.
 */
@Singleton
class MangaWorldSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangaworld"
    override val name = "MangaWorld (IT)"
    override val contentType: String get() = "MANGA"
    override val language = "it"
    override val homepageUrl get() = base
    private val base = "https://www.mangaworld.mx"

    private fun get(url: String): Document {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        val html = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return Jsoup.parse(html)
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.entry").mapNotNull { el ->
            val link = el.selectFirst("a.manga-title") ?: return@mapNotNull null
            val title = link.attr("title").trim().ifBlank { link.text().trim() }.ifBlank { return@mapNotNull null }
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("a.thumb img")?.attr("src")?.let { absoluteMediaUrl(base, it) }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }

    // <select> "Stato" na /archive (overeno zive): ongoing (In corso),
    // completed (Finito), dropped, paused (In pausa = hiatus), canceled.
    private fun statusParam(status: String?): String = when (status) {
        "ongoing" -> "ongoing"
        "completed" -> "completed"
        "hiatus" -> "paused"
        else -> ""
    }

    // /archive?type= filtruje server-side (overeno zive: manga/manhwa/manhua/
    // oneshot/doujinshi vraceji ruzne sady a kombinuji se statusem).
    override val availableComicTypes: List<FilterTag> get() = listOf(
        FilterTag(id = "manga", label = "Manga"),
        FilterTag(id = "manhwa", label = "Manhwa"),
        FilterTag(id = "manhua", label = "Manhua"),
        FilterTag(id = "oneshot", label = "One-shot"),
        FilterTag(id = "doujinshi", label = "Doujinshi"),
    )
    private val siteTypes = setOf("manga", "manhwa", "manhua", "oneshot", "doujinshi")

    private fun archiveUrl(page: Int, filter: MangaFilter, sort: String? = null, genre: String? = null, keyword: String? = null): String = buildString {
        append("$base/archive?page=$page")
        sort?.let { append("&sort=$it") }
        genre?.let { append("&genre=${URLEncoder.encode(it, "UTF-8")}") }
        keyword?.let { append("&keyword=${URLEncoder.encode(it, "UTF-8")}") }
        statusParam(filter.status).takeIf { it.isNotEmpty() }?.let { append("&status=$it") }
        filter.comicTypes.firstOrNull()?.takeIf { it in siteTypes }?.let { append("&type=$it") }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get(
                if (filter.genres.isNotEmpty()) archiveUrl(page, filter, genre = filter.genres.first())
                else archiveUrl(page, filter, sort = if (filter.sortBy == "latest") "newest" else "most_read")
            ))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get(
                if (filter.genres.isNotEmpty()) archiveUrl(page, filter, genre = filter.genres.first())
                else archiveUrl(page, filter, keyword = query)
            ))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // ─── Filtrování podle žánru ──────────────────────────────────────────────
    // /archive stranka ma postranni panel s odkazy na jednotlive zanry ve tvaru
    // archive?genre={slug} (italske slugy) - overeno zive (page 1 vs 2 pro
    // "azione" i ruzne zanry "azione" vs "commedia" vraci prokazatelne odlisne
    // tituly).

    override val supportsTagFilter: Boolean get() = true

    // /archive?status= aplikuje filtr server-side (overeno zive).
    override val supportsStatusFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = get("$base/archive")
            val tags = doc.select("a[href*=\"archive?genre=\"]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("genre=").substringBefore("&").trim().ifBlank { null }
                    ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            manga.copy(
                title = doc.selectFirst("h1.name")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("div.thumb img")?.attr("src")?.let { absoluteMediaUrl(base, it) } ?: manga.coverUrl,
                description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() },
                status = doc.selectFirst("a[href*=\"archive?status=\"]")?.text()?.trim(),
                author = doc.selectFirst("a[href*=\"archive?author=\"]")?.text()?.trim(),
                genres = doc.select("a[href*=\"archive?genre=\"]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            doc.select("a.chap").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.selectFirst("span")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
                val num = Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val dateText = a.selectFirst("i.chap-date")?.text()?.trim()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name.ifBlank { "Capitolo $num" },
                    chapterNumber = num,
                    dateUpload = parseItalianDate(dateText),
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseItalianDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.ITALIAN).parse(text)?.time ?: 0L
        } catch (e: Exception) { e.rethrowIfControl(); 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = get(chapter.url)
            val firstSrc = doc.selectFirst("img.img-fluid")?.attr("src") ?: return@withContext emptyList()
            val dir = firstSrc.substringBeforeLast('/')
            val ext = firstSrc.substringAfterLast('.')
            val total = doc.select("select.page option").mapNotNull {
                it.text().substringAfter('/', "").toIntOrNull()
            }.maxOrNull() ?: 1
            (1..total).map { n ->
                val url = "$dir/$n.$ext"
                Page(n - 1, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

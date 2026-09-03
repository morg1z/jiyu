package com.haise.jiyu.source.mangaworld

import com.haise.jiyu.source.bodyOrThrow

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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        val html = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return Jsoup.parse(html)
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("div.entry").mapNotNull { el ->
            val link = el.selectFirst("a.manga-title") ?: return@mapNotNull null
            val title = link.attr("title").trim().ifBlank { link.text().trim() }.ifBlank { return@mapNotNull null }
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("a.thumb img")?.attr("src")?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val sort = if (filter.sortBy == "latest") "newest" else "most_read"
        try { parseList(get("$base/archive?sort=$sort&page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/archive?keyword=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            manga.copy(
                title = doc.selectFirst("h1.name")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("div.thumb img")?.attr("src")?.takeIf { it.startsWith("http") } ?: manga.coverUrl,
                description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() },
                status = doc.selectFirst("a[href*=\"archive?status=\"]")?.text()?.trim(),
                author = doc.selectFirst("a[href*=\"archive?author=\"]")?.text()?.trim(),
                genres = doc.select("a[href*=\"archive?genre=\"]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
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
        } catch (_: Exception) { emptyList() }
    }

    private fun parseItalianDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.ITALIAN).parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
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
        } catch (_: Exception) { emptyList() }
    }
}

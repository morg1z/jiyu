package com.haise.jiyu.source.orionscans

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * orion-scans.com - bespoke Next.js aplikace, ale NENI klientsky renderovana SPA (na
 * rozdil od nekolika jinych kandidatu ve stejne davce, napr. infinityscans.net) - vypis
 * i detail jsou plne server-rendered HTML s realnym obsahem (overeno zive, ne jen
 * skriptove tagy). Vypis "/series?page=N" ma funkcni strankovani (overeno zive - strana
 * 1 a 2 vraci 20 zcela odlisnych titulu, 0 prekryvu), ale "?search=..." parametr tise
 * ignoruje dotaz (overeno zive - vraci identickou vychozi sadu jako bez parametru),
 * takze hledani je reseno lokalne nad par prvnimi strankami vypisu, stejny vzor jako u
 * jinych zdroju bez funkcniho hledani. Zadny zanrovy filtr na webu nenalezen.
 *
 * Detail mangy pouziva "Label"/"Hodnota" dvojice jako sourozenci - `h1` s presnym
 * textem (Status/Author/Type/...) a hned vedle `div` s hodnotou (overeno zive).
 * Seznam kapitol je primo v detailu (zadna dalsi strankovana kapitola nebyla
 * nalezena mimo tenhle seznam - overeno zive, ze neexistujici cisla kapitol vraci
 * 404). Stranky kapitoly jsou primo `img` se skutecnou `src` (CDN storage.orion-scans.com),
 * rozeznatelne od uvodniho "Chapter Cover" nahledu podle `alt` atributu obsahujiciho
 * "Page".
 */
@Singleton
class OrionScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "orionscans"
    override val name = "Orion Scans"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://orion-scans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=/series/][title]").mapNotNull { link ->
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = link.attr("title").trim().ifBlank { return@mapNotNull null }
            val cover = link.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get("$base/series?page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Server-side "?search=" parametr nefunguje (viz komentar u tridy) - hledani proto
    // stahne prvnich par stranek vypisu a filtruje podle titulku lokalne, stejny vzor
    // jako u jinych zdroju bez funkcniho hledani.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            val q = query.trim()
            (1..5).flatMap { p ->
                try { parseList(get("$base/series?page=$p")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
            }.distinctBy { it.url }.filter { it.title.contains(q, ignoreCase = true) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun statValue(doc: Document, label: String): String? =
        doc.select("h1").firstOrNull { it.text().trim().equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
                    ?: manga.title,
                author = statValue(doc, "Author"),
                artist = statValue(doc, "Artist"),
                status = statValue(doc, "Status")?.lowercase(),
                contentType = normalizeContentType(statValue(doc, "Type") ?: manga.contentType),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("a[href^=${manga.url}/chapter-]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = Regex("""chapter-([\d.]+)$""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = "Chapter $num", chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[alt*=Page]").mapIndexedNotNull { i, img ->
                val src = img.attr("src").trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

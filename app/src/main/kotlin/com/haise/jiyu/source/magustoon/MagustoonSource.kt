package com.haise.jiyu.source.magustoon

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * magustoon.org - Astro (server-rendered) web, NE Madara/MangaThemesia. Vypis "/series"
 * je staticky HTML s kartami `a[href^="/series/"]` (overeno zive - "?page=N" strankovani
 * funguje, strana 1 vs 2 odlisne). Zanrovy "?genre=..." i fulltextovy "?q=..."/"?search=..."
 * parametr appka na serveru TICHA IGNORUJE (overeno zive - identicka sada vysledku pro
 * ruzne hodnoty), takze hledani je reseno lokalne nad par prvnimi strankami vypisu, stejny
 * vzor jako u jinych zdroju bez funkcniho hledani (napr. Manhwa210).
 *
 * Detail mangy pouziva schema.org microdata (`itemprop="name"/"description"/"image"/
 * "genre"`), zadne genre-slug odkazy (jen prosty text - proto zadny tag filtr). Seznam
 * kapitol `a[href^="{mangaPath}/chapter-"]`, cislo kapitoly primo z URL. Nektere nejnovejsi
 * kapitoly jsou zamknute za mincemi ("/purchase" flow) - takove maji misto obrazku jen
 * placeholder/nic v `img[data-reader-page-image]`, getPageList pak vrati prazdny seznam.
 *
 * Stranky kapitoly jsou primo `img[data-reader-page-image]` se skutecnou `src` (Astro
 * static storage CDN, zadny lazy-load trik), overeno zive.
 */
@Singleton
class MagustoonSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "magustoon"
    override val name = "Magustoon"
    override val supportsSortOrder: Boolean get() = false
    override val contentType = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://magustoon.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("main a[href^=\"/series/\"]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            if (href.count { it == '/' } != 2) return@mapNotNull null
            val cover = a.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            val title = a.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: href.removePrefix("/series/").replace('-', ' ')
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/series?page=$page")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Server-side "?q="/"?search=" parametr nefunguje (viz komentar u tridy) - hledani
    // proto stahne prvnich par stranek vypisu a filtruje podle titulku lokalne.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            val q = query.trim()
            (1..3).flatMap { p ->
                try { parseList(get("$base/series?page=$p")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
            }.distinctBy { it.url }.filter { it.title.contains(q, ignoreCase = true) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                title = doc.selectFirst("[itemprop=name]")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("[itemprop=image]")?.attr("src")?.trim()?.ifBlank { null } ?: manga.coverUrl,
                description = doc.selectFirst("[itemprop=description]")?.text()?.trim()?.ifBlank { null },
                genres = doc.select("[itemprop=genre]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("a[href^=\"${manga.url}/chapter-\"]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = Regex("""chapter-([\d.]+)$""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                val name = a.selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null } ?: "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img[data-reader-page-image]").mapIndexedNotNull { i, img ->
                val src = img.attr("src").trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

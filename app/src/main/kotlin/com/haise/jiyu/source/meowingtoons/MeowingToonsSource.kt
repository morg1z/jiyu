package com.haise.jiyu.source.meowingtoons

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

/**
 * Genericky zdroj pro sdilenou komercni sablonu pouzivajici CDN "cdn.meowing.org" -
 * potvrzeno zive na Timeless Toons (timelesstoons.org) i Genz Toons (genztoons.org),
 * identicka struktura na obou.
 *
 * Cely katalog je vzdy na jedne strance ("/library/", zadne strankovani - overeno
 * zive) - appka ho stahne cely a hleda v nem sama, protoze "vyhledavaci" formular na
 * webu (`/series?q=...`) ve skutecnosti nefiltruje (vraci porad stejny plny seznam,
 * overeno zive s nesmyslnym dotazem).
 *
 * Stranky kapitoly jsou lazy-load `<img uid="...">` placeholdery - skutecna URL se
 * sklada klientskym JS jako `https://cdn.meowing.org/uploads/{uid}` (stejna domena
 * pro oba weby), appka to jen replikuje bez nutnosti JS.
 */
class MeowingToonsSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
) : MangaSource {
    override val homepageUrl get() = baseUrl
    private val root get() = baseUrl.trimEnd('/')

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private val coverStyleRegex = Regex("""url\(([^)]+)\)""")

    private fun parseLibrary(html: String): List<SManga> {
        val doc = Jsoup.parse(html, root)
        return doc.select("a[href^=/series/]").mapNotNull { card ->
            val href = card.absUrl("href").ifBlank { return@mapNotNull null }
            if (!href.matches(Regex(""".*/series/[^/?]+/?$"""))) return@mapNotNull null
            val title = card.attr("title").ifBlank { card.attr("alt") }.trim().ifBlank { return@mapNotNull null }
            val styleHost = card.selectFirst("[style*=background-image]")
            val cover = styleHost?.attr("style")?.let { coverStyleRegex.find(it)?.groupValues?.get(1) }
                ?.trim('\'', '"', ' ')?.ifBlank { null }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            parseLibrary(get("$root/library/"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            parseLibrary(get("$root/library/")).filter { it.title.contains(query, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            val description = doc.selectFirst("#expand_content p")?.text()?.trim()?.ifBlank { null }
            val genres = doc.select("a[href*=\"?genre=\"]").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = description,
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            doc.select("a[href^=/chapter/]").mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                val label = a.attr("title").ifBlank { a.attr("alt") }.trim()
                val num = Regex("""[\d.]+""").find(label)?.value?.toFloatOrNull() ?: return@mapNotNull null
                val name = label.ifBlank { "Chapter $num" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = System.currentTimeMillis())
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url), chapter.url)
            doc.select("img[uid]").mapIndexedNotNull { i, img ->
                val uid = img.attr("uid").trim().ifBlank { return@mapIndexedNotNull null }
                val url = "https://cdn.meowing.org/uploads/$uid"
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

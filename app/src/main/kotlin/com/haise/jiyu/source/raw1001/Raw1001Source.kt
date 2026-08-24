package com.haise.jiyu.source.raw1001

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
    override val homepageUrl get() = base
    private val base = "https://raw1001.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/all-manga/$page"))
            val mangaHref = Regex("""^https://raw1001\.net/manga/[a-zA-Z0-9-]+$""")
            doc.select("a:has(img)").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                if (!mangaHref.matches(href)) return@mapNotNull null
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
                // Bug fix - cover URL je na webu relativni cesta ("/uploads/..."), ne
                // absolutni URL - "startsWith(http)" test vsechno vyfiltroval, coverUrl
                // vzdy vyslo null (nahlaseno jako "covery se nenacitaji").
                val rawCover = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                val cover = when {
                    rawCover.startsWith("http") -> rawCover
                    rawCover.startsWith("/") -> "$base$rawCover"
                    else -> null
                }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = emptyList()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(genres = doc.select("a[href*=/genres/]").map { it.text().trim() }.filter { it.isNotBlank() })
        } catch (_: Exception) { manga }
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
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$base/ajax/image/list/chap/${chapter.url}"))
            if (!json.optBoolean("status")) return@withContext emptyList()
            val fragmentHtml = json.optString("html")
            val doc = Jsoup.parse(fragmentHtml)
            doc.select("a.readImg").mapIndexedNotNull { i, a ->
                val url = a.attr("href").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

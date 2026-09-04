package com.haise.jiyu.source.silentquill

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KDT Scans (silentquill.net) - stejna Mangathemesia/"Mangastream" sablona
 * jako [com.haise.jiyu.source.rokaricomics.RokariComicsSource] a
 * [com.haise.jiyu.source.galaxymanga.GalaxyMangaSource] (`.bsx`/`.tt`/
 * `div.eplister`/`ts_reader.run({...images...})`), ale detail strance chybi
 * status/description v jakekoliv staticky parsovatelne podobe - genres jsou
 * jediny extra field, co jde spolehlive precist.
 */
@Singleton
class KDTScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "kdtscans"
    override val name = "KDT Scans"
    override val homepageUrl get() = base
    private val base = "https://www.silentquill.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".bsx").mapNotNull { el ->
            val link = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { el.selectFirst(".tt")?.text().orEmpty() }
                .trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = link.attr("href"), title = title, coverUrl = cover, contentType = "MANGA")
        }
    }

    // "/manga/" ma sidebar s genre[] checkboxy (standardni MangaThemesia "genrez" panel,
    // ciselne id) - stejny endpoint prijime "?genre[]=<id>" jako extra filtr, overeno zive
    // (uplne odlisne tituly na strance 1 pro genre 8 "Action" vs bez filtru).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/manga/"))
            val tags = doc.select("input.genre-item[name=genre[]]").mapNotNull { input ->
                val value = input.attr("value").ifBlank { null } ?: return@mapNotNull null
                val label = doc.selectFirst("label[for=${input.attr("id")}]")?.text()?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null
                FilterTag(id = value, label = label)
            }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun archiveUrl(page: Int, order: String, genreId: String?): String {
        val url = "$base/manga/?page=$page&order=$order"
        return if (genreId != null) "$url&genre%5B%5D=$genreId" else url
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val order = if (filter.sortBy == "latest") "update" else "popular"
        try { parseList(get(archiveUrl(page, order, filter.genres.firstOrNull()))) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(archiveUrl(page, "popular", filter.genres.first())))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
            parseList(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                genres = doc.select("a[href*=/genres/]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("div.eplister li a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.selectFirst("span.chapternum")?.text()?.trim()
                    ?: a.text().trim().ifBlank { "Chapter ${i + 1}" }
                val num = Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            val raw = Regex(""""images":\[(.*?)\]""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            Regex(""""([^"]+)"""").findAll(raw)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.isNotBlank() }
                .mapIndexed { i, url -> Page(i, url, url) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

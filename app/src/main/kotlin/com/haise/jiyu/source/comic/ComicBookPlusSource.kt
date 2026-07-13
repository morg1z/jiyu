package com.haise.jiyu.source.comic

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ComicBookPlus - legální archiv Golden Age (public domain) komiksů.
 * Web běží na vlastním fóru (SMF-like), ne na Madara/WordPressu - proto
 * vlastní parsování dle `?dlid=` id komiksu a jeho `viewer/<hash>/<n>.jpg`
 * stránek (číslováno od 0, počet stran v itemprop="numberOfPages").
 */
@Singleton
class ComicBookPlusSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "comicbookplus"
    override val name = "ComicBookPlus"
    override val contentType = "COMIC"
    private val base = "https://comicbookplus.com"

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("div.cbpLline").mapNotNull { row ->
            val link = row.selectFirst("a[itemprop=name]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val dlid = Regex("""dlid=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            SManga(
                sourceId = id,
                url = "/?dlid=$dlid",
                title = link.text().trim(),
                coverUrl = row.selectFirst("img")?.attr("src"),
                contentType = "COMIC",
            )
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/?cbplus=latestuploads_l_s_${(page - 1).coerceAtLeast(0)}"))
            parseListing(doc)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // ComicBookPlus nemá vlastní fulltext endpoint (jen Google CSE),
            // takže hledáme napříč prvními stránkami "latest uploads".
            val results = mutableListOf<SManga>()
            for (p in 0 until 5) {
                val doc = Jsoup.parse(get("$base/?cbplus=latestuploads_l_s_$p"))
                val items = parseListing(doc)
                if (items.isEmpty()) break
                results += items.filter { it.title.contains(query, ignoreCase = true) }
            }
            results
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                description = doc.selectFirst("meta[itemprop=description]")?.attr("content"),
                genres = doc.selectFirst("meta[itemprop=genre]")?.attr("content")
                    ?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?.let { listOf(it) } ?: emptyList(),
                status = "Complete",
                contentType = "COMIC",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = "Read",
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val pageCount = doc.selectFirst("span[itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull()
                ?: return@withContext emptyList()
            val thumbUrl = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content") ?: return@withContext emptyList()
            val dir = thumbUrl.substringBeforeLast('/')
            (0 until pageCount).map { i -> Page(i, "$dir/$i.jpg") }
        } catch (_: Exception) { emptyList() }
    }
}

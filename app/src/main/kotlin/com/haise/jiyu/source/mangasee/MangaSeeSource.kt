package com.haise.jiyu.source.mangasee

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSeeSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangasee"
    override val name = "MangaSee"

    private val base = "https://mangasee123.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun extractMangaDirectory(html: String): JSONArray? {
        val match = Regex("""vm\.Directory\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(html)
        return match?.groupValues?.get(1)?.let { runCatching { JSONArray(it) }.getOrNull() }
    }

    private fun dirEntryToSManga(obj: JSONObject): SManga {
        val slug = obj.optString("i", "")
        return SManga(
            sourceId = id,
            url = "/manga/$slug",
            title = obj.optString("s", slug),
            coverUrl = "https://cover.nep.li/cover/$slug.jpg",
        )
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base/search/?sort=v&desc=true")
            val dir = extractMangaDirectory(html) ?: return@withContext emptyList()
            val pageSize = 24
            val start = (page - 1) * pageSize
            (start until minOf(start + pageSize, dir.length())).map { i ->
                dirEntryToSManga(dir.getJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.lowercase(), "UTF-8")
            val html = get("$base/search/?name=$q")
            val dir = extractMangaDirectory(html) ?: return@withContext emptyList()
            val qLower = query.lowercase()
            val filtered = (0 until dir.length())
                .map { dir.getJSONObject(it) }
                .filter { it.optString("s", "").lowercase().contains(qLower) }
            filtered.take(24).map { dirEntryToSManga(it) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val title = doc.selectFirst("h1.SeriesName")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".img-fluid[src*='temp.comick'], .img-responsive[src]")?.attr("src") ?: manga.coverUrl
            val desc = doc.selectFirst(".description-set p")?.text()?.trim()
            val genres = doc.select(".list-inline li a[href*='genre']").map { it.text().trim() }.filter { it.isNotBlank() }
            val author = doc.selectFirst(".list-group-item:contains(Author) a")?.text()?.trim()
            manga.copy(title = title, coverUrl = cover, description = desc, genres = genres, author = author)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${manga.url}")
            val match = Regex("""vm\.Chapters\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(html)
            val arr = match?.groupValues?.get(1)?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?: return@withContext emptyList()
            val slug = manga.url.substringAfterLast("/")
            (0 until arr.length()).map { i ->
                val ch = arr.getJSONObject(i)
                val chNum = ch.optString("Chapter", "1")
                val num = (chNum.toIntOrNull() ?: 1).toFloat() / 10f
                val chSlug = chapterToPath(chNum)
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/read-online/$slug-chapter-$chSlug.html",
                    name = "Chapter $num",
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    private fun chapterToPath(chNum: String): String {
        val n = chNum.toIntOrNull() ?: return chNum
        val whole = n / 10
        val frac = n % 10
        return if (frac == 0) "$whole" else "$whole.$frac"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val chMatch = Regex("""vm\.CurChapter\s*=\s*(\{.*?});""", RegexOption.DOT_MATCHES_ALL).find(html)
            val chJson = chMatch?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@withContext emptyList()
            val dirMatch = Regex("""vm\.CurPathName\s*=\s*"([^"]+)"""").find(html)
            val dirPath = dirMatch?.groupValues?.get(1) ?: return@withContext emptyList()
            val chNum = chJson.optString("Chapter", "1")
            val pageCount = chJson.optInt("Page", 0)
            val mangaSlug = chapter.mangaUrl.substringAfterLast("/")
            val chFormatted = chNum.padStart(4, '0').let {
                it.substring(0, it.length - 1) + "." + it.last()
            }.trimStart('0').ifEmpty { "0" }
            (1..pageCount).map { i ->
                val page = i.toString().padStart(3, '0')
                val url = "https://$dirPath/manga/$mangaSlug/$chFormatted-$page.png"
                Page(i - 1, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.comizy

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
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comizy (comizy.io) - nastupce MangaBuddy (mangabuddy.com), ktery mezitim
 * kompletne prepsali na Next.js. Misto HTML selektoru se parsuje JSON ze
 * `<script id="__NEXT_DATA__">` - je stabilnejsi nez CSS selektory a obsahuje
 * uplna strukturovana data (nazev/popis/zanry/kapitoly/obrazky stranek).
 *
 * Zname omezeni: `initialManga.chapters` na detailu titulu vraci jen ~50
 * nejnovejsich kapitol (stranka nema server-rendered plnou historii) - u
 * dlouhych serialu tak nemusi jit dohledat uplne prvni kapitoly.
 */
@Singleton
class ComizySource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "comizy"
    override val name = "Comizy"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    override val supportsChapterComments: Boolean get() = true
    private val base = "https://comizy.io"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun nextData(html: String): JSONObject? {
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        return try { JSONObject(json) } catch (_: Exception) { null }
    }

    private fun pageProps(root: JSONObject): JSONObject =
        root.getJSONObject("props").getJSONObject("pageProps")

    private fun itemToManga(o: JSONObject): SManga = SManga(
        sourceId = id,
        url = base + o.optString("url"),
        title = o.optString("name"),
        coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
        contentType = "MANHWA",
    )

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get("$base/latest?page=$page")) ?: return@withContext emptyList())
            val items = props.getJSONArray("items")
            (0 until items.length()).map { itemToManga(items.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val props = pageProps(nextData(get("$base/search?q=$q&page=$page")) ?: return@withContext emptyList())
            val items = props.getJSONArray("ssrItems")
            (0 until items.length()).map { itemToManga(items.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get(manga.url)) ?: return@withContext manga)
            val im = props.getJSONObject("initialManga")
            val genresArr = im.optJSONArray("genres")
            val genres = genresArr?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name").takeIf(String::isNotBlank) }
            } ?: emptyList()
            manga.copy(
                title = im.optString("name", manga.title),
                coverUrl = im.optString("cover").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = im.optString("summary").takeIf { it.isNotBlank() },
                status = im.optString("status").takeIf { it.isNotBlank() },
                genres = genres,
                contentType = "MANHWA",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get(manga.url)) ?: return@withContext emptyList())
            val chapters = props.getJSONObject("initialManga").getJSONArray("chapters")
            (0 until chapters.length()).map { i ->
                val c = chapters.getJSONObject(i)
                val number = c.optDouble("number", 0.0)
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = base + c.optString("url"),
                    name = c.optString("name").ifBlank { "Chapter $number" },
                    chapterNumber = number.toFloat(),
                    dateUpload = parseIsoDate(c.optString("updatedAt")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseIsoDate(text: String): Long = try {
        Instant.parse(text).toEpochMilli()
    } catch (_: Exception) { 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val props = pageProps(nextData(get(chapter.url)) ?: return@withContext emptyList())
            val images = props.getJSONObject("initialChapter").getJSONArray("images")
            (0 until images.length()).map { i -> Page(i, images.getString(i), images.getString(i)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            try {
                val ic = pageProps(nextData(get(chapter.url)) ?: return@withContext emptyList())
                    .getJSONObject("initialChapter")
                com.haise.jiyu.source.comments.parseMangaReaderJsonComments(ic)
            } catch (_: Exception) { emptyList() }
        }
}

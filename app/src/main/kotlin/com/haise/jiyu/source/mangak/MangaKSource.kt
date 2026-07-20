package com.haise.jiyu.source.mangak

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangak.io je Next.js aplikace, ktera na kazde strance (listing, detail,
 * cetba) embeduje kompletni server-rendered props do `<script
 * id="__NEXT_DATA__">` jako JSON - misto scrapovani HTML se proto parsuje
 * primo tenhle blok (props.pageProps.{initialItems|ssrItems|initialManga|
 * initialChapter}). Poznamka: `initialManga.chapters` je u velmi dlouhych
 * serii (50+ kapitol) oriznuty na poslednich 50 - skutecny endpoint pro
 * dotazeni starsich kapitol se nepodarilo najit (neni v zadnem staticky
 * nactenem JS chunku), takze u takovych serii chybi nejstarsi kapitoly.
 */
@Singleton
class MangaKSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangak"
    override val name = "MangaK"
    override val homepageUrl get() = base
    private val base = "https://mangak.io"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun pageProps(html: String): JSONObject {
        val raw = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return JSONObject()
        return JSONObject(raw).optJSONObject("props")?.optJSONObject("pageProps") ?: JSONObject()
    }

    private fun itemToSManga(o: JSONObject) = SManga(
        sourceId = id,
        url = o.optString("url"),
        title = o.optString("name"),
        coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
    )

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val items = pageProps(get("$base/ranking?page=$page")).optJSONArray("initialItems") ?: return@withContext emptyList()
            (0 until items.length()).map { itemToSManga(items.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val items = pageProps(get("$base/search?q=$q&page=$page")).optJSONArray("ssrItems") ?: return@withContext emptyList()
            (0 until items.length()).map { itemToSManga(items.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val m = pageProps(get("$base${manga.url}")).optJSONObject("initialManga") ?: return@withContext manga
            val genres = m.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val author = m.optJSONArray("authors")?.let { arr ->
                if (arr.length() > 0) arr.getJSONObject(0).optString("name").takeIf { it.isNotBlank() } else null
            }
            manga.copy(
                title = m.optString("name").takeIf { it.isNotBlank() } ?: manga.title,
                coverUrl = m.optString("cover").takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = m.optString("summary").takeIf { it.isNotBlank() },
                genres = genres,
                author = author,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = pageProps(get("$base${manga.url}")).optJSONObject("initialManga")?.optJSONArray("chapters")
                ?: return@withContext emptyList()
            (0 until chapters.length()).map { i ->
                val c = chapters.getJSONObject(i)
                val chapName = c.optString("name")
                val num = Regex("""(\d+(?:\.\d+)?)""").find(chapName)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (chapters.length() - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = c.optString("url"),
                    name = chapName.ifBlank { "Chapter $num" }, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val images = pageProps(get("$base${chapter.url}")).optJSONObject("initialChapter")?.optJSONArray("images")
                ?: return@withContext emptyList()
            (0 until images.length()).map { i -> val u = images.getString(i); Page(i, u, u) }
        } catch (_: Exception) { emptyList() }
    }
}

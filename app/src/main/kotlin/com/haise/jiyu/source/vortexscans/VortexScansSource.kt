package com.haise.jiyu.source.vortexscans

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
 * vortexscans.org bezi na Astro s hydratovanymi "islands" (stejna sablona
 * jako u nekolika dalsich scan skupin) - listing/detail je server-rendered
 * HTML se schema.org microdata, ale plny seznam kapitol se na detailni
 * strance NEnacte cely (jen posledni davka), protoze si ho stahuje az
 * klientsky island z api.vortexscans.org. Skutecny endpoint (bez potreby
 * prihlaseni) byl zjisten z bundlovaneho JS te komponenty:
 *   GET https://api.vortexscans.org/api/chapters?postId={id}&skip=0&take=all&order=desc
 * postId je jen v embedovanych hydration-props na detailni strance (ne v URL),
 * proto se vytahuje regexem primo ze syroveho HTML. Zdrojova API pro
 * fulltextove vyhledavani (/api/posts?search=...) parametr search tise
 * ignoruje, proto se search resi filtrovanim vysledku getPopular.
 */
@Singleton
class VortexScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "vortexscans"
    override val name = "Vortex Scans"
    override val homepageUrl get() = base
    private val base = "https://vortexscans.org"
    private val apiBase = "https://api.vortexscans.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=/series/]").toList().filter { it.selectFirst("img") != null }.mapNotNull { link ->
            val href = link.attr("href")
            val title = link.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/series?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            getPopular(page, filter).filter { it.title.contains(query, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${manga.url}")
            val doc = Jsoup.parse(html)
            val artist = Regex("""&quot;artist&quot;:\[0,&quot;([^&]*)&quot;]""").find(html)?.groupValues?.get(1)
            manga.copy(
                title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("img[itemprop=image]")?.attr("src")?.takeIf { it.isNotBlank() } ?: manga.coverUrl,
                description = doc.selectFirst("[itemprop=description]")?.text()?.trim(),
                genres = doc.select("[itemprop=genre]").map { it.text().trim() }.filter { it.isNotBlank() },
                author = artist?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${manga.url}")
            val postId = Regex("""&quot;postId&quot;:\[0,(\d+)]""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val json = JSONObject(get("$apiBase/api/chapters?postId=$postId&skip=0&take=all&order=desc"))
            val chapters = json.optJSONObject("post")?.optJSONArray("chapters") ?: return@withContext emptyList()
            (0 until chapters.length()).map { i ->
                val c = chapters.getJSONObject(i)
                val num = c.optDouble("number", 0.0).toFloat()
                val slug = c.optString("slug")
                val name = c.optString("title").takeIf { it.isNotBlank() } ?: "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = "${manga.url}/$slug",
                    name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("figure meta[itemprop=image]").mapIndexedNotNull { i, meta ->
                val url = meta.attr("content").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

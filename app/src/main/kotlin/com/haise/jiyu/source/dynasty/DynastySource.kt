package com.haise.jiyu.source.dynasty

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

@Singleton
class DynastySource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "dynasty"
    override val name = "Dynasty Scans"
    override val homepageUrl get() = base

    private val base = "https://dynasty-scans.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    // series.json vraci strankovane vysledky, zabalene do "tags" pole objektu
    // s jedinym klicem "#" (ne skutecne razeni podle pismene) - viz odpoved
    // {"tags":[{"#":[{"name":...,"permalink":...}, ...]}, ...],"current_page":1,"total_pages":17}.
    // Cover uz v listingu neni, doplni se az v getMangaDetails.
    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$base/series.json?page=$page"))
            val groups = json.optJSONArray("tags") ?: return@withContext emptyList()
            val items = mutableListOf<SManga>()
            for (g in 0 until groups.length()) {
                val arr = groups.getJSONObject(g).optJSONArray("#") ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val slug = obj.optString("permalink", "")
                    items.add(
                        SManga(
                            sourceId = id,
                            url = "/series/$slug",
                            title = obj.optString("name", slug),
                            coverUrl = null,
                        )
                    )
                }
            }
            items
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = get("$base/search?q=$q&classes[]=Series")
            val doc = Jsoup.parse(html)
            doc.select(".chapter-list dd").mapNotNull { el ->
                val link = el.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = link.text().trim()
                val cover = el.selectFirst("img")?.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val title = doc.selectFirst("h2.tag-title b")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".thumbnail img, .cover img")?.let {
                val src = it.attr("src").ifBlank { it.attr("data-src") }
                if (src.startsWith("//")) "https:$src"
                else if (src.startsWith("/")) "$base$src"
                else src
            } ?: manga.coverUrl
            val desc = doc.selectFirst(".description")?.text()?.trim()
            val genres = doc.select(".tags a[href*='/tags/']").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(title = title, coverUrl = cover, description = desc, genres = genres)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select(".chapter-list dd a[href*='/chapters/']").mapIndexed { i, el ->
                val href = el.attr("href").removePrefix(base)
                val name = el.text().trim().ifBlank { "Chapter ${i + 1}" }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val match = Regex("""var\s+pages\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(html)
            val json = match?.groupValues?.get(1) ?: return@withContext emptyList()
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val url = obj.optString("image").takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "$base$it" else it }
                    ?: return@mapNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

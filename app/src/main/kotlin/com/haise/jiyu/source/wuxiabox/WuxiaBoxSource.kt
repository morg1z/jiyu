package com.haise.jiyu.source.wuxiabox

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wuxia Box (wuxiabox.com) - server-rendered custom CMS (EmpireCMS-based,
 * podle "/e/..." cest). Seznam kapitol se strankuje pres AJAX fragment
 * endpoint "/e/extend/fy.php?page=N&wjm={slug}", ktery vraci primo HTML
 * (ne JSON) - stejny <ul class="chapter-list"> jako na hlavni strance.
 */
@Singleton
class WuxiaBoxSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "wuxiabox"
    override val name = "Wuxia Box"
    override val contentType: String get() = "NOVEL"
    private val base = "https://www.wuxiabox.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("li.novel-item").mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("h4.novel-title")?.text()?.trim() ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$base$it" }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/updates/$page.html")) } catch (_: Exception) { emptyList() }
    }

    // Vyhledavani jde pres POST na EmpireCMS endpoint, ktery presmeruje
    // na vysledkovou stranku s vygenerovanym searchid.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("show", "title")
                .add("tempid", "1")
                .add("tbname", "news")
                .add("keyboard", query)
                .build()
            val request = Request.Builder().url("$base/e/search/index.php").post(body).build()
            val html = client.newCall(request).execute().use { it.body?.string() ?: "" }
            parseList(html)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val status = doc.select("div.header-stats span").firstOrNull {
                it.selectFirst("small")?.text()?.trim().equals("Status", ignoreCase = true)
            }?.selectFirst("strong")?.text()?.trim()

            manga.copy(
                title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("div.author span[itemprop=author]")?.text()?.trim(),
                genres = doc.select("div.categories a.property-item").map { it.text().trim() },
                description = doc.selectFirst("p.description")?.text()?.trim(),
                status = status,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = manga.url.substringAfterLast("/").removeSuffix(".html")
            val chapters = mutableListOf<SChapter>()
            var page = 0
            while (page < 300) {
                val html = get("$base/e/extend/fy.php?page=$page&wjm=$slug")
                val doc = Jsoup.parse(html, base)
                val rows = doc.select("ul.chapter-list li")
                if (rows.isEmpty()) break
                rows.forEach { li ->
                    val a = li.selectFirst("a") ?: return@forEach
                    val href = a.attr("href")
                    val num = li.attr("data-chapterno").toFloatOrNull() ?: 0f
                    val title = a.selectFirst("strong.chapter-title")?.text()?.trim()?.ifBlank { null }
                        ?: "Chapter $num"
                    val dateText = a.selectFirst("time.chapter-update")?.text()?.trim()
                    chapters.add(
                        SChapter(
                            sourceId = id,
                            mangaUrl = manga.url,
                            url = href,
                            name = title,
                            chapterNumber = num,
                            dateUpload = parseRelativeDate(dateText),
                        )
                    )
                }
                page++
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val m = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE).find(text)
            ?: return System.currentTimeMillis()
        val value = m.groupValues[1].toLongOrNull() ?: 1L
        val deltaMs = when (m.groupValues[2].lowercase()) {
            "second" -> value * 1_000L
            "minute" -> value * 60_000L
            "hour"   -> value * 3_600_000L
            "day"    -> value * 86_400_000L
            "week"   -> value * 7 * 86_400_000L
            "month"  -> value * 30 * 86_400_000L
            "year"   -> value * 365 * 86_400_000L
            else     -> 0L
        }
        return System.currentTimeMillis() - deltaMs
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val text = doc.select("div.chapter-content p").joinToString("\n\n") { it.text().trim() }
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

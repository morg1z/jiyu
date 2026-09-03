package com.haise.jiyu.source.mangageko

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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaGekoSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangageko"
    override val name = "MangaGeko"
    override val homepageUrl get() = base
    private val base = "https://www.mgeko.cc"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").trim().ifBlank {
            a.selectFirst(".novel-title")?.text()?.trim().orEmpty()
        }.takeIf { it.isNotBlank() } ?: return null
        val img = a.selectFirst("img")
        val cover = img?.attr("data-src")?.trim()?.takeIf { it.isNotBlank() } ?: img?.attr("src")?.trim()
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Web sam bez parametru vraci "Latest Updated Manga" (viz <title> stranky) -
            // teprve "hot=true" prepne na skutecne popularni/trending tituly (overeno
            // zive, oba vraceji zcela odlisne seznamy).
            val hotParam = if (filter.sortBy == "latest") "" else "&hot=true"
            val doc = Jsoup.parse(get("$base/jumbo/manga/?results=$page&filter=All$hotParam"))
            doc.select("a.list-body[href^=/manga/]").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search/?search=$q&page=$page"))
            doc.select("a.list-body[href^=/manga/]").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val genres = doc.select("div.categories a.property-item").map { it.text().trim() }
            val rawDescription = doc.selectFirst("p.description")?.text()?.trim()
            val description = rawDescription
                ?.substringAfter("The Summary is", rawDescription)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val contentType = when {
                genres.any { it.equals("Manhwa", ignoreCase = true) } -> "MANHWA"
                genres.any { it.equals("Manhua", ignoreCase = true) } -> "MANHUA"
                else -> "MANGA"
            }
            manga.copy(
                title = doc.selectFirst("h1.novel-title, h1")?.text()?.trim() ?: manga.title,
                description = description,
                author = doc.selectFirst("a.property-item span[itemprop=author]")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("Updating", ignoreCase = true) },
                status = doc.selectFirst("strong.ongoing, strong.completed")?.text()?.trim(),
                genres = genres.filterNot { it.equals("Manga", true) || it.equals("Manhwa", true) || it.equals("Manhua", true) },
                contentType = contentType,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("li.chapter-list-item a[href*=/reader/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val num = Regex("""chapter-([\d.]+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val name = if (num == num.toInt().toFloat()) "Chapter ${num.toInt()}" else "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img[src*=/sv2/comic/]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

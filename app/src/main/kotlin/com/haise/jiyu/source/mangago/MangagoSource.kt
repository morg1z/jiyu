package com.haise.jiyu.source.mangago

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangagoSource @Inject constructor(
    baseClient: OkHttpClient,
    cloudflareInterceptor: CloudflareInterceptor,
) : MangaSource {
    override val id = "mangago"
    override val name = "Mangago"
    override val homepageUrl get() = base
    private val base = "https://www.mangago.me"

    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(cloudflareInterceptor)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", CloudflareInterceptor.CHROME_UA)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/list/allmanga/page/$page/?o=views"))
            doc.select(".thumbnail-group li").mapNotNull { li ->
                val link = li.selectFirst("a") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = li.selectFirst(".g-title, .title, h3")?.text()?.trim()
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    coverUrl = li.selectFirst("img")?.let {
                        it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                    },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/r/search.php?name=$q&page=$page"))
            doc.select(".thumbnail-group li, .searchresult li").mapNotNull { li ->
                val link = li.selectFirst("a[href*='/read-manga/']") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = li.selectFirst(".g-title, h3, .title")?.text()?.trim()
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    coverUrl = li.selectFirst("img")?.attr("src"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst(".w-title h1, h1.title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".cover img, .w-cover img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("#content p, .manga-info p")?.text(),
                genres = doc.select(".tag-links a, .genre a").map { it.text() },
                author = doc.selectFirst(".table-ellipsis td:contains(Author) + td")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("#chapter_table tr td a, .chapter_list li a").mapIndexed { i, a ->
                SChapter(
                    sourceId = id, mangaUrl = manga.url,
                    url = a.attr("href").removePrefix(base),
                    name = a.text().trim().takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val jsMatch = Regex("""var\s+newImglist\s*=\s*\[([^\]]+)\]""").find(html)
                ?: Regex("""imgsrcs\s*=\s*\[([^\]]+)\]""").find(html)
            if (jsMatch != null) {
                val urls = jsMatch.groupValues[1]
                    .split(",")
                    .map { it.trim().trim('"', '\'') }
                    .filter { it.startsWith("http") }
                return@withContext urls.mapIndexed { i, url -> Page(i, url, url) }
            }
            Jsoup.parse(html).select(".pic_box img, #comicpic img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

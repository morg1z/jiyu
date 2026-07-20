package com.haise.jiyu.source.rawkuma

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

@Singleton
class RawKumaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "rawkuma"
    override val name     = "RawKuma (Raw)"
    override val language = "ja"
    override val homepageUrl get() = base
    private val base = "https://rawkuma.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".bsx, .page-item-detail, .manga").mapNotNull { el ->
            val link = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix(base)
            val title = (el.selectFirst(".tt, h3, h2, .post-title")?.text()
                ?: link.attr("title")
                ?: link.text()).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
            }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/manga/?m_orderby=views&page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?s=$q&post_type=wp-manga"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst("h1, .entry-title, .post-title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".thumbook img, .summary_image img")?.let {
                    it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
                } ?: manga.coverUrl,
                description = doc.selectFirst(".summary__content p, .entry-content p")?.text(),
                genres = doc.select(".genres-content a, .mgen a").map { it.text() },
                author = doc.selectFirst(".fmed:contains(Author) span, .author-content a")?.text(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val chapters = doc.select(".chbox a, .wp-manga-chapter a, #chapterlist li a")
            chapters.mapIndexed { i, a ->
                val href = a.attr("href").removePrefix(base)
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (chapters.size - i).toFloat()
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val doc = Jsoup.parse(html)
            val imgs = doc.select("#readerarea img, .reading-content img, .rdminimal img")
            if (imgs.isNotEmpty()) {
                imgs.mapIndexedNotNull { i, img ->
                    val url = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    Page(i, url, url)
                }
            } else {
                val match = Regex("""ts_reader\.run\(\{"sources":\[.*?"images":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(html)
                match?.groupValues?.get(1)
                    ?.split(",")
                    ?.map { it.trim().trim('"') }
                    ?.filter { it.isNotBlank() }
                    ?.mapIndexed { i, url -> Page(i, url, url) }
                    ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }
}

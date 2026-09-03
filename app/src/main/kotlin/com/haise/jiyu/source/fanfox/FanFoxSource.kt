package com.haise.jiyu.source.fanfox

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
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * fanfox.net (dříve mangafox.me) - dlouhodobě zavedený web s velkou knihovnou.
 * Katalog/detail/seznam kapitol je plně server-rendered, ale skutečné URL
 * obrázků kapitoly jsou schované za `chapterfun.ashx` endpointem, který vrací
 * JS obfuskovaný přes Dean Edwards "packer" - viz [JsPacker]. Každá stránka
 * kapitoly = samostatný chapterfun.ashx požadavek (odpovídá tomu, jak web sám
 * funguje - i lidský reader načítá každou stránku zvlášť), proto getPageList
 * jen zjistí počet stránek a getImageUrl dořeší konkrétní URL až při čtení.
 */
@Singleton
class FanFoxSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "fanfox"
    override val name = "FanFox"
    override val homepageUrl get() = base
    private val base = "https://fanfox.net"

    private fun get(url: String, referer: String = base): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", referer)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(titleLink: Element): SManga? {
        val href = titleLink.attr("href").ifBlank { return null }
        val title = titleLink.attr("title").trim().ifBlank { titleLink.text().trim() }.takeIf { it.isNotBlank() } ?: return null
        val coverAnchor = titleLink.closest("p")?.previousElementSibling()
        val cover = coverAnchor?.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Vychozi razeni bez parametru je uz samo o sobe "hot"/popularni (One Piece,
            // Onepunch-Man na prvnich mistech) - "?latest" pro Nejnovejsi overeno zive
            // (uplne jina sada titulu).
            val sortParam = if (filter.sortBy == "latest") "?latest" else ""
            val url = if (page <= 1) "$base/directory/$sortParam" else "$base/directory/$page.html$sortParam"
            val doc = Jsoup.parse(get(url))
            doc.select("p.manga-list-1-item-title > a[href]").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?title=$q&page=$page"))
            doc.select("p.manga-list-4-item-title > a[href]").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val genres = doc.select("p.detail-info-right-tag-list a").map { it.text().trim() }
            val authorLine = doc.selectFirst("p.detail-info-right-say")
            manga.copy(
                title = doc.selectFirst("span.detail-info-right-title-font")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("p.detail-info-right-content")?.text()?.trim()?.takeIf { it.isNotBlank() },
                author = authorLine?.select("a")?.joinToString(", ") { it.text().trim() }?.takeIf { it.isNotBlank() },
                status = doc.selectFirst("span.detail-info-right-title-tip")?.text()?.trim(),
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val dateFormat = SimpleDateFormat("MMM dd,yyyy", Locale.US)
            doc.select("div.detail-main-list-main").mapNotNull { info ->
                val a = info.parent() ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = info.selectFirst("p.title3")?.text()?.trim() ?: return@mapNotNull null
                val num = Regex("""c([\d.]+)/""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val dateText = info.selectFirst("p.title2")?.text()?.trim().orEmpty()
                val date = try { dateFormat.parse(dateText)?.time ?: 0L } catch (_: Exception) { 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = date)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val readerUrl = "$base${chapter.url}"
            val html = get(readerUrl)
            val count = Regex("""var\s+imagecount\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@withContext emptyList()
            val chapterId = Regex("""var\s+chapterid\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val chapterBase = readerUrl.substringBeforeLast("/")
            (1..count).map { p ->
                Page(index = p - 1, url = "$chapterBase/chapterfun.ashx?cid=$chapterId&page=$p&key=")
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val readerUrl = page.url.substringBefore("/chapterfun.ashx")
            val body = get(page.url, referer = "$readerUrl/")
            val decoded = JsPacker.unpackEval(body) ?: return@withContext page.url
            extractFirstImageUrl(decoded) ?: page.url
        } catch (_: Exception) { page.url }
    }

    private fun extractFirstImageUrl(decodedJs: String): String? {
        val basePath = Regex("""if\s*\(\s*i\s*==\s*0\s*\)\s*\{\s*pvalue\[i\]\s*=\s*"([^"]*)"""").find(decodedJs)?.groupValues?.get(1)
            ?: return null
        val firstEntry = Regex("""pvalue\s*=\s*\[\s*"([^"]*)"""").find(decodedJs)?.groupValues?.get(1) ?: return null
        val url = basePath + firstEntry
        return if (url.startsWith("//")) "https:$url" else url
    }
}

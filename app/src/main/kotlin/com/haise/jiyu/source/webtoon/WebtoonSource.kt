package com.haise.jiyu.source.webtoon

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
class WebtoonSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "webtoons"
    override val name = "Webtoon (LINE)"

    private val base = "https://www.webtoons.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .header("Cookie", "pagGDPR=true; needCCPA=false; needCOPPA=false; locale=en")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseCardList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".card_lst li, .challenge_lst li, .daily_lst li").mapNotNull { li ->
            val link = li.selectFirst("a[href*='webtoons.com']") ?: li.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = li.selectFirst(".subj, .info .subj, .cont .subj")?.text()?.trim()
                ?: return@mapNotNull null
            val cover = li.selectFirst("img")?.let {
                it.attr("data-url").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("data-src").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> s.isNotBlank() }
            }
            val url = if (href.startsWith("http")) href.removePrefix(base) else href
            SManga(sourceId = id, url = url, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseCardList(get("$base/en/genre/list?genreTab=ALL&sortOrder=READ_COUNT&page=$page")) }
        catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseCardList(get("$base/en/search?keyword=$q"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val title = doc.selectFirst(".detail_header .subj, h1.subj, .info .subj")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".detail_header .thmb img, .thumb img, .pic img")?.let {
                it.attr("data-url").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> s.isNotBlank() }
            } ?: manga.coverUrl
            val desc = doc.selectFirst(".summary, .detail_body .summary")?.text()?.trim()
            val author = doc.selectFirst(".author, .author_area .author, .info .author")?.text()?.trim()
            val genres = doc.select(".genre, .info .genre, .detail_body .genre")
                .map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(title = title, coverUrl = cover, description = desc, author = author, genres = genres)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("#_episodeList li, .episode-list #_listUl li, ul#_listUl li").mapNotNull { li ->
                val link = li.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epTitle = li.selectFirst(".subj span, .subj")?.text()?.trim() ?: "Episode"
                val epNo = li.attr("data-episode-no").toFloatOrNull()
                    ?: href.substringAfterLast("episode_no=").substringBefore("&").toFloatOrNull()
                    ?: 0f
                val url = if (href.startsWith("http")) href.removePrefix(base) else href
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = url,
                    name = epTitle,
                    chapterNumber = epNo,
                    dateUpload = 0L,
                )
            }.sortedBy { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("#content .viewer_lst img, .viewer_img img, #_imageList img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-url").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                if (url.startsWith("http")) Page(i, url, url) else null
            }
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.webtoon

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
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
    override val contentType = "MANHWA"
    override val homepageUrl get() = base

    private val base = "https://www.webtoons.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .header("Cookie", "pagGDPR=true; needCCPA=false; needCOPPA=false; locale=en")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCardList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".webtoon_list li, .card_lst li, .challenge_lst li, .daily_lst li").mapNotNull { li ->
            val link = li.selectFirst("a[href*='webtoons.com']") ?: li.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = li.selectFirst("strong.title, .subj, .info .subj, .cont .subj")?.text()?.trim()
                ?: return@mapNotNull null
            val cover = li.selectFirst("img")?.let {
                it.attr("data-url").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("data-src").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> s.isNotBlank() }
            }
            val url = toSourcePath(base, href)
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANHWA")
        }
    }

    // /en/genre/list uz vraci chybovou stranku - aktualni katalog je pod /en/originals
    // (jednostrankovy vypis vsech Originals, ne strankovany "top 100" seznam). /en/ranking
    // ("WEBTOON - Popular Series") je samostatna, opravdu jinak razena stranka - overeno
    // zive, prvni tituly se lisi od /en/originals. Zadnou zvlast "naposledy aktualizovano"
    // stranku appka nenasla, "Nejnovejsi" tak zustava na puvodnim katalogu.
    //
    // /en/genres je seznam vsech zanru (a.snb_tab._snb_tab_a) - kazdy odkazuje na
    // /en/genres/{slug}?sortOrder=MANA, ktera pouziva STEJNOU "ul.webtoon_list li" strukturu
    // jako ostatni katalogove stranky (overeno zive - horror vs unfiltered maji jine tituly),
    // takze staci znovupouzit parseCardList. Genre strankovani neni strankovane URL parametrem
    // (jednostrankovy vypis), proto se dalsi stranky nedoplnuji.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/en/genres"))
            val tags = doc.select("a.snb_tab._snb_tab_a").mapNotNull { a ->
                val slug = a.absUrl("href").substringAfter("/genres/").substringBefore("?").ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseCardList(get("$base/en/genres/${filter.genres.first()}?sortOrder=MANA"))
            }
            val path = if (filter.sortBy == "latest") "/en/originals" else "/en/ranking"
            parseCardList(get("$base$path"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseCardList(get("$base/en/search?keyword=$q"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val title = doc.selectFirst(".detail_header .subj, h1.subj, .info .subj")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".detail_header .thmb img, .thumb img, .pic img")?.let {
                it.attr("data-url").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> s.isNotBlank() }
            } ?: manga.coverUrl
            val desc = doc.selectFirst(".summary, .detail_body .summary")?.text()?.trim()
            val author = doc.selectFirst(".author, .author_area .author, .info .author")?.text()?.trim()
            val genres = doc.select(".genre, .info .genre, .detail_body .genre")
                .map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(title = title, coverUrl = cover, description = desc, author = author, genres = genres, contentType = "MANHWA")
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("#_episodeList li, .episode-list #_listUl li, ul#_listUl li").mapNotNull { li ->
                val link = li.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epTitle = li.selectFirst(".subj span, .subj")?.text()?.trim() ?: "Episode"
                val epNo = li.attr("data-episode-no").toFloatOrNull()
                    ?: href.substringAfterLast("episode_no=").substringBefore("&").toFloatOrNull()
                    ?: 0f
                val url = toSourcePath(base, href)
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = url,
                    name = epTitle,
                    chapterNumber = epNo,
                    dateUpload = 0L,
                )
            }.sortedBy { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("#content .viewer_lst img, .viewer_img img, #_imageList img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-url").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                if (url.startsWith("http")) Page(i, url, url) else null
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

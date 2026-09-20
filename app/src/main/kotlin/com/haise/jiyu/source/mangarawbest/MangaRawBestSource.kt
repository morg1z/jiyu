package com.haise.jiyu.source.mangarawbest

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangaraw.best (漫画 raw) - RAW (japonske) manga, mnoho dlouhych zavedenych sad
 * (napr. Kingdom pres 800+ kapitol). Cely web vcetne cteni je server-rendered,
 * zadny token na obrazcich. Alpine.js frontend neexponuje zanr/autora/status na
 * detailu zadnym staticky parsovatelnym zpusobem - hledani se take nepodarilo
 * najit jako cisty endpoint, takze getPopular je jedinou cestou k obsahu.
 */
@Singleton
class MangaRawBestSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangarawbest"
    override val name = "漫画 raw"
    override val homepageUrl get() = base
    private val base = "https://mangaraw.best"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val img = a.selectFirst(".cover-frame img") ?: return null
        val title = img.attr("alt").trim().ifBlank { return null }
        val cover = img.attr("src").trim().takeIf { it.isNotBlank() }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // sort= overeno zivě (Alpine.js select na strance ma i "-created_at"/"name"/"-name",
            // ale appka rozlisuje jen Popularni/Nejnovejsi) - vraci na "-views" vs "-updated_at"
            // prokazatelne jine tituly, ne jen JS-only filtr bez vlivu na server-rendered vystup.
            val sort = if (filter.sortBy == "latest") "-updated_at" else "-views"
            val url = "$base/manga-list?sort=$sort&page=$page"
            val doc = Jsoup.parse(get(url))
            doc.select("a:has(.cover-frame)").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        emptyList()
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("a[href^=${manga.url}/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""di-([\d.]+)hua""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img.chapter-image").mapIndexedNotNull { i, img ->
                val url = img.attr("data-original").ifBlank { img.attr("src") }.let { absoluteMediaUrl(base, it) }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

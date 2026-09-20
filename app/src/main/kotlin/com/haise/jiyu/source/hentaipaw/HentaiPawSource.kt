package com.haise.jiyu.source.hentaipaw

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HentaiPaw (hentaipaw.com - POZOR: NE .net, coz je nesouvisejici ad-redirect
 * domena). Next.js App Router web - archiv/detail jsou plne server-rendered
 * (staticky Jsoup-parsovatelne), ale skutecny reader ("/viewer?articleId=...")
 * uz obrazky nevklada primo do HTML - "src" atributy na detailu ukazuji jen na
 * nizke rozliseni "thumbnails/{n}.webp" (~220x308, jen nahled). Plne obrazky
 * (~10x vetsi soubory) jsou dostupne na CDN pod hash cestou
 * "{galleryId}/{hash}/{n}.webp" - hash neni odvoditelny ze slugu/ID, ale je
 * soucasti Next.js RSC streamovaciho payloadu (`self.__next_f.push([...])`)
 * na viewer strance, ktery Next.js posila i pro server-renderovane stranky.
 * Jediny fetch "/viewer?articleId={id}&page=1" obsahuje hashe VSECH stranek
 * najednou (Next.js si prednacita cely seznam pro klientskou navigaci), takze
 * getPageList potrebuje jen jeden pozadavek na celou galerii.
 */
@Singleton
class HentaiPawSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "hentaipaw"
    override val name = "HentaiPaw"
    override val supportsSortOrder: Boolean get() = false
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://hentaipaw.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("a[href^=/articles/]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val titleDiv = a.selectFirst("div.line-clamp-2") ?: return@mapNotNull null
            val title = titleDiv.attr("title").trim().ifBlank { titleDiv.text().trim() }.ifBlank { return@mapNotNull null }
            val cover = a.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }.distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            try { parseGalleryList(fetchDocument("$base/?page=$page")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/articles/search?keyword=$q&page=$page"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun tagLinks(doc: Document, path: String): List<String> =
        doc.select("a[href^=/$path/]").mapNotNull { it.text().trim().ifBlank { null } }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val artist = tagLinks(doc, "artists").firstOrNull()
            val genres = tagLinks(doc, "tags")
            manga.copy(title = title, artist = artist, genres = genres)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    private val articleIdRegex = Regex("""/articles/(\d+)""")
    // "src\":\"https://cdn.imagedeliveries.com/{id}/{hash}/{n}.webp" uvnitr
    // escapovaneho JSON retezce v self.__next_f.push(...) - vsimni si escapovanych
    // uvozovek (\") pred/po src, jinak by se to plete s obycejnymi <img src> atributy.
    private val nextFImageRegex = Regex("""src\\":\\"(https://cdn\.imagedeliveries\.com/\d+/[a-zA-Z0-9]+/(\d+)\.webp)""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val articleId = articleIdRegex.find(chapter.url)?.groupValues?.get(1) ?: return@withContext emptyList()
            val html = fetchHtml("$base/viewer?articleId=$articleId&page=1")
            nextFImageRegex.findAll(html)
                .map { it.groupValues[1] to it.groupValues[2].toInt() }
                .distinctBy { it.second }
                .sortedBy { it.second }
                .mapIndexed { i, (url, _) -> Page(index = i, url = url, imageUrl = url) }
                .toList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

package com.haise.jiyu.source.eahentai

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
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * eahentai.com - anglicka hentai doujinshi/manga galerie (Next.js App
 * Router). Karty (homepage i "/search?q=") jsou "a[href^=/a/]" s title
 * primo v "aria-label" a nahledem schovanym v Next.js Image proxy
 * ("/_next/image?url=<url-encoded cdn url>&..." v atributu srcset).
 * Reader strana ("/a/{id}/{page}") - libovolne cislo stranky - uz obsahuje
 * primo URL VSECH stranek cele galerie (bez proxy, primo na "i.eahentai.com"),
 * takze getPageList potrebuje jen jeden pozadavek bez ohledu na to, kolik
 * stranek ma zdrojova SChapter.url.
 *
 * Strankovani (homepage i search) je cistě klientske (nekonecny scroll pres
 * Next.js server action, ne query parametr - "?p=2"/"?page=2" overeno zive,
 * ze vraci uplne stejny obsah jako strana 1) - getPopular/search proto pro
 * page > 1 vraci prazdny seznam misto duplicitniho opakovani strany 1.
 */
@Singleton
class EAHentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "eahentai"
    override val name = "EAHentai"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://eahentai.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun coverFromSrcset(img: Element?): String? {
        val srcset = img?.attr("srcset")?.ifBlank { null } ?: return null
        val encoded = Regex("""url=([^&\s]+)""").find(srcset)?.groupValues?.get(1) ?: return null
        return try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Exception) { null }
    }

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("a[href^=/a/]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.attr("aria-label").trim().ifBlank { return@mapNotNull null }
            val cover = coverFromSrcset(a.selectFirst("img"))
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }.distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList()
            // "/latest" je samostatna, chronologicky serazena stranka - overeno zive
            // (jina sada ID galerii nez uvodni "/"), zatimco homepage misi cerstve s
            // nejakym vlastnim vyberem webu ("popular"/featured neni verejne zvlast
            // dostupne - web nema zadny "?sort="/"?order=" parametr ani odkaz v navigaci).
            val url = if (filter.sortBy == "latest") "$base/latest" else base
            try { parseGalleryList(fetchDocument(url)) }
            catch (_: Exception) { emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            if (page > 1) return@withContext emptyList()
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/search?q=$q"))
            } catch (_: Exception) { emptyList() }
        }

    /** Sekce jako "Artist"/"Tags" jsou label <span> následovaný sourozeneckým <div> s odkazy. */
    private fun labelledLinks(doc: Document, label: String): List<String> {
        val span = doc.select("span").firstOrNull { it.ownText().trim() == label } ?: return emptyList()
        val container = span.nextElementSibling() ?: return emptyList()
        return container.select("a").mapNotNull { it.text().trim().ifBlank { null } }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val artist = labelledLinks(doc, "Artist").joinToString(", ").ifBlank { null }
            val genres = labelledLinks(doc, "Tags")
            val descSpan = doc.select("span").firstOrNull { it.ownText().trim() == "Description" }
            val description = descSpan?.nextElementSibling()?.selectFirst("p")?.text()?.trim()?.ifBlank { null }
            manga.copy(title = title, artist = artist, genres = genres, description = description)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = "${manga.url}/1",
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    private val pageImageRegex = Regex("""i\.eahentai\.com/file/ea-gallery/galleries/[a-zA-Z0-9]+/image(\d+)\.webp""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(chapter.url)
            pageImageRegex.findAll(html)
                .map { it.value to it.groupValues[1].toInt() }
                .distinctBy { it.second }
                .sortedBy { it.second }
                .mapIndexed { i, (match, _) ->
                    val url = "https://$match"
                    Page(index = i, url = url, imageUrl = url)
                }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

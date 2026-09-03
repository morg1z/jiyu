package com.haise.jiyu.source.pururin

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
 * Pururin.me - klasicka anglicka hentai doujinshi/manga galerie (NE pururin.to,
 * ktera je mrtva, ani pururin.us, coz je podvodna ad-redirect domena).
 *
 * Cela galerie = jedna "kapitola" (viz NhentaiSource) - web nema kapitoly v
 * ramci jednoho dila. Detail stranka galerie uz obsahuje kompletni seznam
 * thumbnailu vsech stranek ("{n}t.jpg") primo v HTML, takze getPageList
 * nepotrebuje volat zadny dalsi "/read/{id}/{page}/..." endpoint - plny
 * obrazek je na stejne CDN URL, jen bez "t" pred priponou souboru.
 */
@Singleton
class PururinSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "pururin"
    override val name = "Pururin"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://pururin.me"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("a.card-gallery[href]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val h2 = a.selectFirst(".title h2") ?: return@mapNotNull null
            // h2 obsahuje "Anglicky nazev<br>Japonsky nazev" - oba jsou text-node
            // sourozenci <br>, takze ownText() by spojil oba dohromady; prvni
            // textNode je vzdy ten pred <br>.
            val title = h2.textNodes().firstOrNull()?.text()?.trim()?.ifBlank { null }
                ?: h2.text().trim().ifBlank { return@mapNotNull null }
            val cover = a.selectFirst("img.card-img-top")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val sort = if (filter.sortBy == "latest") "newest" else "most-popular"
            try {
                parseGalleryList(fetchDocument("$base/browse?sort=$sort&page=$page"))
            } catch (_: Exception) { emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/search?q=$q&page=$page"))
            } catch (_: Exception) { emptyList() }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            try {
                val doc = fetchDocument(manga.url)
                val title = doc.selectFirst("h1 span[itemprop=name]")?.text()?.trim()?.ifBlank { null }
                    ?: manga.title
                val cover = doc.selectFirst("img.cover")?.attr("src")?.trim()?.ifBlank { null } ?: manga.coverUrl

                var artist: String? = null
                var genres: List<String> = emptyList()
                doc.select("table.table-info tr").forEach { row ->
                    val label = row.selectFirst("td")?.text()?.trim().orEmpty()
                    val links = row.select("td ul.list-inline li a")
                    when (label) {
                        "Artist" -> artist = links.firstOrNull()?.text()?.trim()?.ifBlank { null }
                        "Contents" -> genres = links.mapNotNull { it.text().trim().ifBlank { null } }
                    }
                }
                manga.copy(title = title, coverUrl = cover, artist = artist, genres = genres)
            } catch (_: Exception) { manga }
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

    private val thumbRegex = Regex("""^(.*/)(\d+)t\.(\w+)$""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(chapter.url)
            doc.select("div.gallery-preview img").mapIndexedNotNull { i, img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }.trim().ifBlank { return@mapIndexedNotNull null }
                val match = thumbRegex.find(src) ?: return@mapIndexedNotNull null
                val (dir, num, ext) = match.destructured
                val full = "$dir$num.$ext"
                Page(index = i, url = full, imageUrl = full)
            }
        } catch (_: Exception) { emptyList() }
    }
}

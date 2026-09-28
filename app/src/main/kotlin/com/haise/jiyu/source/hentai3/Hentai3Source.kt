package com.haise.jiyu.source.hentai3

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
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
 * 3Hentai (3hentai.net) - anglicka hentai doujinshi galerie. Cela galerie =
 * jedna "kapitola" (viz NhentaiSource) - web nema kapitoly v ramci jednoho
 * dila.
 *
 * Domovska stranka `/` nema pagovatelny vypis (jen "Popular"/"Newest" widget
 * bez `?page=`), proto se pro getPopular pouziva `/language/english?page=N`,
 * coz je plnohodnotny pagovany seznam se stejnym markupem. Detail stranka
 * galerie uz obsahuje kompletni seznam thumbnailu vsech stranek
 * ("{n}t.jpg") primo v HTML - getPageList tak nepotrebuje volat zadny dalsi
 * "/d/{id}/{page}" reader endpoint, plny obrazek je na stejne CDN URL, jen
 * bez "t" pred priponou souboru (stejny vzor jako Pururin).
 */
@Singleton
class Hentai3Source @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hentai3"
    override val name = "3Hentai"
    override val isAdult = true
    override val homepageUrl get() = base
    override val supportsTagFilter: Boolean get() = true

    private val base = "https://3hentai.net"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("a.cover[href]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.selectFirst("div.title")?.text()?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val cover = a.selectFirst("img")?.let { img -> img.lazySrc().orEmpty() }
                ?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }

    // Tagovy index "/tags" (strankovany, odkazy jako absolutni "/tags/{slug}") a
    // archivy "/tags/{slug}?page=N" - overeno zive. Slug nese i suffix typu tagu
    // ("-female"/"-male" apod.) - pouziva se cely, jak ho web generuje. Vice tagu
    // najednou web nepodporuje - pri vice vybranych se pouzije prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            val out = mutableListOf<FilterTag>()
            var p = 1
            while (p <= 60) {
                val links = fetchDocument("$base/tags?page=$p").select("a[href*=/tags/]")
                if (links.isEmpty()) break
                links.forEach { a ->
                    val slug = a.attr("href").substringAfter("/tags/").trim('/').ifBlank { return@forEach }
                    val label = a.text().trim().ifBlank { return@forEach }
                    out += FilterTag(id = slug, label = label)
                }
                p++
            }
            out.distinctBy { it.id }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Web "?page=N" ignoruje - stranka 2 vraci bajtove identicky obsah jako
        // stranka 1 (audit DUP, overeno zive na /language/english i /search).
        if (page > 1) return@withContext emptyList()
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseGalleryList(fetchDocument("$base/tags/${filter.genres.first()}?page=$page"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // Bez parametru je vychozi razeni podle data (nejnovejsi) - "Popularni:" je
        // tu jen bocni widget s vlastnim sort=popular (navic 24h/7d varianty, ktere
        // appka nepouziva). Overeno zive: oba dotazy vraci skutecne odlisne seznamy.
        try {
            val sort = if (filter.sortBy == "popular") "?sort=popular&page=$page" else "?page=$page"
            parseGalleryList(fetchDocument("$base/language/english$sort"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList() // "?page=" ignorovan - viz getPopular
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseGalleryList(fetchDocument("$base/search?q=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val cover = doc.selectFirst("a.cover img")?.let { img -> img.lazySrc().orEmpty() }
                ?.trim()?.ifBlank { null } ?: manga.coverUrl

            var artist: String? = null
            var genres: List<String> = emptyList()
            doc.select("div.tag-container.field-name").forEach { row ->
                val label = row.ownText().trim().removeSuffix(":")
                val names = row.select("span.filter-elem a.name").mapNotNull { it.text().trim().ifBlank { null } }
                when (label) {
                    "Tags" -> genres = names
                    "Artists" -> artist = names.firstOrNull()
                }
            }
            manga.copy(title = title, coverUrl = cover, artist = artist, genres = genres)
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

    private val thumbRegex = Regex("""^(.*/)(\d+)t\.(\w+)$""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(chapter.url)
            doc.select("div.single-thumb img").mapIndexedNotNull { i, img ->
                val src = img.lazySrc().orEmpty().trim().ifBlank { return@mapIndexedNotNull null }
                val match = thumbRegex.find(src) ?: return@mapIndexedNotNull null
                val (dir, num, ext) = match.destructured
                val full = "$dir$num.$ext"
                Page(index = i, url = full, imageUrl = full)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

package com.haise.jiyu.source.hdoujin

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
 * hDoujin (hdoujin.com - POZOR: NE .org, coz je jina JS-SPA domena bez obsahu)
 * - anglicka hentai doujinshi galerie s vlastni sablonou. Cela galerie = jedna
 * "kapitola" (viz NhentaiSource). Detail stranka JE zaroven reader - vsechny
 * stranky galerie jsou uz v HTML jako <img> uvnitr `.reader-image-wrapper`,
 * takze getPageList nepotrebuje zadny dalsi endpoint.
 */
@Singleton
class HDoujinSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hdoujin"
    override val name = "hDoujin"
    override val supportsSortOrder: Boolean get() = false
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://hdoujin.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseGalleryList(doc: Document): List<SManga> =
        doc.select("div.story-card").mapNotNull { card ->
            // URL schema se zmenilo /en/ -> /n/ (audit 2026-10) - bereme oboji.
            val a = card.selectFirst("a.card-title[href^=\"/n/\"], a.card-title[href^=\"/en/\"]")
                ?: return@mapNotNull null
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.text().trim().ifBlank { a.attr("title").trim() }.ifBlank { return@mapNotNull null }
            val cover = card.selectFirst("img.cover-img")?.attr("src")?.trim()?.ifBlank { null }
            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANGA")
        }.distinctBy { it.url }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            // "?page=N" web ignoruje - stranka 2 vraci identicky obsah jako
            // stranka 1 (audit DUP, overeno zive).
            if (page > 1) return@withContext emptyList()
            // Tag stranky /tag/{id}/{slug} vraci stejne story-card listing (overeno live).
            val tag = filter.genres.firstOrNull()
            try {
                val url = if (tag != null) "$base/tag/$tag" else "$base/?page=$page"
                parseGalleryList(fetchDocument(url))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList() // "?page=" ignorovan - viz getPopular
            val tag = filter.genres.firstOrNull()
            if (tag != null) {
                // Tag stranka query parametr nepodporuje - textovy dotaz
                // dofilitrujeme na klientu pres titulky.
                val base = getPopular(page, filter)
                return@withContext if (query.isBlank()) base
                else base.filter { it.title.contains(query.trim(), ignoreCase = true) }
            }
            if (query.isBlank()) return@withContext getPopular(page, filter)
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                parseGalleryList(fetchDocument("$base/?q=$q&page=$page"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    /**
     * Web nema tag index (/tags 404) - ale kazda gallery karta nese sve tag
     * badges (a.tag-badge[data-tax=tag], href="/tag/{id}/{slug}"). Jedna
     * listova stranka poskytne ~140 unikatnich tagu, takze tagy sklizime
     * z homepage a jedne popularni tag stranky.
     */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        val acc = linkedMapOf<String, String>() // path ("44/group") -> label
        for (url in TAG_SEED_PAGES) {
            try {
                Jsoup.parse(fetchHtml(url), url)
                    .select("a.tag-badge[data-tax=tag]")
                    .forEach { a ->
                        val path = a.attr("href").trim().removePrefix("/tag/").trim('/')
                        if (path.isNotBlank()) acc.putIfAbsent(path, a.text().trim().ifBlank { path })
                    }
            } catch (e: Exception) { e.rethrowIfControl() }
            if (acc.size >= 120) break
        }
        acc.map { (path, label) -> FilterTag(id = path, label = label) }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        val TAG_SEED_PAGES = listOf(
            "https://hdoujin.com/tag/44/group",
            "https://hdoujin.com/tag/17/glasses",
            "https://hdoujin.com/",
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(manga.url)
            val title = doc.selectFirst("h1.title-thai")?.text()?.trim()?.ifBlank { null } ?: manga.title
            val artist = doc.select("a.tag-badge[data-tax=artist]").firstOrNull()?.text()?.trim()?.ifBlank { null }
            val genres = doc.select("a.tag-badge[data-tax=tag]").mapNotNull { it.text().trim().ifBlank { null } }
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(chapter.url)
            doc.select(".reader-image-wrapper img").mapIndexedNotNull { i, img ->
                val src = img.lazySrc().orEmpty().trim().ifBlank { return@mapIndexedNotNull null }
                Page(index = i, url = src, imageUrl = src)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

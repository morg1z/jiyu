package com.haise.jiyu.source.todaymanga

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * todaymanga.com - vlastni sablona, plne server-rendered vcetne cteni. Seznam
 * kapitol je na samostatne strance `/book/{slug}/chapter-list`.
 */
@Singleton
class TodaymangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "todaymanga"
    override val name = "Todaymanga"
    override val homepageUrl get() = base
    private val base = "https://todaymanga.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").trim().removeSuffix(" manga").ifBlank { return null }
        val cover = img.attr("src").trim().let { absoluteMediaUrl(base, it) }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    // "/genre" ma seznam vsech zanru (~50, <a href="/genre/{slug}"><span>Label</span>...) s
    // vlastni archivni strankou "/genre/{slug}" - overeno zive, vraci odlisny seznam titulu
    // nez "/category/editor-pick"/"/category/recent".
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/genre"))
            val tags = doc.select("a[href^=/genre/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/genre/").trim().ifBlank { null } ?: return@mapNotNull null
                val label = a.selectFirst("span")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Genre archiv nema strankovani ani vlastni razeni - pri vybranem zanru se
        // filter.sortBy ignoruje, jen prvni vybrany zanr se pouzije (kombinace vice
        // zanru neni podporovana).
        if (filter.genres.isNotEmpty()) {
            if (page > 1) return@withContext emptyList()
            return@withContext try {
                val doc = Jsoup.parse(get("$base/genre/${filter.genres.first()}"))
                doc.select("a[href^=/book/]:has(img)").mapNotNull(::parseCard).distinctBy { it.url }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (page > 1) return@withContext emptyList()
        // Homepage mixala dohromady VSECHNY sekce (Editors' Choices, Recent Updated,
        // Completed Popular...) do jednoho seznamu bez rozliseni - "Nejnovejsi" v appce
        // tak vzdy vratilo uplne stejna data jako "Popularni". Vlastni /category/ stranky
        // maji presne tohle rozliseni (a na rozdil od homepage skutecne strankuji).
        val path = if (filter.sortBy == "latest") "/category/recent" else "/category/editor-pick"
        try {
            val doc = Jsoup.parse(get("$base$path"))
            doc.select("a[href^=/book/]:has(img)").mapNotNull(::parseCard).distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search?q=$q"))
            doc.select("a[href^=/book/]:has(img)").mapNotNull(::parseCard).distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                author = doc.selectFirst("a[href^=/author/] h2[itemprop=name]")?.text()?.trim()?.takeIf { it.isNotBlank() },
                genres = doc.select("a.tag-item[href^=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}/chapter-list"))
            doc.select("ul.chapters-list li h5.headline a[href]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""Chapter\s*([\d.]+)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img.lazyload[data-src]").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

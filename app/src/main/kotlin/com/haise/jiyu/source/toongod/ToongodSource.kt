package com.haise.jiyu.source.toongod

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * toongod.cc - vlastni (ne-Madara) sablona pro korejske manhwa/webtoon preklady
 * (POZOR: jen .cc domena zije, .org/.com/.net jsou mrtve/blokovane). Archiv
 * "/webtoons/page/N/?order=..." ma ~5000 titulu a funkcni razeni, detail
 * i kapitoly jsou server-rendered HTML bez lazy-loadingu na strance kapitoly
 * (obrazky maji rovnou "src", ne "data-src").
 */
@Singleton
class ToongodSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "toongod"
    override val name = "ToonGod"
    override val contentType = "MANHWA"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://toongod.cc"

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Chyba ${response.code} pri nacitani $url" }
            return Jsoup.parse(response.body?.string().orEmpty(), url)
        }
    }

    // Archiv ("/webtoons/") i vysledky hledani ("/search/") pouzivaji stejny
    // "div.latest-item" radek - jeden parser stac na obojí.
    private fun parseMangaList(doc: Document): List<SManga> =
        doc.select("div.latest-item").mapNotNull { item ->
            val link = item.selectFirst("div.latest-left a") ?: item.selectFirst("a") ?: return@mapNotNull null
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = item.selectFirst("h4.title-smaller")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.attr("title").trim().ifBlank { return@mapNotNull null }
            val cover = item.selectFirst("img.img-latest")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.trim()?.ifBlank { null }

            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = "MANHWA")
        }

    // "/genre/{slug}/" archiv (napr. "/genre/action/") pouziva stejnou ".latest-item"
    // sablonu jako "/webtoons/" a "/search/" - overeno zive (odlisne tituly pro
    // genre "action" vs "romance", i pri strankovani "?page=N" a razeni "?order=...").
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = fetchDocument("$base/webtoons/")
            val tags = doc.select("a[href*=/genre/]").mapNotNull { a ->
                val href = a.absUrl("href")
                val slug = Regex("""/genre/([^/]+)/?$""").find(href)?.groupValues?.get(1)?.ifBlank { null }
                    ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun genreUrl(slug: String, page: Int, orderby: String): String {
        val url = "$base/genre/$slug/"
        val query = mutableListOf<String>()
        if (page > 1) query += "page=$page"
        query += "order=$orderby"
        return "$url?" + query.joinToString("&")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val orderby = when (filter.sortBy) {
                "latest" -> "latest"
                "title"  -> "alphabet"
                else     -> "views"
            }
            val genre = filter.genres.firstOrNull()
            val url = if (genre != null) genreUrl(genre, page, orderby) else "$base/webtoons/page/$page/?order=$orderby"
            parseMangaList(fetchDocument(url))
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val genre = filter.genres.firstOrNull()
            if (genre != null) {
                return@withContext parseMangaList(fetchDocument(genreUrl(genre, page, "views")))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            parseMangaList(fetchDocument("$base/search/?s=$q&page=$page"))
        }

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            val description = doc.selectFirst("div.short-desc-content")?.text()?.trim()?.ifBlank { null }

            var status: String? = null
            var author: String? = null
            var artist: String? = null
            var genres: List<String> = emptyList()
            var detectedType: String? = null

            // ".main-info-list" radky jsou rozdelene do dvou bloku (post-content a
            // post-status), ale obe pouzivaji stejnou tridu bez ohledu na tag
            // (druhy blok ma nekonzistentne "<uL>" misto "<ul>") - selector podle
            // tridy oba bloky pokryje najednou.
            doc.select(".main-info-list li").forEach { li ->
                val label = li.selectFirst("h5")?.text()?.trim().orEmpty()
                when {
                    label.startsWith("Author") -> author = li.select("div a").joinToString(", ") { it.text().trim() }.ifBlank { null }
                    label.startsWith("Artist") -> artist = li.select("div a").joinToString(", ") { it.text().trim() }.ifBlank { null }
                    label.startsWith("Genres") -> genres = li.select("div a").map { it.text().trim() }.filter { it.isNotBlank() }
                    label.startsWith("Type")   -> detectedType = normalizeContentType(li.selectFirst("span")?.text().orEmpty())
                    label.startsWith("Status") -> status = li.selectFirst("span")?.text()?.trim()?.ifBlank { null }
                }
            }

            manga.copy(
                description = description,
                status = status,
                author = author,
                artist = artist,
                genres = genres,
                contentType = detectedType ?: "MANHWA",
            )
        }

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            doc.select("ul.chapter-list li").mapNotNull { row -> chapterFromRow(row, manga.url) }
        }

    private fun chapterFromRow(row: Element, mangaUrl: String): SChapter? {
        val link = row.selectFirst("a") ?: return null
        val url = link.absUrl("href").ifBlank { return null }
        val name = link.selectFirst("span.chapter-name")?.text()?.trim()
            ?.ifBlank { null } ?: link.text().trim().ifBlank { return null }
        val chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
        val dateText = link.selectFirst("span.ct-update")?.text()?.trim()

        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = url,
            name = name,
            chapterNumber = chapterNumber,
            dateUpload = parseDate(dateText),
        )
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            // "23 Dec 2025"
            SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).parse(text)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(chapter.url)
            doc.select("div.reading-content img").mapIndexedNotNull { i, img ->
                val src = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
                    .trim().ifBlank { return@mapIndexedNotNull null }
                Page(index = i, url = src, imageUrl = src)
            }
        }

    private fun normalizeContentType(text: String): String? = when (text.trim().lowercase()) {
        "manga" -> "MANGA"
        "manhwa" -> "MANHWA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> null
    }
}

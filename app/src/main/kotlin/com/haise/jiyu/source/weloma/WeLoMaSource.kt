package com.haise.jiyu.source.weloma

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
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
 * weloma.net (WeLoMa) - RAW (japonske) manga, stejna sablonova rodina jako KT9
 * (klto9.com), ale jina implementace: katalog i seznam kapitol jsou tady plne
 * server-rendered (na rozdil od KT9, kde je seznam kapitol za neuhodnutelnym
 * per-manga nahodnym AJAX endpointem). Obrazky kapitoly jsou proste base64 v
 * `data-img` atributu - zadny token, jen zakodovana primeho URL.
 *
 * Detailni stranka ma sve vlastni h3/title pres `data-enc` (base64) misto
 * primeho textu - proto getMangaDetails NEPRESAZUJE title, necha puvodni
 * z listingu (tam uz je primy text).
 */
@Singleton
class WeLoMaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "weloma"
    override val name = "WeLoMa"
    override val language = "ja" // "Read Manga Raw" - japonske raw kapitoly (overeno zive)
    override val homepageUrl get() = base
    override val supportsTagFilter: Boolean get() = true
    private val base = "https://weloma.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(card: Element): SManga? {
        val a = card.selectFirst(".series-title a") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = a.text().trim().ifBlank { return null }
        val styleEl = card.selectFirst(".content[style*=background-image]")
        val cover = styleEl?.attr("style")?.let { Regex("""url\('([^']*)'\)""").find(it)?.groupValues?.get(1) }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    // Zanrove odkazy "/l/{id}" jsou na homepage v sekci "Genres" a poznavaci
    // znacka je atribut data-title="Genre {nazev}" - autori/artisti sdili stejny
    // "/l/" prefix, ale jejich odkazy data-title="Genre" nemaji, takze se
    // nepleteji. Archiv "/l/{id}?page=N" strankuje stejne jako manga-list.
    // Vice zanru najednou web nepodporuje - pri vice vybranych se pouzije prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            Jsoup.parse(get("$base/")).select("a[data-title^=Genre]").mapNotNull { a ->
                val tagId = a.attr("href").substringAfter("/l/").trim('/').ifBlank { return@mapNotNull null }
                val label = a.attr("data-title").removePrefix("Genre").trim()
                    .ifBlank { a.text().trim() }.ifBlank { return@mapNotNull null }
                FilterTag(id = tagId, label = label)
            }.distinctBy { it.id }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val doc = Jsoup.parse(get("$base/l/${filter.genres.first()}?page=$page"))
                doc.select("div.thumb-item-flow").mapNotNull(::parseCard)
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // "last_update" overeno zive - jiny poradek nez "views". Ostatni vyzkousene
        // nazvy ("latest"/"update"/"new") web nerozezna a tise spadne na stejny
        // (abecedni odzadu) fallback jako neplatna hodnota - jen "last_update" funguje.
        val sort = if (filter.sortBy == "latest") "last_update" else "views"
        try {
            val doc = Jsoup.parse(get("$base/manga-list.html?listType=pagination&page=$page&sort=$sort&sort_type=DESC"))
            doc.select("div.thumb-item-flow").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/manga-list.html?name=$q&page=$page"))
            doc.select("div.thumb-item-flow").mapNotNull(::parseCard)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val author = doc.selectFirst("a.btn-info[href^=/l/]")?.text()?.trim()
            val genres = doc.select("a.btn-danger[href^=/l/]").map { it.text().trim() }.filter { it.isNotBlank() }
            val status = doc.selectFirst("a.btn-success[href^=/manga-]")?.text()?.trim()
            manga.copy(author = author?.takeIf { it.isNotBlank() }, genres = genres, status = status)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            // Web mezitim zmenil obal z <div class="list-chapters"> na
            // <ul class="list-chapters at-series"> (overeno zive) - selektor
            // vazany na konkretni tag "div" pak nenasel nic, "zadne kapitoly"
            // pro kazdy titul. Bez tag-vazby matchuje obojí.
            doc.select(".list-chapters a[href^=/c/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.attr("title").ifBlank { a.text().trim() }.ifBlank { return@mapNotNull null }
                val num = parseChapterNumber(name) ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            doc.select("img.chapter-img[data-img]").mapIndexedNotNull { i, img ->
                val encoded = img.attr("data-img").ifBlank { return@mapIndexedNotNull null }
                val url = try {
                    String(java.util.Base64.getDecoder().decode(encoded))
                } catch (e: Exception) { e.rethrowIfControl(); return@mapIndexedNotNull null }
                if (!url.startsWith("http")) return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

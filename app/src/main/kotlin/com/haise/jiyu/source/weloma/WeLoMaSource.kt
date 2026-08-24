package com.haise.jiyu.source.weloma

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
    override val homepageUrl get() = base
    private val base = "https://weloma.net"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
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

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/manga-list.html?listType=pagination&page=$page&sort=views&sort_type=DESC"))
            doc.select("div.thumb-item-flow").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/manga-list.html?name=$q&page=$page"))
            doc.select("div.thumb-item-flow").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val author = doc.selectFirst("a.btn-info[href^=/l/]")?.text()?.trim()
            val genres = doc.select("a.btn-danger[href^=/l/]").map { it.text().trim() }.filter { it.isNotBlank() }
            val status = doc.selectFirst("a.btn-success[href^=/manga-]")?.text()?.trim()
            manga.copy(author = author?.takeIf { it.isNotBlank() }, genres = genres, status = status)
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            // Web mezitim zmenil obal z <div class="list-chapters"> na
            // <ul class="list-chapters at-series"> (overeno zive) - selektor
            // vazany na konkretni tag "div" pak nenasel nic, "zadne kapitoly"
            // pro kazdy titul. Bez tag-vazby matchuje obojí.
            doc.select(".list-chapters a[href^=/c/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.attr("title").ifBlank { a.text().trim() }.ifBlank { return@mapNotNull null }
                val num = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img.chapter-img[data-img]").mapIndexedNotNull { i, img ->
                val encoded = img.attr("data-img").ifBlank { return@mapIndexedNotNull null }
                val url = try {
                    String(java.util.Base64.getDecoder().decode(encoded))
                } catch (_: Exception) { return@mapIndexedNotNull null }
                if (!url.startsWith("http")) return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

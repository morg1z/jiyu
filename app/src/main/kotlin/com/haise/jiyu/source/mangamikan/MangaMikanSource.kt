package com.haise.jiyu.source.mangamikan

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
 * mangamikan.com - vlastni sablona, plne server-rendered vcetne cteni.
 * Obrazky maji podepsanou URL (`/i.php?c=X&f=Y&exp=...&t=...`), ale token uz
 * je hotovy primo v `data-src` atributu na strance - zadny dalsi request
 * navic netreba.
 */
@Singleton
class MangaMikanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangamikan"
    override val name = "MangaMikan"
    override val homepageUrl get() = base
    private val base = "https://mangamikan.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").trim().ifBlank { return null }
        // Bug fix - "src" u img.cover je jen prazdny/placeholder atribut (lazy-loading), i
        // kdyz komentar u tridy uz spravne rikal, ze hotova URL je v "data-src" - kod se ale
        // divam na "src", takze coverUrl vzdy vyslo null (nahlaseno jako "covery se nenacitaji").
        // Navic je "data-src" relativni cesta ("/i.php?..."), ne absolutni URL, takze i pri
        // spravnem atributu by "startsWith(http)" test vyfiltroval vsechno - treba prefixovat base.
        val raw = a.selectFirst("img.cover")?.attr("data-src")?.trim()?.ifBlank { null }
            ?: a.selectFirst("img.cover")?.attr("src")?.trim()?.ifBlank { null }
        val cover = when {
            raw == null -> null
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> "$base$raw"
            else -> null
        }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/browse?page=$page"))
            doc.select("a.card-manga").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/browse?q=$q&page=$page"))
            doc.select("a.card-manga").mapNotNull(::parseCard)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val authorText = doc.select("span").firstOrNull { it.text().trim().startsWith("Author:") }
                ?.selectFirst("b")?.text()?.trim()
            manga.copy(
                genres = doc.select("a.genre-pill").map { it.text().trim() }.filter { it.isNotBlank() },
                author = authorText?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("a[href^=/read/]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img.page-img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").takeIf { it.startsWith("/i.php") } ?: return@mapIndexedNotNull null
                Page(i, "$base$url", "$base$url")
            }
        } catch (_: Exception) { emptyList() }
    }
}

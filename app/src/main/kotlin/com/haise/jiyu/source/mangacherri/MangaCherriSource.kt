package com.haise.jiyu.source.mangacherri

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangacherri.com - vlastni Vue/Nuxt sablona (SSR), plne server-rendered
 * vcetne cteni. Obrazky primo v HTML bez tokenu. Odkazy na kapitoly jsou
 * RELATIVNI bez uvodniho lomitka (napr. `"slug/24108"`), proto se HTML
 * parsuje s explicitni base URI, aby `abs:href` spravne poskladalo cestu.
 * Hledani (`/search.php` POST) se nepodarilo overit jako funkcni filtr -
 * search() vraci prazdny seznam.
 */
@Singleton
class MangaCherriSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "mangacherri"
    override val name = "MangaCherri"
    override val homepageUrl get() = base
    private val base = "https://mangacherri.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseDoc(url: String) = Jsoup.parse(get(url), url)

    private fun parseCard(a: Element): SManga? {
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").trim().ifBlank { return null }
        val cover = a.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.startsWith("http") }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // overereno zive: home.php je jen sada karuselu (Popular Now/Latest Chapter/
            // Most Popular/Completed), zatimco new-chapters.php ma vlastni strankovanou
            // mrizku razenou dle posledni aktualizace kapitoly - genuinne jina razeni.
            val path = if (filter.sortBy == "latest") "new-chapters.php" else "home.php"
            val doc = Jsoup.parse(get("$base/$path?page=$page"))
            doc.select("a.manga-cover-link").mapNotNull(::parseCard).distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = emptyList()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = parseDoc("$base${manga.url}")
            val status = doc.select("span.text.grey.small").firstOrNull { it.text().trim() == "Status" }
                ?.nextElementSibling()?.text()?.trim()
            manga.copy(
                genres = doc.select("a[href*=/genre.php]").map { it.text().trim() }.filter { it.isNotBlank() },
                author = doc.select("a[href*=/author/]").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() },
                status = status?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = parseDoc("$base${manga.url}")
            doc.select("div.chapters-container a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val num = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = href.removePrefix(base), name = "Chapter $name", chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("img[src*=/mangas/]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

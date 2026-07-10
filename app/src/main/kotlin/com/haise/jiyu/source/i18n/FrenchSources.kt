package com.haise.jiyu.source.i18n

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

// ── Japscan (FR) ──────────────────────────────────────────────────────────────
@Singleton
class JapscanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "japscan"
    override val name = "Japscan 🇫🇷"
    private val base  = "https://www.japscan.lol"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/mangas/$page/")).select(".d-flex.flex-column a.text-dark").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = a.text().trim().ifBlank { return@mapNotNull null },
                    coverUrl = a.selectFirst("img")?.attr("src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q   = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search/"))
            // Japscan search is JS-driven; fallback to popular
            doc.select(".card .card-body a").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href, title = a.text().trim(), coverUrl = null)
            }.filter { it.title.contains(query, ignoreCase = true) }
                .ifEmpty { getPopular(page, filter) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title       = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".d-flex img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("p.m-0")?.text()?.trim(),
                genres      = doc.select("a[href*='/tags/']").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("#chapters_list .chapters_list a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Chapitre ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("div#images img, .reading-content img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ── Anime-Sama (FR) ───────────────────────────────────────────────────────────
@Singleton
class AnimeSamaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "animesama"
    override val name = "Anime-Sama 🇫🇷"
    private val base  = "https://anime-sama.fr"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/catalogue/?page=$page&type=manga&sort=vues")).select(".cardListAnime").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = el.selectFirst("h1, h2, .nom")?.text()?.trim() ?: a.attr("title").trim().ifBlank { return@mapNotNull null },
                    coverUrl = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/catalogue/?search=$q&type=manga")).select(".cardListAnime").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.attr("href"),
                    title    = el.selectFirst("h1, h2, .nom")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title       = doc.selectFirst("h1.titre, h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst("img.cover, img.imgSerie")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("p.synopsis, .synopsis")?.text()?.trim(),
                genres      = doc.select(".tag, .genre a").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select(".chapitreList a, .listeChap a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Chapitre ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select(".reading-content img, .chapter-content img, #pageContainer img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ── ScanVF (FR) ───────────────────────────────────────────────────────────────
@Singleton
class ScanVFSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "scanvf"
    override val name = "ScanVF 🇫🇷"
    private val base  = "https://www.scan-vf.net"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/manga-list?page=$page&sort=views")).select(".manga-poster, .novel-item, .bsx").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val titleText = (el.selectFirst("a[title], .manga-name")?.attr("title")
                    ?: el.selectFirst(".manga-name, h3")?.text()?.trim())
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SManga(sourceId = id, url = href,
                    title    = titleText,
                    coverUrl = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/?s=$q")).select(".manga-poster, .bsx").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val titleText = (el.selectFirst("a[title]")?.attr("title") ?: el.selectFirst("h3")?.text()?.trim())
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SManga(sourceId = id, url = a.attr("href"),
                    title    = titleText,
                    coverUrl = el.selectFirst("img")?.attr("src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title       = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".thumb img, .manga-poster img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst(".summary p, .description")?.text()?.trim(),
                genres      = doc.select(".genres a, .categories a").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select(".chapter-list li a, ul.row-content-chapter li a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Chapitre ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select(".reading-content img, .container-chapter-reader img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

private fun String?.ifNullOrBlank(fallback: () -> Nothing): String =
    if (this.isNullOrBlank()) fallback() else this

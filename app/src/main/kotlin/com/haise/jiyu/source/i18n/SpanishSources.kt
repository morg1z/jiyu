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

// ── TuMangaOnline / LectorTMO (ES) ────────────────────────────────────────────
@Singleton
class TMOSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "tmo"
    override val name = "TuMangaOnline 🇪🇸"
    private val base  = "https://lectortmo.com"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/library?order_item=likes_count&order_dir=desc&page=$page")).select(".element").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = el.selectFirst(".title-truncate, h4")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img.cover, img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }
                    })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/library?title=$q&page=$page")).select(".element").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.attr("href"),
                    title    = el.selectFirst(".title-truncate, h4")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title       = doc.selectFirst("h2.element-title, h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst("img.book-thumbnail, img.cover")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("p.element-description, .description")?.text()?.trim(),
                genres      = doc.select("a.badge.badge-secondary, .categories a").map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".badge.badge-info")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("ul.chapters-list li, .chapters-list .chapter-title a").mapIndexed { i, el ->
                val a    = el.selectFirst("a") ?: el.takeIf { it.tagName() == "a" } ?: return@mapIndexed null
                val href = a!!.attr("href")
                val name = (el.selectFirst(".chapter-title, .num-chapter")?.text() ?: a.text()).trim()
                    .ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }.filterNotNull()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            // TMO redirects to a scan viewer — extract image list from page
            val doc = Jsoup.parse(get(chapter.url))
            val imgs = doc.select(".viewer-container img, #viewer img, .viewer img")
            if (imgs.isNotEmpty()) {
                return@withContext imgs.mapIndexedNotNull { i, img ->
                    val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                        ?: return@mapIndexedNotNull null
                    Page(i, url, url)
                }
            }
            // Fallback: extract from script
            val script = doc.select("script").map { it.data() }
                .firstOrNull { it.contains("pagesData") || it.contains("images") } ?: return@withContext emptyList()
            Regex(""""(https?://[^"]+\.(jpg|jpeg|png|webp))"""")
                .findAll(script).mapIndexed { i, m -> Page(i, m.groupValues[1], m.groupValues[1]) }.toList()
        } catch (_: Exception) { emptyList() }
    }
}

// ── InManga (ES) ──────────────────────────────────────────────────────────────
@Singleton
class InMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "inmanga"
    override val name = "InManga 🇪🇸"
    private val base  = "https://inmanga.com"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/ver/manga/lista?page=$page&sortBy=visit")).select(".manga-card, .thumbnail").mapNotNull { el ->
                val a    = el.selectFirst("a[href*='/ver/manga/']") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = (el.selectFirst(".manga-title, h4, .caption")?.text()?.trim()
                        ?: a.attr("title").trim()).ifBlank { return@mapNotNull null },
                    coverUrl = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/ver/manga/lista?page=$page&filter=$q")).select(".manga-card, .thumbnail").mapNotNull { el ->
                val a = el.selectFirst("a[href*='/ver/manga/']") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.attr("href"),
                    title    = el.selectFirst(".manga-title, h4")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title       = doc.selectFirst("h1.manga-name, h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".manga-cover img, img.img-thumbnail")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst(".manga-synopsis, p.synopsis")?.text()?.trim(),
                genres      = doc.select(".manga-genres a, .tags a").map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".manga-author, .author")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("ul#chapters-list li a, .chapters a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select(".chapter-content img, #chapter-images img, .viewer img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ── MangaLeer (ES) ────────────────────────────────────────────────────────────
@Singleton
class MangaLeerSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "mangaleer"
    override val name = "MangaLeer 🇪🇸"
    private val base  = "https://mangaleer.com"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/manga/?page=$page&m_orderby=views")).select("div.page-item-detail").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = el.selectFirst("h3 a, .post-title a")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/?s=$q&post_type=wp-manga")).select(".c-tabs-item__content").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.attr("href"),
                    title = el.selectFirst("h3 a, .post-title a")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("data-src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title       = doc.selectFirst(".post-title h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".summary_image img")?.attr("data-src") ?: manga.coverUrl,
                description = doc.selectFirst(".summary__content p")?.text()?.trim(),
                genres      = doc.select(".genres-content a").map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".author-content a")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select(".wp-manga-chapter a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select(".reading-content img, .page-break img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                    .takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ── Union Mangás (PT-BR) ──────────────────────────────────────────────────────
@Singleton
class UnionMangasSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id   = "unionmangas"
    override val name = "Union Mangás 🇧🇷"
    private val base  = "https://unionmangas.xyz"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.body?.string() ?: "" }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/lista-mangas?page=$page&orderby=views")).select(".div-manga, .manga-card").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = (el.selectFirst(".manga-title, .title, h3, h4")?.text()?.trim()
                        ?: a.attr("title").trim()).ifBlank { return@mapNotNull null },
                    coverUrl = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/lista-mangas?search=$q")).select(".div-manga, .manga-card").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.attr("href"),
                    title    = el.selectFirst(".manga-title, h3")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title       = doc.selectFirst("h1, .manga-title")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".img-manga img, .capa img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst(".sinopse, .description p")?.text()?.trim(),
                genres      = doc.select(".genres a, .categorias a").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select(".list-capitulos a, .chapters a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select(".chapter-images img, .reading-content img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

package com.haise.jiyu.source.i18n

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
import com.haise.jiyu.util.rethrowIfControl
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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

// ── TuMangaOnline / LectorTMO (ES) ────────────────────────────────────────────
@Singleton
class TMOSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "tmo"
    override val name     = "TuMangaOnline 🇪🇸"
    override val language = "es"
    override val homepageUrl get() = base
    private val base      = "https://lectortmo.com"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/library?order_item=likes_count&order_dir=desc&page=$page"), base).select(".element").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = el.selectFirst(".title-truncate, h4")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img.cover, img")?.let { img ->
                        img.lazySrc().orEmpty()
                    })
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/library?title=$q&page=$page"), base).select(".element").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.absUrl("href"),
                    title    = el.selectFirst(".title-truncate, h4")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.let { img -> img.lazySrc().orEmpty() })
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            manga.copy(
                title       = doc.selectFirst("h2.element-title, h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst("img.book-thumbnail, img.cover")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("p.element-description, .description")?.text()?.trim(),
                genres      = doc.select("a.badge.badge-secondary, .categories a").map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".badge.badge-info")?.text()?.trim(),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            doc.select("ul.chapters-list li, .chapters-list .chapter-title a").mapIndexed { i, el ->
                val a    = el.selectFirst("a") ?: el.takeIf { it.tagName() == "a" } ?: return@mapIndexed null
                val href = a.absUrl("href")
                val name = (el.selectFirst(".chapter-title, .num-chapter")?.text() ?: a.text()).trim()
                    .ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = parseChapterNumber(name) ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }.filterNotNull()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            // TMO redirects to a scan viewer — extract image list from page
            val doc = Jsoup.parse(get(chapter.url), base)
            val imgs = doc.select(".viewer-container img, #viewer img, .viewer img")
            if (imgs.isNotEmpty()) {
                return@withContext imgs.mapIndexedNotNull { i, img ->
                    val url = img.lazySrc().orEmpty().let { absoluteMediaUrl(base, it) }
                        ?: return@mapIndexedNotNull null
                    Page(i, url, url)
                }
            }
            // Fallback: extract from script
            val script = doc.select("script").map { it.data() }
                .firstOrNull { it.contains("pagesData") || it.contains("images") } ?: return@withContext emptyList()
            Regex(""""(https?://[^"]+\.(jpg|jpeg|png|webp))"""")
                .findAll(script).mapIndexed { i, m -> Page(i, m.groupValues[1], m.groupValues[1]) }.toList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

// ── InManga (ES) ──────────────────────────────────────────────────────────────
@Singleton
class InMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "inmanga"
    override val name     = "InManga 🇪🇸"
    override val language = "es"
    override val homepageUrl get() = base
    private val base      = "https://inmanga.com"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/ver/manga/lista?page=$page&sortBy=visit"), base).select(".manga-card, .thumbnail").mapNotNull { el ->
                val a    = el.selectFirst("a[href*='/ver/manga/']") ?: return@mapNotNull null
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = (el.selectFirst(".manga-title, h4, .caption")?.text()?.trim()
                        ?: a.attr("title").trim()).ifBlank { return@mapNotNull null },
                    coverUrl = el.selectFirst("img")?.let { img -> img.lazySrc().orEmpty() })
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/ver/manga/lista?page=$page&filter=$q"), base).select(".manga-card, .thumbnail").mapNotNull { el ->
                val a = el.selectFirst("a[href*='/ver/manga/']") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.absUrl("href"),
                    title    = el.selectFirst(".manga-title, h4")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            manga.copy(
                title       = doc.selectFirst("h1.manga-name, h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".manga-cover img, img.img-thumbnail")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst(".manga-synopsis, p.synopsis")?.text()?.trim(),
                genres      = doc.select(".manga-genres a, .tags a").map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".manga-author, .author")?.text()?.trim(),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            doc.select("ul#chapters-list li a, .chapters a").mapIndexed { i, a ->
                val href = a.absUrl("href")
                val name = a.text().trim().ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = parseChapterNumber(name) ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url), base)
            doc.select(".chapter-content img, #chapter-images img, .viewer img").mapIndexedNotNull { i, img ->
                val url = img.lazySrc().orEmpty().let { absoluteMediaUrl(base, it) }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

// ── MangaLeer (ES) ────────────────────────────────────────────────────────────
@Singleton
class MangaLeerSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "mangaleer"
    override val name     = "MangaLeer 🇪🇸"
    override val language = "es"
    override val homepageUrl get() = base
    private val base  = "https://mangaleer.com"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/manga/?page=$page&m_orderby=views"), base).select("div.page-item-detail").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = el.selectFirst("h3 a, .post-title a")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.let { img -> img.lazySrc().orEmpty() })
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/?s=$q&post_type=wp-manga"), base).select(".c-tabs-item__content").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.absUrl("href"),
                    title = el.selectFirst("h3 a, .post-title a")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("data-src"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            manga.copy(
                title       = doc.selectFirst(".post-title h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".summary_image img")?.attr("data-src") ?: manga.coverUrl,
                description = doc.selectFirst(".summary__content p")?.text()?.trim(),
                genres      = doc.select(".genres-content a").map { it.text().trim() }.filter { it.isNotBlank() },
                author      = doc.selectFirst(".author-content a")?.text()?.trim(),
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            doc.select(".wp-manga-chapter a").mapIndexed { i, a ->
                val href = a.absUrl("href")
                val name = a.text().trim().ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = parseChapterNumber(name) ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }.reversed()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url), base)
            doc.select(".reading-content img, .page-break img").mapIndexedNotNull { i, img ->
                val url = img.lazySrc().orEmpty().trim()
                    .let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

// ── Union Mangás (PT-BR) ──────────────────────────────────────────────────────
@Singleton
class UnionMangasSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "unionmangas"
    override val language = "pt"
    override val name = "Union Mangás 🇧🇷"
    override val homepageUrl get() = base
    private val base  = "https://unionmangas.xyz"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/lista-mangas?page=$page&orderby=views"), base).select(".div-manga, .manga-card").mapNotNull { el ->
                val a    = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = (el.selectFirst(".manga-title, .title, h3, h4")?.text()?.trim()
                        ?: a.attr("title").trim()).ifBlank { return@mapNotNull null },
                    coverUrl = el.selectFirst("img")?.let { img -> img.lazySrc().orEmpty() })
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            Jsoup.parse(get("$base/lista-mangas?search=$q"), base).select(".div-manga, .manga-card").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                SManga(sourceId = id, url = a.absUrl("href"),
                    title    = el.selectFirst(".manga-title, h3")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = el.selectFirst("img")?.attr("src"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            manga.copy(
                title       = doc.selectFirst("h1, .manga-title")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".img-manga img, .capa img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst(".sinopse, .description p")?.text()?.trim(),
                genres      = doc.select(".genres a, .categorias a").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), base)
            doc.select(".list-capitulos a, .chapters a").mapIndexed { i, a ->
                val href = a.absUrl("href")
                val name = a.text().trim().ifBlank { "Capítulo ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = parseChapterNumber(name) ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url), base)
            doc.select(".chapter-images img, .reading-content img").mapIndexedNotNull { i, img ->
                val url = img.lazySrc().orEmpty().let { absoluteMediaUrl(base, it) }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

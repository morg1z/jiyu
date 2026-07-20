package com.haise.jiyu.source.lightnovelworld

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
 * Light Novel World (lightnovelworld.org) - server-rendered custom web
 * (Django + HTMX). Starsi audit z 2026-07-13 tento web oznacil za
 * trvale vypnuty, ale primy curl test z 2026-07-17 potvrdil, ze bezi -
 * bud byl mezitim obnoven, nebo se stary zaznam tykal jine domeny.
 */
@Singleton
class LightNovelWorldSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "lightnovelworld"
    override val name = "Light Novel World"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://lightnovelworld.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.recommendation-card").mapNotNull { el ->
            val href = el.selectFirst("a.card-cover-link")?.attr("href")?.ifBlank { null }
                ?: return@mapNotNull null
            val title = el.selectFirst("h3.card-title")?.text()?.trim() ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$base$it"
            }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/genre-all/?page=$page")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/search/?q=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val description = doc.select("div.summary-content p").joinToString("\n\n") { it.text().trim() }
                .ifBlank { null }
            manga.copy(
                title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("p.novel-author a.author-link")?.text()?.trim(),
                genres = doc.select("div.genre-tags span.genre-tag").map { it.text().trim() },
                description = description,
                status = doc.selectFirst("span.status-badge")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    // Kapitoly jsou na samostatne strance "{novel}/chapters/?page=N" (az
    // desitky stranek u dlouhych novel), odkaz na kazdou kapitolu je jen
    // v onclick atributu karty, ne v <a href>.
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = mutableListOf<SChapter>()
            var page = 1
            while (page < 300) {
                val doc = Jsoup.parse(get("$base${manga.url}chapters/?page=$page"))
                val cards = doc.select("div.chapter-card")
                if (cards.isEmpty()) break
                cards.forEach { chapterFromCard(it, manga.url)?.let(chapters::add) }
                if (doc.selectFirst("a.page-link[title=Next Page]") == null) break
                page++
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    private fun chapterFromCard(card: Element, mangaUrl: String): SChapter? {
        val onclick = card.attr("onclick")
        val href = Regex("""location\.href='([^']+)'""").find(onclick)?.groupValues?.get(1) ?: return null
        val num = card.selectFirst("div.chapter-number")?.text()?.trim()?.toFloatOrNull() ?: 0f
        val title = card.selectFirst("h3.chapter-title")?.text()?.trim()?.ifBlank { null } ?: "Chapter $num"
        val dateText = card.selectFirst("p.chapter-time")?.text()?.trim()
        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = href,
            name = title,
            chapterNumber = num,
            dateUpload = parseRelativeDate(dateText),
        )
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val m = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?""", RegexOption.IGNORE_CASE)
            .find(text) ?: return System.currentTimeMillis()
        val value = m.groupValues[1].toLongOrNull() ?: 1L
        val unit = m.groupValues[2].lowercase()
        val deltaMs = when (unit) {
            "second" -> value * 1_000L
            "minute" -> value * 60_000L
            "hour"   -> value * 3_600_000L
            "day"    -> value * 86_400_000L
            "week"   -> value * 7 * 86_400_000L
            "month"  -> value * 30 * 86_400_000L
            "year"   -> value * 365 * 86_400_000L
            else     -> 0L
        }
        return System.currentTimeMillis() - deltaMs
    }

    // Text kapitoly (novel, ne obrazky) se signalizuje pres imageUrl =
    // "novel://text", stejny vzor jako NovelFullSource/FreeWebNovelSource.
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val container = doc.selectFirst("div.chapter-text") ?: return@withContext emptyList()
            container.select("script, .chapter-ad-container, style").remove()
            val text = container.text().trim()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

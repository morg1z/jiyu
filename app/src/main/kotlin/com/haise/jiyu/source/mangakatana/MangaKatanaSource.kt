package com.haise.jiyu.source.mangakatana

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaKatanaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangakatana"
    override val name = "MangaKatana"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://mangakatana.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.item").mapNotNull { el ->
            val link = el.selectFirst("h3.title a, h3 a") ?: return@mapNotNull null
            val href = toSourcePath(base, link.attr("href"))
            val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val cover = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    // Overeno zive: "/genres" je samostatna stranka s pokrocilym filtrem, jejiz
    // "div.genres div.item" obsahuje pro kazdy zanr checkbox
    // "input[name=include_genre_chk]" (atribut value = slug pro URL) a "span.name"
    // s popiskem (55 zanru celkem). Samotne prochazeni podle jednoho zanru bezi na
    // vlastni archivni ceste "/genre/{slug}" se strankovanim "/genre/{slug}/page/N"
    // (stejna karta "div.item" jako getPopular/search) - zivym porovnanim potvrzeno,
    // ze vysledky se od /latest lisi uz od druhe polozky. Kombinace vice zanru
    // najednou tahle jednoducha cesta nepodporuje (jen jeden slug v URL).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/genres"))
            val tags = doc.select("div.genres div.item").mapNotNull { item ->
                val slug = item.selectFirst("input[name=include_genre_chk]")?.attr("value")?.ifBlank { null }
                    ?: return@mapNotNull null
                val label = item.selectFirst("span.name")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreUrl(slug: String, page: Int): String =
        if (page <= 1) "$base/genre/$slug" else "$base/genre/$slug/page/$page"

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(genreUrl(filter.genres.first(), page)))
            }
            parseList(get("$base/latest/$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseList(get(genreUrl(filter.genres.first(), page)))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?search=$q&search_by=book_name&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val statusText = doc.selectFirst(".d-cell-small.value.status")?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1.heading")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".cover img, .thumb img")?.let {
                    it.lazySrc().orEmpty()
                } ?: manga.coverUrl,
                description = doc.selectFirst(".summary p")?.text()?.trim(),
                genres = doc.select(".genres a").map { it.text() },
                author = doc.selectFirst(".authors a")?.text()?.trim(),
                status = when {
                    statusText.equals("Ongoing", ignoreCase = true) -> "Ongoing"
                    statusText.equals("Completed", ignoreCase = true) -> "Completed"
                    else -> statusText
                },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        // Lokalni instance na kazde volani, ne sdilene pole - SimpleDateFormat.parse() neni
        // thread-safe a getChapterList muze bezet soubezne z vice korutin na te same instanci
        // zdroje (audit nalez).
        val dateFormat = SimpleDateFormat("MMM-dd-yyyy", Locale.ENGLISH)
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val rows = doc.select("div.chapters tr")
            rows.mapIndexedNotNull { i, row ->
                val a = row.selectFirst("div.chapter a") ?: return@mapIndexedNotNull null
                val href = toSourcePath(base, a.attr("href"))
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (rows.size - i).toFloat()
                val dateText = row.selectFirst("div.update_time")?.text()?.trim()
                val date = try { dateText?.let { dateFormat.parse(it)?.time } ?: 0L } catch (e: Exception) { e.rethrowIfControl(); 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = date)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(resolveSourceUrl(base, chapter.url))
            val match = Regex("""var thzq\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?: return@withContext emptyList()
            match.groupValues[1]
                .split(",")
                .map { it.trim().trim('\'') }
                .filter { it.isNotBlank() }
                .mapIndexed { i, url -> Page(i, url, url) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

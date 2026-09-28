package com.haise.jiyu.source.ifreedom

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.novelText
import com.haise.jiyu.util.parseChapterDate
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

/**
 * Generický zdroj pro ruské ranobě weby na WP šabloně ifreedom/bookhamster
 * (ifreedom.su, bookhamster.ru): archiv `/vse-knigi/?bpage={N}` (stránkování
 * `/page/{N}/` web ignoruje a vrací p1 - audit 2026-09-23), série `/ranobe/{slug}/`,
 * žánrový filtr `/vse-knigi/?genre[]={název}`, hledání `?searchname=` (WP `?s=`
 * na těchto webech timeoutuje). Selektory se u webů mírně liší,
 * proto jsou parametrizované v konstruktoru.
 *
 * Ifreedom zamyká nejnovější kapitoly za VIP (`/podpiska/` odkazy) - ty se při
 * parsování seznamu kapitol přeskočí, dostupné jsou jen volné kapitoly.
 */
class IFreedomSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    /** Selektor jedné položky v archivu (ifreedom ".item-book-slide", bookhamster ".one-book-home"). */
    private val itemSelector: String = ".item-book-slide",
    /** Selektor odkazu na sérii uvnitř položky (ifreedom "a.link-book-slide", bookhamster ".title-home a"). */
    private val linkSelector: String = "a.link-book-slide",
    private val coverSelector: String = ".block-book-slide-img img, .img-home img, img",
    private val titleSelector: String = ".block-book-slide-title, .title-home",
    /** Kontejner seznamu kapitol na detailu (ifreedom ".chapterinfo", bookhamster ".li-ranobe"). */
    private val chapterItemSelector: String = ".chapterinfo",
    /** Selektor textu kapitoly (ifreedom ".chapter-content", bookhamster ".entry-content"). */
    private val contentSelector: String = ".chapter-content",
    private val languageOverride: String = "ru",
    private val inGlobalSearch: Boolean = false,
) : MangaSource {

    private val root get() = baseUrl.trimEnd('/')

    override val contentType = "NOVEL"
    override val homepageUrl get() = baseUrl
    override val language get() = languageOverride
    override val includeInGlobalSearch get() = inGlobalSearch
    override val supportsTagFilter get() = true
    override val supportsSortOrder get() = false

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select(itemSelector).mapNotNull { item ->
            val link = item.selectFirst(linkSelector) ?: item.selectFirst("a[href*='/ranobe/']")
                ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = href,
                title = item.selectFirst(titleSelector)?.text()?.trim()
                    ?: link.attr("title").trim().ifBlank { link.text().trim() },
                coverUrl = item.selectFirst(coverSelector)?.lazySrc()
                    ?.let { resolveSourceUrl(baseUrl, it) },
                contentType = "NOVEL",
            )
        }.distinctBy { it.url }

    // Filtr statusu na /vse-knigi/ posila "status[]={rusky label}" - overeno
    // zive, kazda hodnota vraci jinou sadu. Webovky sdili stejny formular.
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed", "hiatus")

    private val statusValues = mapOf(
        "ongoing" to "Перевод активен",
        "completed" to "Произведение завершено",
        "hiatus" to "Перевод приостановлен",
    )

    private fun archiveUrl(page: Int, filter: MangaFilter): String {
        val sb = StringBuilder("$root/vse-knigi/?")
        filter.genres.forEach { sb.append("genre[]=").append(URLEncoder.encode(it, "UTF-8")).append('&') }
        statusValues[filter.status]?.let { sb.append("status[]=").append(URLEncoder.encode(it, "UTF-8")).append('&') }
        if (page > 1) sb.append("bpage=$page")
        return sb.toString().trimEnd('?', '&')
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseListing(Jsoup.parse(get(archiveUrl(page, filter)))) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty() || filter.status != null) {
                return@withContext parseListing(Jsoup.parse(get(archiveUrl(page, filter))))
            }
            // WP "?s=" na techto webech timeoutuje (25s+, audit) - vlastni vyhledavani
            // sablony je GET /vse-knigi/?searchname= a paginuje pres bpage.
            val url = "$root/vse-knigi/?searchname=${URLEncoder.encode(query, "UTF-8")}" + if (page > 1) "&bpage=$page" else ""
            parseListing(Jsoup.parse(get(url)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            // Filtr žánrů na /vse-knigi/ používá parametry genre[]={název} (cyrilice).
            val doc = Jsoup.parse(get("$root/vse-knigi/"))
            val tags = doc.select("a[href*='genre[]='], input[name='genre[]']").mapNotNull { el ->
                val value = (if (el.tagName() == "input") el.attr("value")
                    else el.attr("href").substringAfter("genre[]=", "").substringBefore('&'))
                    .trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = value, label = el.text().trim().ifBlank { value })
            }.distinctBy { it.id }.sortedBy { it.label }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst(".opisanie, .description, .summary-content, meta[property='og:description']")
                    ?.let { if (it.tagName() == "meta") it.attr("content") else it.text().trim() },
                genres = doc.select(".genreslist a, .data-value a[href*='genre[]='], a[href*='genre[]=']")
                    .map { it.text().trim() }.filter { it.isNotBlank() }.distinct(),
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, manga.url)))
            doc.select(chapterItemSelector).mapIndexedNotNull { i, item ->
                val a = (if (item.tagName() == "a") item else item.selectFirst("a")) ?: return@mapIndexedNotNull null
                val href = a.attr("href")
                // VIP kapitoly (ifreedom) odkazují na /podpiska/ - přeskočit, nejsou volně čitelné.
                if (href.isBlank() || "/podpiska/" in href) return@mapIndexedNotNull null
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = a.text().trim().ifBlank { "Глава ${i + 1}" },
                    chapterNumber = Regex("[\\d.,]+").find(a.text())?.value?.replace(',', '.')?.toFloatOrNull()
                        ?: (i + 1).toFloat(),
                    dateUpload = parseChapterDate(
                        item.selectFirst(".timechapter, .chaptdesc .timechapter")?.text(),
                        Locale.forLanguageTag("ru"),
                    ),
                )
            }.distinctBy { it.url }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(baseUrl, chapter.url)))
            val content = doc.selectFirst(contentSelector) ?: return@withContext emptyList()
            content.select("script, style, iframe, ins, .pc-adv, .adsbygoogle, form").remove()
            val text = content.novelText()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

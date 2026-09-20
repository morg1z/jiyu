package com.haise.jiyu.source.wuxiabox

import com.haise.jiyu.util.lazySrc
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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wuxia Box (wuxiabox.com) - server-rendered custom CMS (EmpireCMS-based,
 * podle "/e/..." cest). Seznam kapitol se strankuje pres AJAX fragment
 * endpoint "/e/extend/fy.php?page=N&wjm={slug}", ktery vraci primo HTML
 * (ne JSON) - stejny <ul class="chapter-list"> jako na hlavni strance.
 */
@Singleton
class WuxiaBoxSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "wuxiabox"
    override val name = "Wuxia Box"
    override val contentType: String get() = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://www.wuxiabox.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("li.novel-item").mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("h4.novel-title")?.text()?.trim() ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                it.lazySrc().orEmpty()
            }?.let { resolveSourceUrl(base, it) }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "NOVEL")
        }
    }

    // "/updates/{page}.html" vypisuje karty JEDNOTLIVYCH KAPITOL (odkaz na
    // /novel/{slug}_{cislo}.html), ne mangu samotnou - manga.url pak ukazoval na
    // kapitolu misto na detail, takze getChapterList() se slugem "{slug}_{cislo}"
    // u fy.php vzdy vratilo prazdno (audit 2026-07-27). Spravny katalog vsech
    // titulu je "/list/all/all-onclick-{page}.html" (0-indexovane, razeno podle
    // poctu prokliku = "popularni"), ktery odkazuje primo na /novel/{slug}.html.
    //
    // Stejny endpoint podporuje i zanrovy archiv - misto "all" jde dosadit slug
    // zanru ("/list/{genre}/all-{sort}-{page}.html"), sidebar "Genre / Category"
    // na kazde listovaci strance obsahuje kompletni seznam (~52 zanru, overeno
    // zive). Vice zanru najednou EmpireCMS nekombinuje - pouzije se jen prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/list/all/all-onclick-0.html"))
            val tags = doc.select("#categorylist a[href^=\"/list/\"]").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/list/").substringBefore("/").ifBlank { return@mapNotNull null }
                if (slug == "all") return@mapNotNull null
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // overeno zive: "all-newstime" (razeno dle casu pridani) vraci jine
        // poradi nez "all-onclick" (razeno dle poctu prokliku), stejny vzor strankovani.
        val sort = if (filter.sortBy == "latest") "newstime" else "onclick"
        val genre = filter.genres.firstOrNull() ?: "all"
        try { parseList(get("$base/list/$genre/all-$sort-${page - 1}.html")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Vyhledavani jde pres POST na EmpireCMS endpoint, ktery presmeruje
    // na vysledkovou stranku s vygenerovanym searchid. Pri vybranem zanru se misto
    // toho pouzije zanrovy archiv (stejny vzor jako u Madara-style zdroju).
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext getPopular(page, filter)
        }
        try {
            val body = FormBody.Builder()
                .add("show", "title")
                .add("tempid", "1")
                .add("tbname", "news")
                .add("keyboard", query)
                .build()
            val request = Request.Builder().url("$base/e/search/index.php").post(body).build()
            val html = client.newCall(request).execute().use { it.bodyOrThrow("$base/e/search/index.php") }
            parseList(html)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val status = doc.select("div.header-stats span").firstOrNull {
                it.selectFirst("small")?.text()?.trim().equals("Status", ignoreCase = true)
            }?.selectFirst("strong")?.text()?.trim()

            manga.copy(
                title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: manga.title,
                author = doc.selectFirst("div.author span[itemprop=author]")?.text()?.trim(),
                genres = doc.select("div.categories a.property-item").map { it.text().trim() },
                description = doc.selectFirst("p.description")?.text()?.trim(),
                status = status,
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = manga.url.substringAfterLast("/").removeSuffix(".html")
            val chapters = mutableListOf<SChapter>()
            var page = 0
            while (page < 300) {
                val html = get("$base/e/extend/fy.php?page=$page&wjm=$slug")
                val doc = Jsoup.parse(html, base)
                val rows = doc.select("ul.chapter-list li")
                if (rows.isEmpty()) break
                rows.forEach { li ->
                    val a = li.selectFirst("a") ?: return@forEach
                    val href = a.attr("href")
                    val num = li.attr("data-chapterno").toFloatOrNull() ?: 0f
                    val title = a.selectFirst("strong.chapter-title")?.text()?.trim()?.ifBlank { null }
                        ?: "Chapter $num"
                    val dateText = a.selectFirst("time.chapter-update")?.text()?.trim()
                    chapters.add(
                        SChapter(
                            sourceId = id,
                            mangaUrl = manga.url,
                            url = href,
                            name = title,
                            chapterNumber = num,
                            dateUpload = parseRelativeDate(dateText),
                        )
                    )
                }
                page++
            }
            chapters
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseRelativeDate(text: String?): Long = com.haise.jiyu.util.parseChapterDate(text)


    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            val text = doc.select("div.chapter-content p").joinToString("\n\n") { it.text().trim() }
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

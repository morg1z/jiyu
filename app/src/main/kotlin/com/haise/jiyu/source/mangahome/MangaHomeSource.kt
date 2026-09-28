package com.haise.jiyu.source.mangahome

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.fanfox.JsPacker
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sdílí infrastrukturu s MangaTown/Mangafox (mangahere síť) - obrázky na
 * zjcdn.mangahere.org už mají Referer pokrytý přes hotlinkRefererSuffixes
 * v AppModule.kt. Na rozdíl od MangaTown je čtecí stránka jednostránková
 * (všechny obrázky rovnou v HTML), žádné sekvenční procházení není potřeba.
 */
@Singleton
class MangaHomeSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "mangahome"
    override val name = "MangaHome"
    override val homepageUrl get() = base
    override val supportsTagFilter = true
    private val base = "https://www.mangahome.com"

    @Volatile private var cachedTags: List<FilterTag>? = null

    /**
     * /advsearch má checklist žánrů (`ul.genres li[rel=Label]` s
     * `onclick="clickGenre(this, 'slug');"` - viz advsearch.js). Formulář posílá
     * vybrané sloty jako "ingenres=slug1,slug2" na /search - overeno zive, ze
     * filtruje spravne a lze kombinovat vice zanru najednou (AND).
     */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val tags = try {
            val doc = Jsoup.parse(get("$base/advsearch"))
            doc.select("ul.genres li[rel]").mapNotNull { li ->
                val label = li.attr("rel").trim().ifBlank { return@mapNotNull null }
                val onclick = li.selectFirst("a")?.attr("onclick").orEmpty()
                val slug = Regex("""clickGenre\(this,\s*'([^']+)'\)""").find(onclick)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        if (tags.isNotEmpty()) cachedTags = tags
        tags
    }

    private fun get(url: String, referer: String = base): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", referer)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a.post-cover[href^=/manga/]").mapNotNull { link ->
            val href = link.attr("href")
            val title = link.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    // /search na webu prijima type= (manga|manhwa|manhua), is_completed= (0|1),
    // released= + released_method=eq - vsechny overene zive (ruzne sady,
    // kombinovatelne s ingenres).
    override val supportsStatusFilter: Boolean get() = true
    override val availableStatuses: List<String> get() =
        listOf("ongoing", "completed")
    override val availableComicTypes: List<FilterTag> get() = listOf(
        FilterTag(id = "manga", label = "Manga"),
        FilterTag(id = "manhwa", label = "Manhwa"),
        FilterTag(id = "manhua", label = "Manhua"),
    )
    override val supportsYearFilter: Boolean get() = true

    private val SITE_TYPES = setOf("manga", "manhwa", "manhua")

    private fun searchUrl(page: Int, filter: MangaFilter, query: String = ""): String = buildString {
        append("$base/search?name=").append(URLEncoder.encode(query, "UTF-8")).append("&page=").append(page)
        if (filter.genres.isNotEmpty()) append("&ingenres=").append(filter.genres.joinToString(",")).append("&exgenres=")
        when (filter.status) {
            "completed" -> append("&is_completed=1")
            "ongoing" -> append("&is_completed=0")
        }
        filter.comicTypes.firstOrNull()?.takeIf { it in SITE_TYPES }?.let { append("&type=").append(it) }
        filter.year?.let { append("&released=").append(it).append("&released_method=eq") }
    }

    private fun hasSearchFilters(filter: MangaFilter) =
        filter.genres.isNotEmpty() || filter.status != null ||
            filter.comicTypes.isNotEmpty() || filter.year != null

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Kdyz jsou vybrane zanry/filtry, nema "/directory"/"/latest" zadny param -
        // musime prehodit na /search s ingenres (viz getAvailableTags) - overeno zive.
        if (hasSearchFilters(filter)) {
            return@withContext try { parseList(get(searchUrl(page, filter))) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // "/latest/N.html" ma stejnou kartu (a.post-cover) jako "/directory/N.html",
        // jen jiny zdroj razeni - overeno zive, vraci odlisne tituly.
        val path = if (filter.sortBy == "latest") "/latest/$page.html" else "/directory/$page.html"
        try { parseList(get("$base$path")) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get(searchUrl(page, filter, query)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val statusText = doc.selectFirst("p:contains(Status:)")?.ownText()?.trim()
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("p.hide")?.text()?.trim(),
                author = doc.selectFirst("p:contains(Author(s)) a")?.text()?.trim(),
                genres = doc.select("p:contains(Genre(s)) a").map { it.text() },
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
        val dateFormat = java.text.SimpleDateFormat("MMM dd,yyyy", java.util.Locale.ENGLISH)
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val items = doc.select("ul.detail-chlist li")
            items.mapIndexedNotNull { i, li ->
                // Odkaz muze mit pred /c jeste svazek: /manga/{slug}/v35/c300
                // (drivejsi selektor a[href^={url}/c] tyhle kapitoly preskocil).
                val a = li.selectFirst("a[href*=${manga.url}/]") ?: return@mapIndexedNotNull null
                val href = a.attr("href")
                val text = a.text().trim()
                val num = Regex("""(\d+(?:\.\d+)?)""").find(href.substringAfterLast("/c"))?.value?.toFloatOrNull()
                    ?: (items.size - i).toFloat()
                val dateText = li.selectFirst("span.time")?.text()?.trim()
                val date = try { dateText?.let { dateFormat.parse(it)?.time } ?: 0L } catch (e: Exception) { e.rethrowIfControl(); 0L }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = text.ifBlank { "Chapter $num" },
                    chapterNumber = num, dateUpload = date)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Cteci stranka je JS-driven: <img id="image"> se plni az pres
    // chapterfun.ashx?cid={chapter_id}&page={N} (odpoved = Dean Edwards packer,
    // dekoduje JsPacker). Stejny mechanismus jako FanFox/MangaFox - viz tam.
    // Page.url je "virtualni" chapterfun URL, skutecne image URL se resi az
    // v getImageUrl (lazy, stejne jako webovy reader - 1 request na stranku).
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val readerUrl = resolveSourceUrl(base, chapter.url)
            val html = get(readerUrl)
            val count = Regex("""imagecount\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@withContext emptyList()
            val chapterId = Regex("""chapter_id\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            // chapterBase = adresar ctecky (/manga/{slug}) - chapterfun.ashx
            // staci na libovolne ceste, cid urcuje obsah.
            val chapterBase = readerUrl.trimEnd('/').substringBeforeLast("/")
            (1..count).map { p ->
                Page(index = p - 1, url = "$chapterBase/chapterfun.ashx?cid=$chapterId&page=$p&key=")
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val readerUrl = page.url.substringBefore("/chapterfun.ashx")
            val body = get(page.url, referer = "$readerUrl/")
            val decoded = JsPacker.unpackEval(body)
                ?: throw java.io.IOException("MangaHome: chapterfun odpoved neni packer payload")
            // Rozbalene JS: function dm5imagefun(){var pix="//cdn/...";
            // var pvalue=["/v000.jpg","/v001.jpg"];if(i==0){pvalue[i]="//cdn..."}}
            val basePath = Regex("""if\s*\(\s*i\s*==\s*0\s*\)\s*\{\s*pvalue\[i\]\s*=\s*"([^"]*)"""").find(decoded)?.groupValues?.get(1)
            val firstEntry = Regex("""pvalue\s*=\s*\[\s*"([^"]*)"""").find(decoded)?.groupValues?.get(1)
            if (basePath == null || firstEntry == null) {
                throw java.io.IOException("MangaHome: v rozbalenem JS chybi pvalue/basePath")
            }
            val url = basePath + firstEntry
            return@withContext if (url.startsWith("//")) "https:$url" else url
        } catch (e: java.io.IOException) { throw e }
          catch (e: Exception) { e.rethrowIfControl(); throw java.io.IOException("MangaHome resolver: ${e.message}", e) }
    }
}

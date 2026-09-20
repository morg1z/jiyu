package com.haise.jiyu.source.comic

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
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ComicBookPlus - legální archiv Golden Age (public domain) komiksů.
 * Web běží na vlastním fóru (SMF-like), ne na Madara/WordPressu - proto
 * vlastní parsování dle `?dlid=` id komiksu a jeho `viewer/<hash>/<n>.jpg`
 * stránek (číslováno od 0, počet stran v itemprop="numberOfPages").
 */
@Singleton
class ComicBookPlusSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "comicbookplus"
    override val name = "ComicBookPlus"
    override val supportsSortOrder: Boolean get() = false
    override val contentType = "COMIC"
    override val homepageUrl get() = base
    private val base = "https://comicbookplus.com"

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("div.cbpLline").mapNotNull { row ->
            val link = row.selectFirst("a[itemprop=name]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val dlid = Regex("""dlid=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            SManga(
                sourceId = id,
                url = "/?dlid=$dlid",
                title = link.text().trim(),
                coverUrl = row.selectFirst("img")?.attr("src"),
                contentType = "COMIC",
            )
        }

    // Kategorie ("žánry") jsou vlastní taxonomie webu (`/?cbplus=categories`
    // -> `h2.j > a.ya` odkazy na `/?cbplus={slug}`, overeno zive - 43 znacek,
    // jeden request bez pagovani). Archivni stranka kategorie ale na rozdil
    // od "latestuploads" (jednotlive cisla, `?dlid=`) vypisuje CELE SERIALY
    // (`?cid=`, `div.cbpLline` s `a.ya` odkazem) - proto vlastni
    // parseGenreListing a rozsireny getChapterList (viz nize), ktery pro
    // `?cid=` adresu rozbali seznam jednotlivych cisel ze schema.org
    // "hasPart" radku na strance serialu. Pagovani kategorie NENI stejne
    // jako u "latestuploads" (`_l_s_N`) - pouziva `_l_n_{page-1}` az od
    // stranky 2 (overeno zive: `_l_s_1` vraci "We Could Not Find It",
    // `_l_n_1` spravne vrati stranku 2). Kombinace vice zanru najednou
    // neni podporovana - pouziva se jen prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/?cbplus=categories"))
            val tags = doc.select("h2.j > a.ya[href*=cbplus=]").mapNotNull { a ->
                val slug = Regex("""cbplus=([a-zA-Z0-9_]+)""").find(a.attr("href"))?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun parseGenreListing(doc: Document): List<SManga> =
        doc.select("div.cbpLline").mapNotNull { row ->
            val link = row.selectFirst("a.ya[href*=cid=]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val cid = Regex("""cid=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            SManga(
                sourceId = id,
                url = "/?cid=$cid",
                title = link.text().trim(),
                coverUrl = row.selectFirst("img")?.attr("src"),
                contentType = "COMIC",
            )
        }

    private fun genreUrl(slug: String, page: Int): String {
        val suffix = if (page <= 1) "" else "_l_n_${page - 1}"
        return "$base/?cbplus=$slug$suffix"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            val doc = Jsoup.parse(get("$base/?cbplus=latestuploads_l_s_${(page - 1).coerceAtLeast(0)}"))
            parseListing(doc)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseGenreListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            // ComicBookPlus nemá vlastní fulltext endpoint (jen Google CSE),
            // takže hledáme napříč prvními stránkami "latest uploads".
            val results = mutableListOf<SManga>()
            for (p in 0 until 5) {
                val doc = Jsoup.parse(get("$base/?cbplus=latestuploads_l_s_$p"))
                val items = parseListing(doc)
                if (items.isEmpty()) break
                results += items.filter { it.title.contains(query, ignoreCase = true) }
            }
            results
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            manga.copy(
                description = doc.selectFirst("meta[itemprop=description]")?.attr("content"),
                genres = doc.selectFirst("meta[itemprop=genre]")?.attr("content")
                    ?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?.let { listOf(it) } ?: emptyList(),
                status = "Complete",
                contentType = "COMIC",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    // Genre archiv (viz parseGenreListing) vraci cele serialy (`?cid=`), ne jednotliva
    // cisla - jejich stranka detailu ma seznam cisel jako schema.org "hasPart" radky
    // (`tr[itemprop=hasPart]`, kazdy s `?dlid=` odkazem na skutecnou cetbu, overeno
    // zive). Puvodni chovani (jedna staticka "Read" kapitola primo na `manga.url`)
    // zustava beze zmeny pro `?dlid=` tituly z ostatnich cest (latestuploads/search).
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val cid = Regex("""cid=(\d+)""").find(manga.url)?.groupValues?.get(1)
        if (cid != null) {
            // Lokalni instance na kazde volani, ne sdilene pole - SimpleDateFormat.parse()
            // neni thread-safe a getChapterList muze bezet soubezne z vice korutin (napr.
            // ComicKChapterResolver.searchAndFetchStreaming) na te same instanci zdroje
            // (audit nalez).
            val seriesDateFormat = SimpleDateFormat("yyyy-MM", Locale.US)
            return@withContext try {
                val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
                doc.select("tr[itemprop=hasPart]").mapNotNull { row ->
                    val name = row.selectFirst("span[itemprop=name]")?.text()?.trim()?.ifBlank { null }
                        ?: return@mapNotNull null
                    val href = row.selectFirst("a[itemprop=url]")?.attr("href")?.ifBlank { null }
                        ?: return@mapNotNull null
                    val dlid = Regex("""dlid=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    val num = Regex("""(\d+(?:\.\d+)?)\s*$""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    val dateStr = row.selectFirst("time[itemprop=datePublished]")?.attr("datetime")
                    SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = "/?dlid=$dlid",
                        name = name,
                        chapterNumber = num,
                        dateUpload = dateStr?.let { runCatching { seriesDateFormat.parse(it)?.time }.getOrNull() } ?: 0L,
                    )
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = "Read",
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            val pageCount = doc.selectFirst("span[itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull()
                ?: return@withContext emptyList()
            val thumbUrl = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content") ?: return@withContext emptyList()
            val dir = thumbUrl.substringBeforeLast('/')
            (0 until pageCount).map { i -> Page(i, "$dir/$i.jpg") }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

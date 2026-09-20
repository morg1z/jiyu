package com.haise.jiyu.source.hivetoons

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.absoluteMediaUrl
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HiveToons (hivetoons.org) - nastupce Hive Scans (hivescans.com), ktery
 * mezitim kompletne prepsali (Astro + schema.org microdata misto Madara).
 * Vetsina poli se da spolehlive vytahnout pres itemProp atributy
 * (schema.org/CreativeWork), ktere jsou stabilnejsi nez Tailwind trida.
 *
 * Web nema server-rendered fulltextove hledani (vyhledavaci pole nema
 * `name` atribut, filtruje se jen JS-em na klientovi) - search proto
 * stahne prvni stranku archivu a filtruje nazvy lokalne, stejny vzor jako
 * [com.haise.jiyu.source.hachirumi.HachirumiSource].
 */
@Singleton
class HiveToonsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hivetoons"
    override val name = "HiveToons"
    override val contentType: String get() = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://hivetoons.org"
    // Web bezi na sdilene "vcomics" Astro sablone, ktera browse/genre stranky
    // (hivetoons.org/genre/{slug}) renderuje az na klientovi - misto scrapovani
    // prazdneho HTML skeletu se proto genre filtrovani resi primo pres JSON API
    // backend appky (viz komentar u SourceManager - "hivetoons -> api.hivetoons.org"),
    // ktery si stejny klientsky JS sam vola (endpoint najit reverse-engineeringem
    // JS bundlu /_vcomics/*.js - `${API}/api/genres/${encodeURIComponent(slug)}/posts`).
    private val apiBase = "https://api.hivetoons.org"

    private fun get(url: String): Document {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        val html = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return Jsoup.parse(html)
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { it.bodyOrThrow(url) }
        return JSONObject(body)
    }

    private fun parseList(doc: Document): List<SManga> =
        doc.select("a[href^=\"/series/\"][title]").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = el.attr("title").trim().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img")?.attr("src")?.let { absoluteMediaUrl(base, it) }
            SManga(sourceId = id, url = base + href, title = title, coverUrl = cover, contentType = "MANHWA")
        }
            // Kazda karta ma DVA <a href="/series/..." title="..."> odkazy na stejnou
            // mangu - obalku a nazev pod ni - oba sedi na selektor vyse. Bez deduplikace
            // vznikne v seznamu duplicitni "url", coz spadne LazyVerticalGrid v
            // SourceBrowseScreen ("Key ... was already used") - overeno padem na realnem
            // telefonu. distinctBy nechava PRVNI vyskyt, coz je prave ten s obalkovym
            // <img> (druhy - textovy odkaz na nazev - obalku nema).
            .distinctBy { it.url }

    // Live overeno: /api/genres vraci malou (95 polozek), plochou taxonomii bez
    // vlastniho "slug" pole - klientsky JS si slug odvozuje jako
    // encodeURIComponent(name.toLowerCase()) (viz komentar u apiBase), stejny vzorec
    // pouzivame tady. FilterTag.id proto nese puvodni (nezakodovany) nazev zanru.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val req = Request.Builder().url("$apiBase/api/genres")
                .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                .header("Accept", "application/json")
                .build()
            val body = client.newCall(req).execute().use { it.bodyOrThrow("$apiBase/api/genres") }
            val arr = JSONArray(body)
            val tags = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = name, label = name)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreSlug(name: String): String =
        URLEncoder.encode(name.lowercase(), "UTF-8").replace("+", "%20")

    private fun parseGenrePosts(json: JSONObject): List<SManga> {
        val posts = json.optJSONArray("posts") ?: return emptyList()
        return (0 until posts.length()).mapNotNull { i ->
            val post = posts.optJSONObject(i) ?: return@mapNotNull null
            val slug = post.optString("slug").ifBlank { return@mapNotNull null }
            val title = post.optString("postTitle").trim().ifBlank { return@mapNotNull null }
            val cover = post.optString("featuredImage").ifBlank { null }
            val seriesType = post.optString("seriesType")
            val isNovel = post.optBoolean("isNovel", false)
            val contentType = when {
                isNovel -> "NOVEL"
                seriesType.equals("MANHUA", ignoreCase = true) -> "MANHUA"
                seriesType.equals("MANGA", ignoreCase = true) -> "MANGA"
                else -> "MANHWA"
            }
            SManga(sourceId = id, url = "$base/series/$slug", title = title, coverUrl = cover, contentType = contentType)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseGenrePosts(getJson("$apiBase/api/genres/${genreSlug(filter.genres.first())}/posts?page=$page&perPage=20&filter="))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // Bez koncoveho lomitka web posle 301 na "http://..." (ne https) - Android to
        // spravne odmitne jako cleartext (viz network_security_config.xml) a appka pak
        // tise skonci na prazdnem seznamu. Overeno logem site pripojeni na realnem
        // telefonu. S lomitkem uz web odpovi rovnou 200, zadne presmerovani.
        try {
            // "/latest-updates" ma overene jinou (skutecne cerstvejsi) razeni nez archiv
            // "/series/" - live diff titulu od #2 potvrdil odlisne poradi. Stranka ale
            // nema funkcni ?page= pagination (page 2 vraci identicky obsah jako page 1),
            // proto pro page>1 vracime prazdny seznam misto duplicit.
            if (filter.sortBy == "latest") {
                if (page > 1) emptyList() else parseList(get("$base/latest-updates"))
            } else {
                parseList(get("$base/series/?page=$page"))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                parseGenrePosts(getJson("$apiBase/api/genres/${genreSlug(filter.genres.first())}/posts?page=$page&perPage=20&filter="))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (page > 1) return@withContext emptyList()
        try {
            parseList(get("$base/series/")).filter { it.title.contains(query, ignoreCase = true) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = get(manga.url)
            val status = doc.select("h1").firstOrNull { it.text().trim().equals("Status", ignoreCase = true) }
                ?.parent()?.selectFirst("p")?.text()?.trim()
            manga.copy(
                title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("img[itemprop=image]")?.attr("src")?.let { absoluteMediaUrl(base, it) } ?: manga.coverUrl,
                description = doc.selectFirst("div[itemprop=description]")?.text()?.trim(),
                genres = doc.select("a[itemprop=genre]").map { it.text().trim() }.filter { it.isNotBlank() },
                status = status,
                contentType = "MANHWA",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val relPath = toSourcePath(base, manga.url)
            val doc = get(manga.url)
            doc.select("a[href^=\"$relPath/chapter-\"]").mapNotNull { a ->
                val href = a.attr("href")
                val num = Regex("""/chapter-(\d+(?:\.\d+)?)$""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                href to num
            }.distinctBy { it.first }.map { (href, num) ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = base + href,
                    name = "Chapter ${if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()}",
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            get(chapter.url).select("img[data-reader-page-image]").mapIndexedNotNull { i, img ->
                val url = img.attr("src").let { absoluteMediaUrl(base, it) } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

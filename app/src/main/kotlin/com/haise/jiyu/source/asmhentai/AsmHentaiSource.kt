package com.haise.jiyu.source.asmhentai

import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * asmhentai.com - hentai/doujinshi galerie klon podobny nhentai. Listing
 * (`div.preview_item`) i detail stranka jsou obycejne server-rendrovane
 * HTML bez JS zavislosti. Detail stranka obsahuje skryte inputy
 * `#load_dir`/`#load_id` a text "Pages: N" - z toho jde primo (bez dalsich
 * requestu) sestavit URL kazde plne stranky galerie podle overeneho vzoru
 * `https://images.asmhentai.com/{dir}/{id}/{n}.jpg` (overeno i na oficialnim
 * "/gallery/{id}/{n}/" readeru, ktery stejnou URL pouziva pro <img id="fimg">).
 */
@Singleton
class AsmHentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "asmhentai"
    override val name = "AsmHentai"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://asmhentai.com"
    private val imgBase = "https://images.asmhentai.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("div.preview_item").mapNotNull { item ->
            val link = item.selectFirst("div.image a[href]") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.selectFirst("div.cpt h2.caption")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = link.selectFirst("img")?.attr("data-src")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("//")) "https:$it" else it }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
        }
    }

    // /tags/ vypisuje kompletni seznam znacek webu (odkazy `a.badge.tag` na
    // `/tag/{slug}/`), overeno zive - 120 polozek, jeden request bez pagovani.
    // Archiv `/tag/{slug}/?page=N` pouziva stejny `div.preview_item` markup
    // jako homepage a stejnym zpusobem pagi­nuje (overeno zive: obsah stranky 1
    // i 2 archivu se lisi jak od sebe navzajem, tak od nefiltrovane homepage).
    // Kombinace vice tagu najednou neni podporovana - pouziva se jen prvni.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/tags/"), base)
            val tags = doc.select("a.badge.tag[href^=/tag/]").mapNotNull { a ->
                val slug = a.attr("href").trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = a.ownText().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // "Nejnovější" = úvodní stránka (nejnovější galerie). "Populární" = parametr `sort=popular`, který web
    // umí u archivů (tag, jazyk) - u úvodní stránky ho ignoruje (ověřeno živě), proto se bez tagu bere
    // archiv anglických galerií "/language/english/". Dřív obě záložky vracely totéž.
    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val popular = filter.sortBy != "latest"
            val sort = if (popular) "&sort=popular" else ""
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(get("$base/tag/${filter.genres.first()}/?page=$page$sort"))
            }
            if (popular) parseListing(get("$base/language/english/?page=$page$sort"))
            else parseListing(get("$base/?page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(get("$base/tag/${filter.genres.first()}/?page=$page"))
            }
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseListing(get("$base/search/?q=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun tagSection(doc: Document, label: String): List<String> {
        val h3 = doc.select("div.tags h3").firstOrNull { it.text().trim().trimEnd(':').equals(label, ignoreCase = true) }
            ?: return emptyList()
        val container = h3.parent() ?: return emptyList()
        return container.select("div.tag_list a span.tag").map {
            it.text().replace(Regex("""\s*\([\d,]+\)$"""), "").trim()
        }.filter { it.isNotBlank() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base)
            val title = doc.selectFirst("div.info h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.title
            val cover = doc.selectFirst("div.cover img")?.attr("data-src")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("//")) "https:$it" else it } ?: manga.coverUrl

            val artist = (tagSection(doc, "Artists") + tagSection(doc, "Groups")).firstOrNull()
            val genres = tagSection(doc, "Tags").take(20)
            val parody = tagSection(doc, "Parodies")
            val characters = tagSection(doc, "Characters")
            val language = tagSection(doc, "Languages").firstOrNull()
            val category = tagSection(doc, "Category").firstOrNull()

            val desc = buildString {
                if (parody.isNotEmpty()) append("Parody: ${parody.joinToString(", ")}\n")
                if (characters.isNotEmpty()) append("Characters: ${characters.joinToString(", ")}\n")
                if (!language.isNullOrBlank()) append("Language: $language\n")
                if (!category.isNullOrBlank()) append("Category: $category")
            }.trim()

            manga.copy(
                title = title,
                coverUrl = cover,
                description = desc.takeIf { it.isNotBlank() },
                author = artist,
                artist = artist,
                genres = genres,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun galleryId(mangaUrl: String) = mangaUrl.trim('/').substringAfterLast('/')

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base)
            val gid = galleryId(manga.url)
            val dir = doc.selectFirst("input#load_dir")?.attr("value")?.trim()?.takeIf { it.isNotBlank() }
                ?: Regex("""images\.asmhentai\.com/(\d+)/""").find(doc.html())?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val pages = doc.select("div.pages h3").firstOrNull { it.text().contains("Pages:") }
                ?.text()?.let { Regex("""Pages:\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: return@withContext emptyList()

            listOf(
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "$dir|$gid|$pages",
                    name = manga.title,
                    chapterNumber = 1f,
                    dateUpload = 0L,
                )
            )
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val parts = chapter.url.split("|")
            if (parts.size != 3) return@withContext emptyList()
            val (dir, gid, pagesStr) = parts
            val pages = pagesStr.toIntOrNull() ?: return@withContext emptyList()
            (1..pages).map { n ->
                val url = "$imgBase/$dir/$gid/$n.jpg"
                Page(n - 1, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

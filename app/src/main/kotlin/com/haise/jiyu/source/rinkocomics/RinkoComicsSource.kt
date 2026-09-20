package com.haise.jiyu.source.rinkocomics

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.normalizeContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * rinkocomics.com - bespoke WordPress motiv "ComicWorld" (custom post type "comic"),
 * NE Madara/MangaThemesia (overeno zive - zadny "Powered by Madara" ani wp-manga
 * struktura, vlastni "/comic/" a "/chapter/" cesty).
 *
 * Katalog "/comic/" (+ "/comic/page/N/" strankovani) je PLNE server-rendered HTML
 * s formularem `form.ac-filters-form` - fulltextove hledani (`s=`) i zanrovy filtr
 * (`genres[]=slug`, vice hodnot najednou) fungujou server-side (overeno zive - ruzne
 * dotazy/zanry vraci ruzne "X comics found" pocty i ruzne sady karet), oboji jde
 * kombinovat s "/page/N/" strankovanim zaroven. Karty `article.ac-card` maji primo
 * `<img>` s realnou cover URL a `div.ac-genres a` s celym seznamem zanru bez
 * dalsiho requestu. Zadny "popularity" sort na webu neni (jen newest/oldest/az/za) -
 * appka proto vzdy pouziva vychozi razeni webu (newest first).
 *
 * Detail mangy je take staticky HTML - "Label"/hodnota dvojice v `div.comic-graph`
 * (typ obsahu, napr. "Manhwa") a `div.statistics` (Status), plne zanry znovu
 * v `div.comic-genres .genres .genre`, popis v `div.comic-synopsis` (overeno zive).
 *
 * Prvnich 10 kapitol je primo v detailu (`li.chapter[data-permalink]`, "nejnovejsi
 * napřed" - vcetne zamcenych za mincemi, ty maji navic tridu "locked-chapter" a
 * `data-reason="login_required"`, ale porad se daji zaradit do seznamu, jen jejich
 * getPageList vrati prazdno). Dalsi kapitoly (11.+) se dotahujou přes standardni WP
 * `admin-ajax.php` POST (`action=load_more_chapters`, `comic_id` z
 * `#loadMoreChaptersBtn[data-comic-id]`, `offset` po 10) - vyzaduje WP nonce, ktery
 * appka pokazde cerstve vytahne z JS promenne "comicworld_ajax" primo na detailni
 * strance (nonce je pro anonymni navstevniky sdileny a rotuje cca po 12-24h, proto
 * se nesmi hardcodovat) - overeno zive primo curlem s takhle ziskanym nonce, vraci
 * JSON `{"success":true,"data":{"html":"<li class=\"chapter\" ...>"}}` se stejnou
 * `<li>` strukturou jako na strance.
 *
 * Stranky kapitoly jsou standardni lazysizes vzor (`img.chapter-image[data-src]`,
 * primy CDN "cdn.rinkocomics.com" bez hotlink ochrany, overeno zive). Zamcene
 * kapitoly nemaji v HTML zadny `img.chapter-image` (jen placeholder/paywall UI) -
 * getPageList tak pro ne prirozene vrati prazdny seznam bez extra osetreni.
 */
@Singleton
class RinkoComicsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "rinkocomics"
    override val name = "Rinko Comics"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://rinkocomics.com"

    override val supportsTagFilter: Boolean get() = true

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/comic/"))
            doc.select("input.ac-genre-checkbox[value]").mapNotNull { input ->
                val slug = input.attr("value").trim().ifBlank { return@mapNotNull null }
                val label = input.nextElementSibling()?.nextElementSibling()?.text()?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun catalogUrl(page: Int, query: String, genres: List<String>): String {
        val path = if (page > 1) "$base/comic/page/$page/" else "$base/comic/"
        val params = mutableListOf("post_type=comic")
        if (query.isNotBlank()) params += "s=" + URLEncoder.encode(query, "UTF-8")
        genres.forEach { params += "genres%5B%5D=" + URLEncoder.encode(it, "UTF-8") }
        return "$path?" + params.joinToString("&")
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("article.ac-card").mapNotNull { card ->
            val href = card.selectFirst("a.ac-thumb")?.attr("href")?.ifBlank { null }
                ?: return@mapNotNull null
            val title = card.selectFirst("h2.ac-title a")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            val genres = card.select("div.ac-genres a").map { it.text().trim() }.filter { it.isNotBlank() }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, genres = genres)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get(catalogUrl(page, "", filter.genres)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get(catalogUrl(page, query, filter.genres)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun statValue(doc: Document, label: String): String? =
        doc.select("span").firstOrNull { it.text().trim().equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val typeText = doc.select("div.comic-graph span").map { it.text().trim() }
                .firstOrNull { it.equals("Manga", true) || it.equals("Manhwa", true) || it.equals("Manhua", true) || it.equals("Novel", true) }
            val genres = doc.select("div.comic-genres .genres .genre").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("div.comic-synopsis")?.text()?.trim()?.ifBlank { null },
                genres = genres.ifEmpty { manga.genres },
                status = statValue(doc, "Status")?.lowercase(),
                contentType = normalizeContentType(typeText, default = "MANHWA"),
                alternateTitles = doc.select("span.alt-title").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun chapterFromElement(el: org.jsoup.nodes.Element, mangaUrl: String): SChapter? {
        val permalink = el.attr("data-permalink").trim().ifBlank { return null }
        val title = el.attr("data-title").trim().ifBlank { null } ?: "Chapter"
        val num = Regex("""chapter-([\d.]+)/?$""").find(permalink)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return null
        return SChapter(sourceId = id, mangaUrl = mangaUrl, url = permalink, name = title, chapterNumber = num, dateUpload = 0L)
    }

    // Stranka ma VICE ruznych WP nonce promennych pro ruzne AJAX akce (chapter-modal,
    // notifikace, auth, ...) - regex proto musi cilit primo na "comicworld_ajax" (ta,
    // kterou pouziva loadmore.js pro "load_more_chapters"), ne prvni "nonce" v HTML.
    private fun nonceOf(html: String): String? =
        Regex("""comicworld_ajax\s*=\s*\{[^}]*"nonce":"([a-f0-9]+)"""").find(html)?.groupValues?.get(1)

    private fun loadMoreChapters(nonce: String, comicId: String, offset: Int): String {
        val body = FormBody.Builder()
            .add("action", "load_more_chapters")
            .add("nonce", nonce)
            .add("comic_id", comicId)
            .add("offset", offset.toString())
            .build()
        val req = Request.Builder().url("$base/wp-admin/admin-ajax.php")
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .post(body)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow("$base/wp-admin/admin-ajax.php") }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val doc = Jsoup.parse(html)
            val chapters = mutableListOf<SChapter>()
            doc.select("li.chapter[data-permalink]").forEach { el -> chapterFromElement(el, manga.url)?.let { chapters += it } }

            val comicId = doc.selectFirst("#loadMoreChaptersBtn")?.attr("data-comic-id")?.ifBlank { null }
            val nonce = nonceOf(html)
            if (comicId != null && nonce != null) {
                var offset = 10
                while (offset <= 2000) {
                    val json = JSONObject(loadMoreChapters(nonce, comicId, offset))
                    if (!json.optBoolean("success")) break
                    val fragment = json.optJSONObject("data")?.optString("html").orEmpty()
                    if (fragment.isBlank()) break
                    val added = Jsoup.parse(fragment).select("li.chapter[data-permalink]")
                    if (added.isEmpty()) break
                    added.forEach { el -> chapterFromElement(el, manga.url)?.let { chapters += it } }
                    offset += 10
                }
            }
            chapters.distinctBy { it.chapterNumber }.sortedByDescending { it.chapterNumber }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select("img.chapter-image[data-src]").mapIndexedNotNull { i, img ->
                val src = img.attr("data-src").trim().ifBlank { return@mapIndexedNotNull null }
                Page(i, src, src)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

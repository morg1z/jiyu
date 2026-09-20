package com.haise.jiyu.source.novelhall

import com.haise.jiyu.util.resolveSourceUrl
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Novelhall (novelhall.com) - adult/mixed zdroj (viz audit z 2026-07-17:
 * vetsina "Recommend" titulu na homepage je explicitni NTR/incest obsah).
 * Pridano az na vyslovne uzivatelovo prani jako soucast "adult davky" -
 * neaplikuje se zadny content-rating filtr, protoze web zadny neposkytuje.
 *
 * Server-rendered custom web. Kapitoly jsou kompletne vypsane primo na
 * detailni strance (zadne strankovani), text kapitoly pouziva <br> misto
 * <p> pro oddeleni odstavcu, proto vlastni html->text prevod misto
 * proste Element.text() (ktere by <br> ignorovalo a slepilo odstavce
 * dohromady).
 */
@Singleton
class NovelHallSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "novelhall"
    override val name = "Novelhall"
    override val contentType: String get() = "NOVEL"
    override val isAdult = true
    override val homepageUrl get() = base
    private val base = "https://www.novelhall.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // Navigacni "dropdown-menu" (pritomne na kazde strance vc. homepage) obsahuje
    // kompletni seznam zanru jako odkazy /genre/{slug}/. Kazda zanrova archivni
    // stranka ma vlastni sekci "{Genre} Popular Recommendation" (div.section1) s
    // jinym seznamem knih nez ostatni zanry - overeno zive (action3 vs mystery maji
    // odlisne tituly uz na pozici #2). Tahle sekce ale nema strankovani (page-nav je
    // prazdny <div>), takze zanrovy filtr podporuje jen page 1, stejne jako
    // /search-keyword-*.html vysledky.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/"))
            val tags = doc.select("ul.dropdown-menu a[href^=/genre/]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = href.removePrefix("/genre/").removeSuffix("/").ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = slug, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun genreUrl(slug: String) = "$base/genre/$slug/"

    private fun parseGenreList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        val section = doc.selectFirst("div.section1 ul") ?: return emptyList()
        return section.select("> li").mapNotNull { li ->
            val h2a = li.selectFirst("div.book-info h2 a") ?: return@mapNotNull null
            val href = h2a.attr("href").ifBlank { return@mapNotNull null }
            val title = h2a.text().trim().ifBlank { return@mapNotNull null }
            val cover = li.selectFirst("div.book-img img")?.attr("src")?.let { absoluteMediaUrl(base, it) }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "NOVEL")
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val genre = filter.genres.firstOrNull()
            if (genre != null) {
                if (page > 1) return@withContext emptyList()
                return@withContext parseGenreList(get(genreUrl(genre)))
            }
            // overeno zive: puvodne se pro "popularni" pouzival jen lastupdate.html
            // (= "nejnovejsi"), pritom /ranking.html ma jine (skutecne popularitni)
            // razeni a stejny strankovaci vzor "-{page}.html".
            val slug = if (filter.sortBy == "latest") "lastupdate" else "ranking"
            val url = if (page <= 1) "$base/$slug.html" else "$base/$slug-$page.html"
            val doc = Jsoup.parse(get(url))
            doc.select("table tr").mapNotNull { row ->
                val link = row.selectFirst("td.w70 a") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val title = link.text().trim().ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href, title = title, coverUrl = null, contentType = "NOVEL")
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                if (page > 1) return@withContext emptyList()
                return@withContext parseGenreList(get(genreUrl(filter.genres.first())))
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search-keyword-$q.html"))
            doc.select("h4.search-title").mapNotNull { h4 ->
                val link = h4.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val title = link.text().trim().ifBlank { return@mapNotNull null }
                val cover = h4.parent()?.parent()?.selectFirst("img")?.attr("src")?.let { absoluteMediaUrl(base, it) }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "NOVEL")
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val bookInfo = doc.selectFirst("div.book-info")
            val tags = bookInfo?.select("div.booktag span.blue").orEmpty()
            // ownText() (ne text()) protoze span.blue u Author obsahuje
            // skryty <p style="display:none"> s hit-counter cislem jako
            // vnoreny element - Jsoup ho na rozdil od prohlizece
            // nerespektuje a .text() by ho zahrnul do vysledku.
            val author = tags.firstOrNull { it.ownText().contains("Author") }
                ?.ownText()?.substringAfter("：")?.trim()
            val status = tags.firstOrNull { it.ownText().contains("Status") }
                ?.ownText()?.substringAfter("：")?.trim()
            val description = bookInfo?.selectFirst("span.js-close-wrap")?.text()
                ?.removeSuffix("back<<")?.trim()
            val genres = bookInfo?.select("div.booktag a.red")?.map { it.text().trim() } ?: emptyList()
            val cover = bookInfo?.selectFirst("img")?.attr("src")?.let { absoluteMediaUrl(base, it) }

            manga.copy(
                title = bookInfo?.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl = cover ?: manga.coverUrl,
                author = author,
                status = status,
                description = description,
                genres = genres,
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            doc.select("div.book-catalog li a").mapIndexed { i, a ->
                val href = a.attr("href")
                val title = a.text().trim().ifBlank { "Chapter ${i + 1}" }
                val num = Regex("""\d+(?:\.\d+)?""").find(title)?.value?.toFloatOrNull() ?: (i + 1).toFloat()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = title,
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Element.text() ignoruje <br> (nevklada zalomeni), takze odstavce
    // oddelene jen <br><br> misto <p> by se slepily do jedne zdi textu -
    // proto rucni prochazeni uzlu s prevodem <br> na "\n".
    private fun htmlToText(el: Element): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    if (node.tagName() == "br") sb.append("\n") else node.childNodes().forEach(::walk)
                }
            }
        }
        el.childNodes().forEach(::walk)
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, chapter.url)))
            val container = doc.selectFirst("div#htmlContent") ?: return@withContext emptyList()
            val text = htmlToText(container)
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

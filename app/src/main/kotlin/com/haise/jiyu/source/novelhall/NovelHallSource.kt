package com.haise.jiyu.source.novelhall

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
    override val homepageUrl get() = base
    private val base = "https://www.novelhall.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/lastupdate.html" else "$base/lastupdate-$page.html"
            val doc = Jsoup.parse(get(url))
            doc.select("table tr").mapNotNull { row ->
                val link = row.selectFirst("td.w70 a") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val title = link.text().trim().ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href, title = title, coverUrl = null)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search-keyword-$q.html"))
            doc.select("h4.search-title").mapNotNull { h4 ->
                val link = h4.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                val title = link.text().trim().ifBlank { return@mapNotNull null }
                val cover = h4.parent()?.parent()?.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
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
            val cover = bookInfo?.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }

            manga.copy(
                title = bookInfo?.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl = cover ?: manga.coverUrl,
                author = author,
                status = status,
                description = description,
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
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
        } catch (_: Exception) { emptyList() }
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
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val container = doc.selectFirst("div#htmlContent") ?: return@withContext emptyList()
            val text = htmlToText(container)
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

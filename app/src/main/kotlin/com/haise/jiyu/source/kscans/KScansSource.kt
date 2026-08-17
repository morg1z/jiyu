package com.haise.jiyu.source.kscans

import com.haise.jiyu.source.bodyOrThrow

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
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * kscans.xyz - vlastni (bespoke) motiv, jen pro ROMANY (AiMTL - strojove prelozene
 * fantasy/light novely), zadny obrazkovy obsah. Overeno zive (PowerShell):
 * - katalog: cely seznam je na jedne strance ("/" nebo "/popular"), zadna strankovace.
 * - hledani: POST na "/search" s form polem "q" (ne GET query string).
 * - detail i vysledky hledani pouzivaji stejnou kartu `a.novel-item[href]`, kde href je
 *   jen ciselne ID (napr. "62"), potreba resolvovat pres absUrl na spravne baseUri.
 * - seznam kapitol je cely na detailu (zadna strankovace), radek `a.chapter-link` s
 *   `data-number` (cislo kapitoly primo v atributu).
 * - text kapitoly je v `div.chapter-text`, odstavce oddelene jen `<br>` (ne `<p>`) -
 *   pouzit rucni prevod <br> -> "\n" (stejny vzor jako NovelHallSource).
 */
@Singleton
class KScansSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "kscans"
    override val name = "kScans"
    override val homepageUrl get() = base
    private val base = "https://kscans.xyz"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun postSearch(query: String): String {
        val form = FormBody.Builder().add("q", query).build()
        val req = Request.Builder().url("$base/search")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .post(form)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow("$base/search") }
    }

    private fun parseNovelList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("a.novel-item").mapNotNull { card ->
            val href = card.absUrl("href").ifBlank { return@mapNotNull null }
            val titleEl = card.selectFirst("h3.ni-title, h3.novel-title") ?: return@mapNotNull null
            titleEl.select("span.trending-badge").remove()
            val title = titleEl.text().trim().ifBlank { return@mapNotNull null }
            val cover = card.selectFirst("img")?.absUrl("src")?.ifBlank { null }
            val genres = card.select("span.category-tag").map { it.text().trim() }.filter { it.isNotBlank() }
            val status = card.selectFirst("span.novel-status-badge")?.text()?.trim()?.lowercase()
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, genres = genres, status = status, contentType = "NOVEL")
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            parseNovelList(get("$base/popular"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            parseNovelList(postSearch(query))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            val description = doc.selectFirst("div.nd-description")?.let { htmlToText(it) }?.ifBlank { null }
            val author = doc.selectFirst("div.nd-author")?.text()?.trim()?.removePrefix("By ")?.ifBlank { null }
            val pills = doc.selectFirst("div.nd-pills")
            val genres = pills?.select("span.category-tag")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: manga.genres
            val status = pills?.selectFirst("span.novel-status-badge")?.text()?.trim()?.lowercase() ?: manga.status
            manga.copy(
                title = doc.selectFirst("h1.nd-title")?.text()?.trim() ?: manga.title,
                description = description,
                author = author,
                genres = genres,
                status = status,
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            doc.select("a.chapter-link[href]").mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                val num = a.attr("data-number").toFloatOrNull() ?: return@mapNotNull null
                val name = a.selectFirst("span.cl-text")?.text()?.trim()?.ifBlank { null } ?: "Chapter $num"
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    // Element.text() ignoruje <br> (nevklada zalomeni), takze odstavce oddelene jen
    // <br><br> misto <p> by se slepily do jedne zdi textu - proto rucni prochazeni
    // uzlu s prevodem <br> na "\n" (stejny vzor jako NovelHallSource).
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
            val doc = Jsoup.parse(get(chapter.url), chapter.url)
            val contentEl = doc.selectFirst("div.chapter-text") ?: return@withContext emptyList()
            contentEl.select("p.chapter-title").remove()
            val text = htmlToText(contentEl)
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

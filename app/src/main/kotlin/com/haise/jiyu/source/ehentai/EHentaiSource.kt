package com.haise.jiyu.source.ehentai

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * e-hentai.org - nejvetsi anglicky psany hentai/doujinshi katalog. Vychozi
 * (necookie) listing pouziva "Compact" tabulkovy vzhled (`table.itg`),
 * kazdy radek je jedna galerie s odkazem `/g/{id}/{hash}/`. Detail stranky
 * maji miniatury galerie stránkovane po 20 (`?p=0,1,2...`, viz text
 * "Showing 1 - N of X images"), kazda miniatura odkazuje na "reader" URL
 * `/s/{pagehash}/{gid}-{n}`, ktera teprve po nacteni obsahuje primy
 * `<img id="img">` odkaz na plnou stranku - proto getPageList jen sesbira
 * reader URL a getImageUrl kazdou zvlast dotahuje (stejny princip jako
 * oficialni e-hentai web reader).
 */
@Singleton
class EHentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "ehentai"
    override val name = "E-Hentai"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://e-hentai.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("table.itg tr").mapNotNull { row ->
            val link = row.selectFirst("td.glname a[href]") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = link.selectFirst("div.glink")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = row.selectFirst("div.glthumb img")
            val cover = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
        }
    }

    // e-hentai nema maly zanrovy strom - jeho "tagy" (namespace-based female:/male:/
    // parody:/character:/group:/artist:/other:) jsou stovky tisic unikatnich hodnot
    // (stejny rad velikosti jako Hitomi/nhentai) bez lehkeho endpointu na jejich vypis.
    // Ma ale mensi hrubsi "Category" system - 10 typu obsahu (Doujinshi, Manga,
    // Artist CG, ...) zobrazenych jako checkboxy primo na homepage - to pouzivame
    // jako nase FilterTag. Web kombinuje f_cats jako bitmasku kategorii KE SKRYTI
    // (ne k zobrazeni), takze pro "zobraz jen tuto kategorii" se posle soucet vsech
    // OSTATNICH hodnot (1023 = soucet vsech deseti bitu).
    private object Categories {
        const val ALL_MASK = 1023
    }

    @Volatile private var cachedTags: List<FilterTag>? = null

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/"))
            val tags = doc.select("div.cs[id^=cat_]").mapNotNull { div ->
                val value = div.attr("id").removePrefix("cat_").toIntOrNull() ?: return@mapNotNull null
                val label = div.text().trim().ifBlank { return@mapNotNull null }
                FilterTag(id = value.toString(), label = label)
            }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    /** Vraci "f_cats=N" pro vybrany filter, nebo null kdyz zadny tag neni vybran. */
    private fun catsFilterValue(filter: MangaFilter): String? {
        val selected = filter.genres.mapNotNull { it.toIntOrNull() }.sum()
        if (selected <= 0) return null
        val excludeMask = Categories.ALL_MASK - selected
        return "f_cats=$excludeMask"
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Bez parametru web řadí čistě chronologicky (nejnovější nahrání) - "Populární"
        // tab potřebuje samostatnou "/popular" stránku (ověřeno živě, jiný obsah).
        val cats = catsFilterValue(filter)
        val url = if (filter.sortBy == "popular") {
            "$base/popular" + if (cats != null) "?$cats" else ""
        } else {
            "$base/?page=${page - 1}" + if (cats != null) "&$cats" else ""
        }
        try { parseListing(get(url)) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val cats = catsFilterValue(filter)
            val url = "$base/?f_search=$q&page=${page - 1}" + if (cats != null) "&$cats" else ""
            parseListing(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url), manga.url)
            val title = doc.selectFirst("h1#gn")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.title
            val coverStyle = doc.selectFirst("#gd1 div")?.attr("style").orEmpty()
            val cover = Regex("""url\(([^)]+)\)""").find(coverStyle)?.groupValues?.get(1) ?: manga.coverUrl
            val category = doc.selectFirst("#gdc .cs")?.text()?.trim()

            val byCategory = doc.select("#taglist table tr").associate { tr ->
                val cat = tr.selectFirst("td.tc")?.text()?.trim()?.removeSuffix(":").orEmpty()
                val tags = tr.select("a[id^=ta_]").map { it.text().trim() }.filter { it.isNotBlank() }
                cat to tags
            }
            val artist = byCategory["artist"]?.firstOrNull()
            val genres = (byCategory["female"].orEmpty() + byCategory["male"].orEmpty() +
                byCategory["mixed"].orEmpty() + byCategory["other"].orEmpty()).take(20)
            val parody = byCategory["parody"].orEmpty()
            val language = doc.select("#gdd table tr").firstOrNull {
                it.selectFirst("td.gdt1")?.text()?.trim() == "Language:"
            }?.selectFirst("td.gdt2")?.text()?.trim()

            val desc = buildString {
                if (!category.isNullOrBlank()) append("Category: $category\n")
                if (parody.isNotEmpty()) append("Parody: ${parody.joinToString(", ")}\n")
                if (!language.isNullOrBlank()) append("Language: $language")
            }.trim()

            manga.copy(
                title = title,
                coverUrl = cover,
                description = desc.takeIf { it.isNotBlank() },
                author = artist,
                artist = artist,
                genres = genres,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    private fun parseTotalImages(doc: Document): Int {
        val text = doc.selectFirst("p.gpc")?.text().orEmpty()
        return Regex("""of\s+([\d,]+)\s+images""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
    }

    private fun parseReaderLinks(doc: Document): List<String> =
        doc.select("#gdt a[href]").map { it.attr("href") }.filter { it.isNotBlank() }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val firstDoc = Jsoup.parse(get("${chapter.url}?p=0"), chapter.url)
            val total = parseTotalImages(firstDoc)
            val pageCount = if (total > 0) ceil(total / 20.0).toInt() else 1

            val allLinks = if (pageCount <= 1) {
                parseReaderLinks(firstDoc)
            } else {
                coroutineScope {
                    val rest = (1 until pageCount).map { p ->
                        async {
                            try { parseReaderLinks(Jsoup.parse(get("${chapter.url}?p=$p"), chapter.url)) }
                            catch (_: Exception) { emptyList() }
                        }
                    }.map { it.await() }
                    parseReaderLinks(firstDoc) + rest.flatten()
                }
            }

            allLinks.mapIndexed { i, url -> Page(i, url) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(page.url), page.url)
            doc.selectFirst("img#img")?.attr("src")?.takeIf { it.isNotBlank() } ?: page.url
        } catch (_: Exception) { page.url }
    }
}

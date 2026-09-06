package com.haise.jiyu.source.nextseries

import com.haise.jiyu.source.bodyOrThrow

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
import java.net.URLEncoder

/**
 * Genericky zdroj pro sdilenou Next.js sablonu s cestami "/series/comic/{slug}" -
 * potvrzeno zive na Drake Scans (drakecomic.net), Kayn Scans (kaynscans.com) a
 * DivaScans (divascans.org), identicka struktura na vsech trech (stejny titulek
 * stranky "Read Novels, Manhwa & Comics Online Free", stejne CSS tridy).
 *
 * Vypis "/series" (katalog) i vysledky "/series?search=..." pouzivaji stejnou kartu
 * `[role=gridcell] a[href^=/series/comic/]` s titulkem v `img[alt]` (overeno zive -
 * "search" parametr funkcni, ruzne dotazy vraci ruzne sady). Obalka je titulni cislo
 * strankovana "?page=N". Obalkovy obrazek NENI potreba parsovat z <img> (ten jde pres
 * "/_next/image" proxy) - konstruuje se primo jako "/uploads/series/{slug}/cover.webp"
 * (overeno zive, sedi na vsech testovanych titulech).
 *
 * Detail mangy je server-rendered s daty primo v HTML textu (Next.js RSC payload) -
 * "genre":[...] a "description":"..." JSON pole (po jednom vyskytu na strance, bezpecne
 * parsovatelne regexem), "Status" je label+hodnota pres sourozeneho <span>/<div>.
 *
 * Seznam kapitol je KOMPLETNI primo v HTML detailu (`a[href*=/chapter/]`, cislovano
 * "/series/comic/{slug}/chapter/{n}") - overeno zive na 100+ kapitolovem titulu, zadne
 * strankovani/mezery. Stranky kapitoly NEJSOU v <img> tazich (jen prvni se vyrenderuje
 * naplno, zbytek je lazy) - vsechny obrazky "/uploads/series/{slug}/c{cislo}/p{poradi}.webp"
 * jsou ale primo v RSC textu stranky, takze appka je jen vytahne regexem (overeno zive -
 * poradi v textu odpovida poradi stranek).
 *
 * Zadny spolehlivy zdrojovy seznam zanru (pro tag-filter) neni nikde na webu k nalezeni
 * staticky (zanrove chipy na detailu jsou bez viditelneho textu v odkazu, katalogova
 * strana nema zadny filtr formular v HTML) - supportsTagFilter proto zustava vypnuty.
 */
class NextSeriesSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    override val contentType: String = "MANHWA",
) : MangaSource {
    override val homepageUrl get() = baseUrl
    private val base get() = baseUrl.trimEnd('/')

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private val slugFromHref = Regex("""^/series/comic/([a-zA-Z0-9-]+)""")

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=/series/comic/]").mapNotNull { a ->
            val href = a.attr("href")
            val slug = slugFromHref.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = a.selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            SManga(
                sourceId = id, url = "/series/comic/$slug", title = title,
                coverUrl = "$base/uploads/series/$slug/cover.webp",
            )
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get("$base/series?page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/series?search=$q&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    private val genreRegex = Regex(""""genre":\[([^]]*)\]""")
    private val descriptionRegex = Regex(""""description":"((?:[^"\\]|\\.)*)"""")

    private fun statValue(doc: Document, label: String): String? =
        doc.select("span").firstOrNull { it.text().trim().equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()?.ifBlank { null }

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manga" -> "MANGA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANHWA"
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${manga.url}")
            val doc = Jsoup.parse(html)
            val genres = genreRegex.find(html)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotBlank() } ?: manga.genres
            val description = descriptionRegex.find(html)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim()?.ifBlank { null }
            manga.copy(
                title = doc.select("h1").firstOrNull { it.className() != "noscript-title" }
                    ?.text()?.trim() ?: manga.title,
                description = description,
                genres = genres,
                status = statValue(doc, "Status")?.lowercase(),
                contentType = normalizeContentType(statValue(doc, "Type")),
            )
        } catch (_: Exception) { manga }
    }

    private val chapterUrlRegex = Regex("""^/series/comic/[a-zA-Z0-9-]+/chapter/(\d+(?:\.\d+)?)""")

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("a[href*=/chapter/]").mapNotNull { a ->
                val href = a.attr("href")
                val num = chapterUrlRegex.find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = "Chapter $num",
                    chapterNumber = num, dateUpload = 0L)
            }.distinctBy { it.chapterNumber }.sortedByDescending { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    private val pageImageRegex = Regex("""/uploads/series/[a-zA-Z0-9-]+/c[a-zA-Z0-9-]+/p\d+\.webp""")

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            pageImageRegex.findAll(html).map { it.value }.distinct().mapIndexed { i, path ->
                val url = "$base$path"
                Page(i, url, url)
            }.toList()
        } catch (_: Exception) { emptyList() }
    }
}

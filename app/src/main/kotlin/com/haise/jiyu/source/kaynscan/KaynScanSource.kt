package com.haise.jiyu.source.kaynscan

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
import org.jsoup.parser.Parser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * kaynscan.org - NENI Madara ani MangaThemesia. Beží na Astro s "islands" architekturou
 * (build cesta `/_vcomics/...`) - katalog i detail se renderuji server-side, ale data pro
 * hydrataci JS komponent jsou schovana v `props="..."` atributu `<astro-island>` tagu jako
 * HTML-entity-escapovany JSON ve zvlastnim tvaru `["typ", hodnota]` (kazde pole je pár).
 * Misto psani plneho dekoderu tohoto formatu appka cilene regexuje jen pole, ktera
 * potrebuje (slug/postTitle/featuredImage/seriesType pro seznam, id/number/slug/title/
 * createdAt pro kapitoly) - overeno zive (PowerShell), viz project memory
 * "project_jiyu_..." poznamky k teto davce zdroju.
 *
 * Vyhledavani NEMA funkcni server-side filtr (`/series?q=...`/`?search=...` vraci vzdy
 * stejny nefiltrovany seznam - overeno zive) - misto toho appka stahne nekolik prvnich
 * stranek katalogu a filtruje podle titulku sama (best-effort, ne kompletni katalog).
 *
 * Obrazky stranek kapitoly NEJSOU v `props` blobu, ale primo jako obycejne absolutni URL
 * (`<link rel="preload">` a nasledne `<img>`) na CDN `storage.kaynscan.org/.../page-NNNN...`
 * - jednoduchy regex na cele URL v poradi vyskytu v dokumentu staci, zadne parsovani JSON.
 */
@Singleton
class KaynScanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "kaynscan"
    override val name = "Kayn Scan"
    override val homepageUrl get() = base
    private val base = "https://kaynscan.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun unescape(s: String): String = Parser.unescapeEntities(s, false)

    private val listItemRegex = Regex(
        """&quot;id&quot;:\[0,\d+],&quot;slug&quot;:\[0,&quot;(.*?)&quot;],&quot;postTitle&quot;:\[0,&quot;(.*?)&quot;],&quot;featuredImage&quot;:\[0,(?:&quot;(.*?)&quot;|null)],&quot;seriesType&quot;:\[0,&quot;(.*?)&quot;]""",
    )

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.uppercase()) {
        "MANHWA" -> "MANHWA"
        "MANHUA" -> "MANHUA"
        "NOVEL"  -> "NOVEL"
        else -> "MANGA"
    }

    private fun parseListing(html: String): List<SManga> =
        listItemRegex.findAll(html).mapNotNull { m ->
            val slug = m.groupValues[1].ifBlank { return@mapNotNull null }
            val title = unescape(m.groupValues[2]).trim().ifBlank { return@mapNotNull null }
            val cover = m.groupValues[3].ifBlank { null }?.let { unescape(it) }
            SManga(
                sourceId = id,
                url = "$base/series/$slug",
                title = title,
                coverUrl = cover,
                contentType = normalizeContentType(m.groupValues[4]),
            )
        }.distinctBy { it.url }.toList()

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page <= 1) "$base/series" else "$base/series?page=$page"
            parseListing(get(url))
        } catch (_: Exception) { emptyList() }
    }

    // Server-side filtr na "/series" nefunguje (overeno zive) - misto nej se prohleda
    // prvnich par stranek katalogu a filtruje se podle titulku primo v appce.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val q = query.trim()
            (1..5).flatMap { p ->
                val url = if (p <= 1) "$base/series" else "$base/series?page=$p"
                try { parseListing(get(url)) } catch (_: Exception) { emptyList() }
            }.distinctBy { it.url }.filter { it.title.contains(q, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    private fun field(html: String, name: String): String? =
        Regex("""&quot;$name&quot;:\[0,&quot;(.*?)&quot;]""").find(html)?.groupValues?.get(1)?.let { unescape(it) }?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val description = field(html, "postContent")?.let {
                org.jsoup.Jsoup.parse(it).text().trim()
            }?.ifBlank { null }
            manga.copy(
                title = field(html, "postTitle") ?: manga.title,
                description = description,
                artist = field(html, "artist"),
                status = field(html, "seriesStatus")?.lowercase(),
                contentType = normalizeContentType(field(html, "seriesType")),
            )
        } catch (_: Exception) { manga }
    }

    private val chapterRegex = Regex(
        """&quot;id&quot;:\[0,\d+],&quot;number&quot;:\[0,([\d.]+)],&quot;slug&quot;:\[0,&quot;(.*?)&quot;],&quot;title&quot;:\[0,&quot;(.*?)&quot;],&quot;createdAt&quot;:\[0,&quot;(.*?)&quot;]""",
    )

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            chapterRegex.findAll(html).mapNotNull { m ->
                val num = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
                val slug = m.groupValues[2].ifBlank { return@mapNotNull null }
                val customTitle = unescape(m.groupValues[3]).trim()
                val name = customTitle.ifBlank { "Chapter ${if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()}" }
                val createdAt = m.groupValues[4]
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "${manga.url}/$slug",
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseIsoDate(createdAt),
                )
            }.distinctBy { it.url }.toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun parseIsoDate(text: String): Long = try {
        java.time.Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    private val pageImageRegex = Regex("""https://storage\.kaynscan\.org/[^"'\s]+?\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(chapter.url)
            pageImageRegex.findAll(html).map { it.value }.distinct()
                .filter { it.contains("/upload/series/") }
                .mapIndexed { i, url -> Page(i, url, url) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}

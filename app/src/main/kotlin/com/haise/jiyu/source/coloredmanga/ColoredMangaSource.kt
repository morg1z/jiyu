package com.haise.jiyu.source.coloredmanga

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
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * colorizedmangas.com - Next.js App Router. NENI klasicka scanlation skupina - jde o
 * fanouskovske obarveni JIZ OFICIALNE LICENCOVANYCH mainstream titulu (One Piece, Naruto,
 * Bleach, ...), hostovane pres GitHub repozitare (jsDelivr CDN). Maly katalog (~50 titulu,
 * overeno zive), zadne strankovani potreba - appka stahne cely seznam z uvodni stranky
 * (ld+json "CollectionPage" -> "hasPart" pole ComicSeries) a hleda v nem klientsky.
 *
 * Detail (zanry/popis) je take ld+json typu "ComicSeries", ale na rozdil od uvodni
 * stranky NENI v normalnim `<script type="application/ld+json">` - je escapovany uvnitr
 * RSC payloadu (`self.__next_f.push([1,"..."])`). Vytahuje se regexem na
 * `{\"@type\":\"ComicSeries\"`, pocitanim zavorek (respektuje escapovane uvozovky) a
 * JSONTokener pro jednorazove odescapovani retezce v retezci (spravne zvladne \", \\, \n
 * na rozdil od naivniho .replace()).
 *
 * Seznam kapitol uz JE normalni server-rendered HTML (`<a href="/{slug}/chapter/N">`),
 * Jsoup jde primo pouzit. Stranky kapitoly jsou primo https URL na
 * `cdn.jsdelivr.net/gh/.../pages/{kapitola}/{stranka}.webp` - poradi podle nazvu souboru
 * (zero-padded, string-sort = numeric-sort), filtrovane na cislo aktualni kapitoly (aby se
 * nezachytily nahledove obrazky sousednich kapitol).
 *
 * Obal (cover) neni v zadnem JSON - je to staticky asset `/covers/{slug}.jpg` (overeno
 * zive na 5 ruznych titulech).
 */
@Singleton
class ColoredMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "coloredmanga"
    override val name = "Colored Manga"
    override val homepageUrl get() = base
    private val base = "https://colorizedmangas.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun extractLdJsonBlocks(html: String): List<String> {
        val marker = "application/ld+json"
        val result = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val mi = html.indexOf(marker, searchFrom)
            if (mi < 0) break
            val s = html.indexOf('>', mi) + 1
            val e = html.indexOf("</script>", s)
            if (s <= 0 || e < 0) break
            result += html.substring(s, e)
            searchFrom = e + 1
        }
        return result
    }

    private fun parseCatalog(html: String): List<SManga> {
        val blocks = extractLdJsonBlocks(html).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val collection = blocks.firstNotNullOfOrNull { block ->
            val graph = block.optJSONArray("@graph") ?: return@firstNotNullOfOrNull null
            (0 until graph.length()).map { graph.getJSONObject(it) }
                .firstOrNull { it.optString("@type") == "CollectionPage" }
        } ?: return emptyList()
        val parts = collection.optJSONArray("hasPart") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { i ->
            val o = parts.getJSONObject(i)
            val url = o.optString("url").ifBlank { return@mapNotNull null }
            val slug = url.trimEnd('/').substringAfterLast('/')
            SManga(
                sourceId = id,
                url = url,
                title = o.optString("name").ifBlank { slug },
                coverUrl = "$base/covers/$slug.jpg",
                author = o.optJSONObject("author")?.optString("name")?.ifBlank { null },
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try { parseCatalog(get(base)) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            parseCatalog(get(base)).filter { it.title.contains(query, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    // Detail (zanry/popis) je jen v RSC payloadu jako `{\"@type\":\"ComicSeries\",...}` -
    // pocitani zavorek (respektuje escapovane uvozovky uvnitr retezcu) najde konec objektu,
    // pak JSONTokener("\"...\"").nextValue() spravne odescapuje JSON-string-v-JSON-stringu.
    private fun extractComicSeriesJson(html: String): JSONObject? {
        val marker = "{\\\"@type\\\":\\\"ComicSeries\\\""
        val start = html.indexOf(marker)
        if (start < 0) return null
        var depth = 0
        var i = start
        var inString = false
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { i++; break }
                    }
                }
            }
            i++
        }
        val raw = html.substring(start, i)
        val decoded = runCatching { JSONTokener("\"$raw\"").nextValue() as String }.getOrNull() ?: return null
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get(manga.url)
            val series = extractComicSeriesJson(html) ?: return@withContext manga
            val genres = series.optJSONArray("genre")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?.ifEmpty { null } ?: manga.genres
            manga.copy(
                description = series.optString("description").ifBlank { null } ?: manga.description,
                genres = genres,
                author = series.optJSONObject("author")?.optString("name")?.ifBlank { null } ?: manga.author,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = manga.url.trimEnd('/').substringAfterLast('/')
            val doc = Jsoup.parse(get(manga.url), manga.url)
            doc.select("a[href^=/$slug/chapter/]").mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                val num = href.trimEnd('/').substringAfterLast('/').toFloatOrNull() ?: return@mapNotNull null
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = "Chapter $num",
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.distinctBy { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterNum = chapter.url.trimEnd('/').substringAfterLast('/')
            val html = get(chapter.url)
            val regex = Regex("""https://cdn\.jsdelivr\.net/gh/[^"\\]+/pages/$chapterNum/\d+\.(?:webp|jpg|jpeg|png)""")
            regex.findAll(html).map { it.value }.distinct().sorted()
                .mapIndexed { i, url -> Page(i, url, url) }.toList()
        } catch (_: Exception) { emptyList() }
    }
}

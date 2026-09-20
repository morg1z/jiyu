package com.haise.jiyu.source.simplyhentai

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
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
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * simply-hentai.com - anglicka hentai doujin/manga galerie (Next.js Pages
 * Router). Listing i detail stranky jsou server-rendovane a nesou kompletni
 * data primo v `__NEXT_DATA__` JSON (`pageProps.mangas`/`pageProps.manga`) -
 * zadne extra HTTP pozadavky navic. Detail dokonce uz obsahuje CELY `images`
 * seznam s primymi plne-rozlisenymi URL (`sizes.full`), takze getPageList
 * nepotrebuje zadnou zvlast "all-pages"/reader stranku.
 *
 * Skutecne fulltextove hledani je cistě klientske (`/search?query=...` ma
 * prazdne `pageProps` bez ohledu na parametr - overeno zive) a zadny verejny
 * API endpoint pro nej se nepodarilo najit ani v JS bundlech. Jedina funkcni
 * serverova cesta je `/search/{slug}` - ale ta NEni fulltextovy vyhledavac,
 * jde primo na konkretni galerii, jejiz vlastni slug (ne nazev seznamu/tagu)
 * presne odpovida - tedy funguje jen kdyz `query` uhodne presny slug jedne
 * konkretni galerie. search() to pouziva jako "best effort": vrati tu jednu
 * galerii pri presne shode, jinak prazdny seznam.
 */
@Singleton
class SimplyHentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "simplyhentai"
    override val name = "Simply Hentai"
    override val supportsSortOrder: Boolean get() = false
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://www.simply-hentai.com"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    private fun extractNextData(html: String): JSONObject? {
        val markerIdx = html.indexOf("__NEXT_DATA__")
        if (markerIdx == -1) return null
        val jsonStart = html.indexOf('>', markerIdx).let { if (it == -1) return null else it + 1 }
        val jsonEnd = html.indexOf("</script>", jsonStart)
        if (jsonEnd == -1) return null
        return try { JSONObject(html.substring(jsonStart, jsonEnd)) } catch (e: Exception) { e.rethrowIfControl(); null }
    }

    private fun mangaFromListItem(item: JSONObject): SManga? {
        val slug = item.optString("slug").ifBlank { return null }
        val series = item.optJSONObject("series") ?: return null
        val seriesSlug = series.optString("slug").ifBlank { return null }
        val title = item.optString("title").ifBlank { return null }
        val cover = item.optJSONObject("preview")?.optJSONObject("sizes")?.optString("full")?.ifBlank { null }
        return SManga(sourceId = id, url = "$base/$seriesSlug/$slug", title = title, coverUrl = cover, contentType = "MANGA")
    }

    private fun parseListing(html: String): List<SManga> {
        val pageProps = extractNextData(html)?.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()
        val mangas = pageProps.optJSONArray("mangas") ?: return emptyList()
        return (0 until mangas.length()).mapNotNull { mangaFromListItem(mangas.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            try { parseListing(fetchHtml("$base/2-mangas/sort-most-viewed?page=$page")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun slugify(text: String): String = text.trim().lowercase()
        .replace(Regex("""\s+"""), "-")
        .replace(Regex("""[^a-z0-9-]"""), "")

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext getPopular(page, filter)
            if (page > 1) return@withContext emptyList()
            try {
                val html = fetchHtml("$base/search/${slugify(query)}")
                val manga = extractNextData(html)?.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("manga")
                    ?: return@withContext emptyList()
                mangaFromDetail(manga)?.let { listOf(it) } ?: emptyList()
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private fun mangaFromDetail(manga: JSONObject): SManga? {
        val slug = manga.optString("slug").ifBlank { return null }
        val series = manga.optJSONObject("series") ?: return null
        val seriesSlug = series.optString("slug").ifBlank { return null }
        val title = manga.optString("title").ifBlank { return null }
        val cover = manga.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("sizes")?.optString("full")?.ifBlank { null }
        return SManga(sourceId = id, url = "$base/$seriesSlug/$slug", title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val json = extractNextData(fetchHtml(manga.url))?.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("manga")
                ?: return@withContext manga
            val description = json.optString("description").ifBlank { null }?.let { Jsoup.parse(it).text().ifBlank { null } }
            val artists = json.optJSONArray("artists")
            val artist = if (artists != null) (0 until artists.length())
                .mapNotNull { artists.getJSONObject(it).optString("title").ifBlank { null } }
                .joinToString(", ").ifBlank { null } else null
            val tagsArr = json.optJSONArray("tags")
            val genres = if (tagsArr != null) (0 until tagsArr.length())
                .mapNotNull { tagsArr.getJSONObject(it).optString("title").ifBlank { null } } else emptyList()

            manga.copy(
                title = json.optString("title").ifBlank { null } ?: manga.title,
                description = description,
                artist = artist,
                genres = genres,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val json = extractNextData(fetchHtml(chapter.url))?.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("manga")
                ?: return@withContext emptyList()
            val images = json.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapNotNull { i ->
                val url = images.getJSONObject(i).optJSONObject("sizes")?.optString("full")?.ifBlank { null }
                    ?: return@mapNotNull null
                Page(index = i, url = url, imageUrl = url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

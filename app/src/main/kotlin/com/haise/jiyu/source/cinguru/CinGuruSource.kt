package com.haise.jiyu.source.cinguru

import com.haise.jiyu.util.resolveSourceUrl
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * cin.guru - anglicka hentai doujinshi galerie postavena na Next.js. Na
 * rozdil od ostatnich zdroju v tomhle balicku (klasicky server-renderovany
 * HTML markup) tady CSS trida jsou hashovane CSS moduly ("styles_xxx__hash"),
 * takze selektory na tride jsou k nicemu. Misto toho kazda stranka nese
 * kompletni hydratacni data primo v `<script id="__NEXT_DATA__">` jako JSON
 * (props.pageProps.data) - odtamtud se parsuje uplne vsechno.
 *
 * Homepage (`/`) ma jen dve staticke sekce bez pagovani ("popular" - 5
 * polozek, "all" - 25 nejnovejsich) a nemeni se podle `?page=` - proto
 * getPopular vraci data jen pro page==1, ale obe sekce jsou v JEDNOM
 * JSON payloadu - "Nejnovejsi" (filter.sortBy=="latest") tak muze cist
 * "all" a "Popularni" cist "popular" bez dalsiho requestu navic.
 * Fulltextove hledani (`/search`) je
 * v tehle appce cistě klientske (Next.js "nextExport" stranka bez
 * getServerSideProps) - v syrovem HTML/JSON z serveru nejsou zadna data,
 * takze search() tady neni podporovane a vraci prazdny seznam (stejny
 * pristup jako DankeMoeSource.search).
 *
 * Cela galerie = jedna "kapitola" (viz NhentaiSource).
 */
@Singleton
class CinGuruSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "cinguru"
    override val name = "cin.guru"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://cin.guru"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .build()
        return client.newCall(request).execute().use { it.bodyOrThrow(url) }
    }

    /** Vytahne JSON z `<script id="__NEXT_DATA__" type="application/json">{...}</script>` pomoci pocitani zavorek (stejny pristup jako Hentai20Source.extractTsReaderImages). */
    private fun extractNextData(html: String): JSONObject? {
        val markerIdx = html.indexOf("__NEXT_DATA__")
        if (markerIdx == -1) return null
        val jsonStart = html.indexOf('{', markerIdx)
        if (jsonStart == -1) return null

        var depth = 0
        var end = -1
        var inString = false
        var escaped = false
        for (i in jsonStart until html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end == -1) return null
        return try { JSONObject(html.substring(jsonStart, end + 1)) } catch (e: Exception) { e.rethrowIfControl(); null }
    }

    private fun listItemToSManga(obj: JSONObject): SManga? {
        val galleryId = obj.optInt("id", -1).takeIf { it != -1 } ?: return null
        val titleObj = obj.optJSONObject("title")
        val title = titleObj?.optString("pretty")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("english")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("japanese")?.takeIf { it.isNotBlank() }
            ?: "ID: $galleryId"
        val cover = obj.optJSONObject("cover")?.optString("t")?.ifBlank { null }
        return SManga(sourceId = id, url = "/v/$galleryId", title = title, coverUrl = cover, contentType = "MANGA")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val json = extractNextData(fetchHtml("$base/")) ?: return@withContext emptyList()
            val data = json.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("data") ?: return@withContext emptyList()
            val key = if (filter.sortBy == "latest") "all" else "popular"
            val items = data.optJSONArray(key) ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { listItemToSManga(items.optJSONObject(it) ?: return@mapNotNull null) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Hledani je na cin.guru cistě klientske (Next.js "nextExport" stranka) - v HTML z
    // pozadavku serveru nejsou zadna data k rozparsovani, viz komentar u tridy.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = emptyList()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val json = extractNextData(fetchHtml(resolveSourceUrl(base, manga.url))) ?: return@withContext manga
            val data = json.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("data") ?: return@withContext manga

            val titleObj = data.optJSONObject("title")
            val title = titleObj?.optString("pretty")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: manga.title

            val tags = data.optJSONArray("tags") ?: org.json.JSONArray()
            val tagObjs = (0 until tags.length()).mapNotNull { tags.optJSONObject(it) }
            val artist = tagObjs.firstOrNull { it.optString("type") == "artist" }?.optString("name")
            val genres = tagObjs.filter { it.optString("type") == "tag" }.mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }

            manga.copy(title = title, artist = artist?.ifBlank { null }, genres = genres)
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
            val json = extractNextData(fetchHtml(resolveSourceUrl(base, chapter.url))) ?: return@withContext emptyList()
            val data = json.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("data") ?: return@withContext emptyList()
            val pages = data.optJSONObject("images")?.optJSONArray("pages") ?: return@withContext emptyList()
            (0 until pages.length()).mapNotNull { i ->
                val url = pages.optJSONObject(i)?.optString("t")?.ifBlank { null } ?: return@mapNotNull null
                Page(index = i, url = url, imageUrl = url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

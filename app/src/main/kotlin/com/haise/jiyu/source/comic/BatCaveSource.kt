package com.haise.jiyu.source.comic

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatCave - americké superhrdinské komiksy (Marvel/DC a další vydavatelé). Web běží na
 * DataLife Engine CMS s vlastní čtečkou - na rozdíl od typických "jedno číslo = jedna
 * kapitola" comic sourců (viz ComicBookPlusSource/ReadFreeComicsOnlineSource) tady má každý
 * titul SKUTEČNÝ seznam kapitol/čísel, ale ten NENÍ v HTML tabulce - je zapsaný jako JSON
 * uvnitř `<script>window.__DATA__ = {...}</script>` na stránce detailu. Samotné obrázky
 * stránek se navíc nedají odvodit ze statické URL - čtečka je tahá přes AJAX POST na
 * interní API endpoint (viz getPageList), který vrací seznam URL podle id kapitoly.
 *
 * Web je za Cloudflare (ověřeno živě - `Cf-Mitigated: challenge` v response headers) -
 * spoléhá se na sdílený CloudflareInterceptor v OkHttpClientu (AppModule.kt), stejně jako
 * ostatní chráněné zdroje. Živě na zařízení zatím neověřeno.
 */
@Singleton
class BatCaveSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "batcave"
    override val name = "BatCave"
    override val contentType = "COMIC"
    override val homepageUrl get() = base
    private val base = "https://batcave.biz"

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun postJson(url: String, json: JSONObject): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseListing(doc: Document): List<SManga> =
        doc.select("#dle-content > .readed, #content-load > .latest").mapNotNull { el ->
            val link = el.selectFirst(".readed__title > a, .latest__title > a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val img = el.selectFirst("img")
            SManga(
                sourceId = id,
                url = href.removePrefix(base),
                title = link.text().trim(),
                coverUrl = img?.attr("data-src")?.ifBlank { img.attr("src") },
                contentType = "COMIC",
            )
        }

    // Bez dotazu = obecný "browse" výpis (/comix/), stejná stránková struktura jako výsledky
    // hledání - proto sdílené parseListing pro obě.
    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val url = if (page > 1) "$base/comix/page/$page/" else "$base/comix/"
            parseListing(Jsoup.parse(get(url)))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = buildString {
                append(base).append("/search/").append(encoded)
                if (page > 1) append("/page/").append(page).append("/")
            }
            parseListing(Jsoup.parse(get(url)))
        } catch (_: Exception) { emptyList() }
    }

    /** Textový obsah `<li>` v postranním seznamu detailu (Publisher/Writer/Artist/...), bez odkazu samotného. */
    private fun Document.pageListValue(label: String): String? =
        selectFirst(".page__list > li:has(> div:contains($label)) > a")?.text()?.trim()?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val publisher = doc.pageListValue("Publisher")
            val description = buildString {
                if (publisher != null) append(publisher)
                appendLine()
                append(doc.selectFirst("div.page__text")?.text().orEmpty())
            }.trim()
            val releaseType = doc.selectFirst(".page__list > li:has(> div:contains(Release type))")?.ownText()?.trim()
            manga.copy(
                title = doc.selectFirst("header.page__header h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("div.page__poster img")?.attr("src") ?: manga.coverUrl,
                description = description.ifBlank { null },
                author = doc.pageListValue("Writer"),
                artist = doc.pageListValue("Artist"),
                genres = doc.select("div.page__tags a").map { it.text().trim() },
                status = when (releaseType) {
                    "Ongoing" -> "ONGOING"
                    "Completed" -> "COMPLETED"
                    else -> null
                },
            )
        } catch (_: Exception) { manga }
    }

    private val chapterDateFormat = SimpleDateFormat("d.M.yyyy", Locale.US)

    // Seznam kapitol neni v HTML, ale v JSON bloku vlozenem primo do stranky - viz dokumentace
    // tridy. `chapter.url` si ulozime jako "comicId/chapterId/xhash", getPageList si to zpatky
    // rozparsuje (xhash je potreba poslat spolu s id, jinak API odpovi chybou).
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val script = doc.select("script").map { it.data() }
                .firstOrNull { it.contains("window.__DATA__") } ?: return@withContext emptyList()
            val json = script.substringAfter("window.__DATA__ = ").substringBeforeLast(";").trim()
            val data = JSONObject(json)
            val comicId = data.getInt("news_id")
            val xhash = data.optString("xhash", "")
            val chapters = data.optJSONArray("chapters") ?: return@withContext emptyList()

            (0 until chapters.length()).mapNotNull { i ->
                val chap = chapters.getJSONObject(i)
                val chapterId = chap.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "$comicId/$chapterId/$xhash",
                    name = chap.optString("title").ifBlank { "Ch.${chap.optDouble("posi", 0.0)}" },
                    chapterNumber = chap.optDouble("posi", 0.0).toFloat(),
                    dateUpload = chapterDateFormat.parse(chap.optString("date"))?.time ?: 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val (comicId, chapterId) = chapter.url.split("/", limit = 3).let { it[0] to it[1] }
            val body = JSONObject().apply {
                put("news_id", comicId)
                put("chapter_id", chapterId)
            }
            val response = postJson("$base/engine/ajax/controller.php?mod=api&action=reader/getChapterData", body)
            val images = JSONObject(response).optJSONObject("data")?.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).map { i ->
                val raw = images.getString(i).trim()
                val url = if (raw.startsWith("http")) raw else "$base$raw"
                Page(index = i, url = url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

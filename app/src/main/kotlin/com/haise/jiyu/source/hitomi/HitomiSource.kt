package com.haise.jiyu.source.hitomi

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ltn.hitomi.la je mrtva (DNS/TCP nedostupna), nahradila ji
 * ltn.gold-usergeneratedcontent.net. Detailni i listing stranky na
 * hitomi.la jsou dnes uz jen prazdne HTML kostry vyplnovane klientskym JS
 * (viz reader.js/galleryblock.js/search.js), takze misto scrapovani HTML
 * pouzivame primo tri zdroje dat, ktere ten JS sam pouziva:
 *  - `/index-all.nozomi` - binarni index gallery ID (4B big-endian), ctu
 *    ho pres HTTP Range misto stahovani celych ~5MB pro kazdou stranku
 *  - `/galleryblock/{id}.html` - serverove vyrenderovana karta (nazev +
 *    thumbnail) pro jedno ID, vyzaduje Referer jinak vraci 404
 *  - `/galleries/{id}.js` - JS promenna `galleryinfo` s kompletnim
 *    detailem vcetne "files" pole pro stranky kapitoly
 * Plnohodnotny fulltextovy search hitomi resi vlastnim binarnim indexem
 * parsovanym v JS (mimo rozumny rozsah reverse-engineeringu), search proto
 * jen filtruje getPopular vysledky.
 *
 * Adresy plnych obrazku (ne thumbnaily) se musi sestavit stejnym
 * algoritmem jako `gg.js` (subdomena "w1"/"w2" + casem se menici prefix
 * `gg.b`) - `gg.js` se proto stahuje a parsuje za behu (viz [fetchGg]),
 * hodnoty se nedaji zafixovat natvrdo.
 */
@Singleton
class HitomiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "hitomi"
    override val name = "Hitomi.La"
    override val homepageUrl get() = baseUrl

    private val baseUrl = "https://hitomi.la"
    private val ltnUrl = "https://ltn.gold-usergeneratedcontent.net"
    private val cdnDomain = "gold-usergeneratedcontent.net"
    private val itemsPerPage = 25

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "$baseUrl/")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun getRange(url: String, start: Long, end: Long): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "$baseUrl/")
            .header("Range", "bytes=$start-$end")
            .build()
        return client.newCall(req).execute().use { it.body?.bytes() ?: ByteArray(0) }
    }

    private fun decodeNozomiIds(bytes: ByteArray): List<Long> {
        val ids = mutableListOf<Long>()
        var i = 0
        while (i + 4 <= bytes.size) {
            val id = ((bytes[i].toLong() and 0xFF) shl 24) or
                ((bytes[i + 1].toLong() and 0xFF) shl 16) or
                ((bytes[i + 2].toLong() and 0xFF) shl 8) or
                (bytes[i + 3].toLong() and 0xFF)
            ids.add(id)
            i += 4
        }
        return ids
    }

    private fun parseGalleryBlock(html: String): SManga? {
        val doc = Jsoup.parse(html, baseUrl)
        val link = doc.selectFirst("h1.lillie a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim().ifBlank { return null }
        val cover = doc.selectFirst(".dj-img1 img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        return SManga(sourceId = id, url = href, title = title, coverUrl = cover)
    }

    private suspend fun fetchGalleryBlocks(ids: List<Long>): List<SManga> = coroutineScope {
        ids.map { galId ->
            async {
                try { parseGalleryBlock(get("$ltnUrl/galleryblock/$galId.html")) } catch (_: Exception) { null }
            }
        }.mapNotNull { it.await() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val start = (page - 1).toLong() * itemsPerPage * 4
            val end = start + itemsPerPage * 4 - 1
            val ids = decodeNozomiIds(getRange("$ltnUrl/index-all.nozomi", start, end))
            fetchGalleryBlocks(ids)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            getPopular(page, filter).filter { it.title.contains(query, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    /** Cisla galerie je vzdy posledni "-oddeleny" segment nazvu souboru pred priponou. */
    private fun galleryIdOf(url: String) = url.substringAfterLast("-").removeSuffix(".html")

    private fun galleryInfo(galleryId: String): JSONObject? {
        val js = get("$ltnUrl/galleries/$galleryId.js")
        val jsonStr = js.substringAfter("=").trimEnd(';', '\n', '\r', ' ').trim()
        return try { JSONObject(jsonStr) } catch (_: Exception) { null }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val json = galleryInfo(galleryIdOf(manga.url)) ?: return@withContext manga
            val tags = json.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("tag") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val type = json.optString("type").takeIf { it.isNotBlank() }
            val language = json.optString("language_localname").takeIf { it.isNotBlank() }
            val description = buildString {
                if (!type.isNullOrBlank()) append("Type: $type\n")
                if (!language.isNullOrBlank()) append("Language: $language\n")
                if (tags.isNotEmpty()) append("Tags: ${tags.joinToString(", ")}")
            }.trim()
            manga.copy(
                title = json.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
                description = description.takeIf { it.isNotBlank() },
                genres = tags.take(20),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = "/galleries/${galleryIdOf(manga.url)}",
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = System.currentTimeMillis(),
            )
        )
    }

    /**
     * gg.js obcas otoci polaritu: `var o = 1;` + case-list vedouci k `o = 0;`,
     * jindy `var o = 0;` + case-list vedouci k `o = 1;` - proto se musi cist
     * default hodnota i hodnota z "case ...: o = X; break;" zvlast, ne jen
     * predpokladat, ze case-list vzdy znamena 0 (puvodni bug, ktery zpusobil
     * spatny vyber subdomeny a "Page failed to load" u nekterych galerii).
     */
    private data class GgData(val b: String, val default: Int, val caseValue: Int, val cases: Set<Int>)

    private fun fetchGg(): GgData {
        val js = get("$ltnUrl/gg.js")
        val b = Regex("""b:\s*'([^']*)'""").find(js)?.groupValues?.get(1).orEmpty()
        val default = Regex("""var o = (\d);""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val caseValue = Regex("""o = (\d); break;""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cases = Regex("""case (\d+):""").findAll(js).map { it.groupValues[1].toInt() }.toSet()
        return GgData(b, default, caseValue, cases)
    }

    /** Sestaveni URL plne stranky presne podle algoritmu z common.js/gg.js (viz komentar u tridy). */
    private fun fullImageUrl(hash: String, gg: GgData): String {
        val tail = hash.takeLast(3)
        val g = ("" + tail[2] + tail[0] + tail[1]).toInt(16)
        val m = if (g in gg.cases) gg.caseValue else gg.default
        val subdomain = "w" + (1 + m)
        return "https://$subdomain.$cdnDomain/${gg.b}$g/$hash.webp"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val galleryId = chapter.url.substringAfterLast("/")
            val json = galleryInfo(galleryId) ?: return@withContext emptyList()
            val files = json.optJSONArray("files") ?: return@withContext emptyList()
            val gg = fetchGg()
            (0 until files.length()).map { i ->
                val hash = files.getJSONObject(i).optString("hash")
                val url = fullImageUrl(hash, gg)
                Page(index = i, url = url, imageUrl = url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

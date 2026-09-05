package com.haise.jiyu.source.hentainexus

import com.haise.jiyu.source.FilterTag
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
import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * hentainexus.com - hentai/doujinshi katalog. Listing i detail stranka jsou
 * obycejne server-rendrovane HTML (Bulma layout), ale samotny "reader"
 * (`/read/{id}`) NEMA obrazky primo v HTML - misto toho vlozi jeden base64
 * blob do `initReader("...", "puvodni nazev", {...})`, ktery klientsky JS
 * (obfuskovany `reader.min.js`) desifruje vlastnim proudovym algoritmem
 * (XOR proti hostname + RC4-podobny KSA/PRGA s "drop" krokem odvozenym z
 * CRC-8-like kontrolniho souctu prvnich 64 bajtu). Algoritmus je zde
 * primo prepsany do Kotlinu (odvozeno z verejneho reverse-engineeringu v
 * https://github.com/MapoMagpie/comic-looms, matcher pro hentainexus.com) -
 * bez nej by nebylo mozne stranky galerie vubec ziskat, protoze zadny jiny
 * (neobfuskovany) zdroj obrazku na webu neexistuje.
 */
@Singleton
class HentaiNexusSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "hentainexus"
    override val name = "HentaiNexus"
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://hentainexus.com"
    private val hostname = "hentainexus.com"

    /** Prvnich 16 prvocisel - stejny seznam jako sito v puvodnim reader.min.js. */
    private val primes = intArrayOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53)

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    /** Desifrovani `initReader()` payloadu - viz komentar u tridy. */
    private fun decodeReaderPayload(base64Data: String): JSONArray {
        val raw = Base64.getDecoder().decode(base64Data)
        val decoded = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        val hostLen = minOf(hostname.length, 64)
        for (i in 0 until hostLen) {
            decoded[i] = decoded[i] xor hostname[i].code
        }

        // CRC-8-like kontrolni soucet prvnich 64 bajtu -> index do "primes".
        var a = 0
        for (step in 0 until 64) {
            a = a xor decoded[step]
            repeat(8) {
                a = if (a and 1 != 0) (a ushr 1) xor 12 else a ushr 1
            }
        }
        val d = primes[a and 7]

        // RC4-style KSA, klic = prvnich 64 bajtu (cyklicky).
        val s = IntArray(256) { it }
        var b = 0
        for (i in 0 until 256) {
            b = (b + s[i] + decoded[i % 64]) % 256
            val tmp = s[i]; s[i] = s[b]; s[b] = tmp
        }

        // Modifikovana PRGA - misto kroku +1 pouziva promenny "drop" (d).
        val out = ByteArrayOutputStream()
        var e = 0; var f = 0; var j = 0; var k = 0
        var i = 0
        val n = decoded.size
        while (i + 64 < n) {
            j = (j + d) % 256
            k = (f + s[(k + s[j]) % 256]) % 256
            f = (f + j + s[j]) % 256
            val tmp = s[j]; s[j] = s[k]; s[k] = tmp
            e = s[(k + s[(j + s[(e + f) % 256]) % 256]) % 256]
            out.write((decoded[i + 64] xor e) and 0xFF)
            i++
        }
        return JSONArray(String(out.toByteArray(), Charsets.UTF_8))
    }

    private fun parseListing(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("a[href^=/view/]").mapNotNull { a ->
            val card = a.selectFirst("div.card") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst(".card-header-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
        }
    }

    // /explore/categories/tag je jediny (nestrankovany) HTML vypis vsech ~228 tagu
    // pouzivanych na webu ("used N times" u kazdeho) - dost male na zive stahnuti
    // celeho seznamu najednou (na rozdil od /explore/categories/parody apod., ktere
    // by mohly byt vetsi). Filtrovani pak jede pres uz existujici fulltext vyhledavani
    // ("?q=") se specialni syntaxi "tag:jmeno" (vicelove tagy v uvozovkach) - presne
    // to same, co pouzivaji odkazy primo na strance /explore/categories/tag. Live
    // overeno: "tag:beach" a "tag:blowjob" vraci skutecne odlisne seznamy titulu.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/explore/categories/tag"), base)
            val tags = doc.select("a[href^=\"/?q=tag:\"]").mapNotNull { a ->
                val name = a.text().substringBefore("(used").trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = name, label = name)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun tagQuery(tag: String): String {
        val expr = if (tag.contains(' ')) "tag:\"$tag\"" else "tag:$tag"
        return URLEncoder.encode(expr, "UTF-8")
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val q = tagQuery(filter.genres.first())
                val url = if (page <= 1) "$base/?q=$q" else "$base/page/$page?q=$q"
                parseListing(get(url))
            } catch (_: Exception) { emptyList() }
        }
        try {
            val url = if (page <= 1) "$base/" else "$base/page/$page"
            parseListing(get(url))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val q = tagQuery(filter.genres.first())
                val url = if (page <= 1) "$base/?q=$q" else "$base/page/$page?q=$q"
                parseListing(get(url))
            } catch (_: Exception) { emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val url = if (page <= 1) "$base/?q=$q" else "$base/page/$page?q=$q"
            parseListing(get(url))
        } catch (_: Exception) { emptyList() }
    }

    private fun stripCount(text: String) = text.replace(Regex("""\s*\([\d,]+\)\s*$"""), "").trim()

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
            val title = doc.selectFirst("h1.title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.title
            val cover = doc.selectFirst("figure.image img")?.attr("src")?.takeIf { it.isNotBlank() } ?: manga.coverUrl

            val rows = doc.select("table.view-page-details tr").associate { tr ->
                val label = tr.selectFirst("td.viewcolumn")?.text()?.trim().orEmpty()
                val valueTd = tr.select("td").getOrNull(1)
                val links = valueTd?.select("a")?.map { stripCount(it.text()) }?.filter { it.isNotBlank() }.orEmpty()
                val values = links.ifEmpty {
                    listOfNotNull(valueTd?.text()?.trim()?.takeIf { it.isNotBlank() })
                }
                label to values
            }

            val artist = rows["Artist"]?.firstOrNull() ?: rows["Circle"]?.firstOrNull()
            val genres = rows["Tags"].orEmpty().take(20)
            val parody = rows["Parody"].orEmpty()
            val publisher = rows["Publisher"]?.firstOrNull()
            val magazine = rows["Magazine"]?.firstOrNull()
            val pages = rows["Pages"]?.firstOrNull()

            val desc = buildString {
                if (parody.isNotEmpty()) append("Parody: ${parody.joinToString(", ")}\n")
                if (!magazine.isNullOrBlank()) append("Magazine: $magazine\n")
                if (!publisher.isNullOrBlank()) append("Publisher: $publisher\n")
                if (!pages.isNullOrBlank()) append("Pages: $pages")
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
        val galleryId = manga.url.trim('/').substringAfterLast('/')
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = "/read/$galleryId",
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val token = Regex("""initReader\("([^"]*)"""").find(html)?.groupValues?.get(1) ?: return@withContext emptyList()
            val arr = decodeReaderPayload(token)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("image_fallback").ifBlank { null }
                    ?: obj.optString("image").ifBlank { null }
                    ?: obj.optString("image_webp").ifBlank { null }
                    ?: obj.optString("image_avif").ifBlank { null }
                    ?: return@mapNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

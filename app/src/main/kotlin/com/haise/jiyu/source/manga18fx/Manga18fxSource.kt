package com.haise.jiyu.source.manga18fx

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vlastní (ne-Madara) web - vlastní markup pro výpis/detail/kapitoly, ale
 * detail-boxy pro Status/Author/Genre atd. mají shodou náhody stejnou
 * strukturu jako Madara ("summary-heading"/"summary-content"), takže by je
 * generický MadaraSource mohl teoreticky napůl zvládnout - kvůli odlišnému
 * chapter-list markupu (`ul.row-content-chapter`, vlastní `span.chapter-time`
 * formát data) je ale čitelnější vlastní třída.
 */
@Singleton
class Manga18fxSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "manga18fx"
    override val name = "Manga18fx"
    override val contentType = "MANHWA"

    // http, ne https - viz network_security_config.xml pro duvod (nekonzistentni
    // TLS handshake na Androidu, web funguje spolehlive pres obycejne HTTP).
    private val base = "http://manga18fx.com"

    // Sdileny klient ma connectionSpecs omezene jen na TLS varianty (kvuli
    // Chrome-like TLS fingerringu pro ostatni zdroje) - bez ConnectionSpec.CLEARTEXT
    // v seznamu OkHttp odmitne JAKYKOLIV http:// pozadavek s "CLEARTEXT communication
    // not enabled for client" driv, nez se vubec podiva do network_security_config.xml
    // (ktery uz http pro tuhle domenu spravne povoluje). Odvozeny klient jen pro
    // tenhle zdroj, aby se sdileny klient (a jeho TLS fingerprint) pro ostatni
    // zdroje nijak nezmenil.
    private val cleartextClient = client.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.COMPATIBLE_TLS))
        .build()

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        cleartextClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Chyba ${response.code} pri nacitani $url" }
            return Jsoup.parse(response.body?.string().orEmpty(), url)
        }
    }

    private fun parseMangaList(doc: Document): List<SManga> =
        doc.select("div.bsx-item").mapNotNull { item ->
            val link = item.selectFirst("div.thumb-manga a") ?: item.selectFirst("a") ?: return@mapNotNull null
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = item.selectFirst("h3.tt")?.text()?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val cover = item.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.trim()?.ifBlank { null }

            SManga(sourceId = id, url = url, title = title, coverUrl = cover)
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            // "latest" -> homepage feed strankovany /page/N, jinak /hot-manga?page=N (podle zobrazeni).
            val url = if (filter.sortBy == "latest") "$base/page/$page" else "$base/hot-manga?page=$page"
            parseMangaList(fetchDocument(url))
        }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            parseMangaList(fetchDocument("$base/search?q=$q"))
        }

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            val description = doc.selectFirst("div.dsct")?.text()?.trim()?.ifBlank { null }

            // Info-box je serie ".post-content_item" bloku (Rating/Alternative/Author(s)/
            // Artist(s)/Genre(s)/Type/Release/Status v tomto poradi) - poradi neni pevne
            // zaruceno, proto se paruji podle textu popisku (".summary-heading"), ne podle
            // pozice.
            var status: String? = null
            var author: String? = null
            var artist: String? = null
            var genres: List<String> = emptyList()
            doc.select("div.post-content_item").forEach { box ->
                val label = box.selectFirst(".summary-heading")?.text()?.trim().orEmpty()
                val content = box.selectFirst(".summary-content") ?: return@forEach
                when {
                    label.startsWith("Status") -> status = content.text().trim().ifBlank { null }
                    label.startsWith("Author") -> author = content.text().trim().ifBlank { null }
                    label.startsWith("Artist") -> artist = content.text().trim().ifBlank { null }
                    label.startsWith("Genre")  -> genres = content.select("a").map { it.text().trim() }.filter { it.isNotBlank() }
                }
            }

            manga.copy(description = description, status = status, author = author, artist = artist, genres = genres)
        }

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            doc.select("ul.row-content-chapter li.a-h").mapNotNull { row -> chapterFromRow(row, manga.url) }
        }

    private fun chapterFromRow(row: Element, mangaUrl: String): SChapter? {
        val link = row.selectFirst("a.chapter-name") ?: row.selectFirst("a") ?: return null
        val url = link.absUrl("href").ifBlank { return null }
        val name = link.text().trim().ifBlank { return null }
        val chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: 0f
        val dateText = row.selectFirst("span.chapter-time")?.text()?.trim()

        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = url,
            name = name,
            chapterNumber = chapterNumber,
            dateUpload = parseDate(dateText),
        )
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            // "17 Jul 26"
            SimpleDateFormat("d MMM yy", Locale.ENGLISH).parse(text)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(chapter.url)
            doc.select("div.page-break img").mapIndexedNotNull { i, img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }.trim().ifBlank { return@mapIndexedNotNull null }
                Page(index = i, url = src, imageUrl = src)
            }
        }
}

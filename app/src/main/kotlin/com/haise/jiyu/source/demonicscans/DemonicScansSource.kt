package com.haise.jiyu.source.demonicscans

import com.haise.jiyu.source.bodyOrThrow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.TallImageSlicer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Náhrada za mrtvou doménu "Demon Scans" (demonscans.net - DNS už nerozresolvuje
 * vůbec, doména zanikla). Tohle je JINÝ tým/branding ("Manga Demon" / demonicscans.org),
 * ne přímý nástupce - jen podobně znějící jméno. Vlastní (ne Madara) šablona webu:
 * seznamy i detail jsou plně server-side renderované HTML (žádné nutné AJAX volání),
 * kapitoly taky - `<img class="imgholder">` s přímou URL na CDN, bez potřeby Refereru.
 *
 * `/chaptered.php?manga={id}&chapter={n}` dělá jen 302 redirect na skutečnou čtecí
 * stránku `/title/{slug}/chapter/{n}/1` - necháváme na tom, že OkHttpClient
 * (viz AppModule.kt) sleduje redirecty defaultně, takže stačí posílat tenhle
 * jednodušší odkaz a nemusíme si sami skládat slug.
 */
@Singleton
class DemonicScansSource @Inject constructor(
    private val client: OkHttpClient,
    @param:ApplicationContext private val context: Context,
) : MangaSource {

    override val id = "demonicscans"
    override val name = "DemonicScans"
    override val contentType = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://demonicscans.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    /** Karty v seznamech (translationlist.php/lastupdates.php) mají shodnou strukturu. */
    private fun parseCards(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("#updates-container > div.updates-element").mapNotNull { card ->
            val a = card.selectFirst("h2 a[href^=/manga/]") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = card.selectFirst(".thumb img")?.attr("src")
            SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = contentType)
        }.distinctBy { it.url }
    }

    /**
     * Žádná dedikovaná "populární" stránka - "Populární" tab proto mapujeme na
     * translationlist.php (kompletní katalog jejich vlastních překladů, nejbližší
     * ekvivalent "výchozího procházení"), "Nejnovější" na lastupdates.php (feed
     * aktualizací kapitol, odpovídá skutečnému významu "latest").
     */
    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val path = if (filter.sortBy == "latest") "lastupdates.php" else "translationlist.php"
            val query = if (page > 1) "?list=$page" else ""
            parseCards(get("$base/$path$query"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // /search.php nemá stránkování (je to živý autocomplete endpoint) - druhá a
        // další stránka by jen zopakovala stejný výsledek, radši ukončit scrollování.
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/search.php?manga=$q"))
            doc.select("a[href^=/manga/]").mapNotNull { a ->
                val href = a.attr("href")
                val title = a.selectFirst(".seach-right > div")?.text()?.trim()
                    ?: a.selectFirst("img")?.attr("title")?.trim()
                    ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val cover = a.selectFirst("img")?.attr("src")
                SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = contentType)
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val genres = doc.select(".genres-list li").map { it.text().trim() }.filter { it.isNotBlank() }

            var author: String? = null
            var status: String? = null
            doc.select("#manga-info-stats > div.flex.flex-row").forEach { row ->
                val cells = row.select("li")
                when (cells.getOrNull(0)?.text()?.trim()) {
                    "Author" -> author = cells.getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() }
                    "Status" -> status = cells.getOrNull(1)?.text()?.trim()
                }
            }
            val normalizedStatus = when {
                status.equals("Ongoing", ignoreCase = true) -> "Ongoing"
                status.equals("Completed", ignoreCase = true) -> "Completed"
                else -> status
            }

            manga.copy(
                title = doc.selectFirst("h1.big-fat-titles")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("#manga-page img")?.attr("src") ?: manga.coverUrl,
                genres = genres,
                author = author,
                status = normalizedStatus,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("#chapters-list a.chplinks").mapNotNull { a ->
                val href = a.attr("href")
                val num = Regex("""chapter=(\d+(?:\.\d+)?)""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                val name = a.ownText().trim().ifBlank { "Chapter ${num.toChapterLabel()}" }
                val dateText = a.selectFirst("span")?.text()?.trim().orEmpty()
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = num,
                    dateUpload = parseDate(dateText),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            val rawUrls = doc.select("img.imgholder").mapNotNull { img ->
                val src = img.attr("src")
                // Bug fix - puvodne se povolovaly jen obrazky z "demoniclibs.com" (allowlist
                // jedne konkretni CDN domeny), ta uz ale neni to, odkud web skutecne servíruje
                // obrazky (overeno zive - aktualne demonicscans.org/readermc.org) - allowlist
                // tak vyfiltroval UPLNE VSECHNY obrazky, ne jen reklamni banner, kvuli cemuz
                // getPageList vzdy vratil prazdno ("kapitolu se nepodarilo nacist"). Misto
                // povolovani jedne domeny se ted vyluci jen znamy reklamni vzor - odolnejsi
                // vuci budoucim zmenam CDN.
                if (src.isBlank() || src.contains("free_ads") || src.contains("/ads/")) return@mapNotNull null
                src
            }
            rawUrls.flatMapIndexed { pageIndex, url -> sliceIfNeeded(chapter, pageIndex, url) }
                .mapIndexed { i, p -> p.copy(index = i) }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * DemonicScans servíruje jednu "stránku" jako jeden souvislý obrázek, který může být
     * extrémně vysoký (pozorováno 720x11400 px u "Somebody Stop the Pope" kap. 90) - to
     * přesahuje maximální rozměr GPU textury na spoustě zařízení, takže se Compose Image
     * nevykreslí vůbec, potichu, bez chybové hlášky (černá plocha - viz issue report).
     * Takovou stránku stáhneme jednou a rozřežeme na menší kusy přes [BitmapRegionDecoder]
     * (dekóduje jen požadovaný výřez, nemusí držet celý obrázek v paměti najednou), uložíme
     * do cache složky appky a vrátíme jako VÍCE [Page] záznamů místo jednoho. Normální
     * (nepřesahující limit) stránky projdou beze změny - žádné stahování navíc.
     */
    private fun sliceIfNeeded(chapter: SChapter, pageIndex: Int, url: String): List<Page> {
        val original = Page(index = pageIndex, url = url, imageUrl = url)
        val cacheDir = File(context.cacheDir, "demonicscans_slices/${chapter.url.hashCode()}")

        // Cache hit z dřívějšího otevření stejné kapitoly - žádné nové stahování/řezání.
        cacheDir.listFiles { f -> f.name.startsWith("${pageIndex}_") }
            ?.sortedBy { it.name.substringAfter('_').substringBefore('.').toIntOrNull() ?: 0 }
            ?.takeIf { it.isNotEmpty() }
            ?.let { files -> return files.map { f -> Page(index = 0, url = f.absolutePath, imageUrl = "file://${f.absolutePath}") } }

        val bytes = try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            client.newCall(req).execute().use { it.body?.bytes() }
        } catch (_: Exception) { null } ?: return listOf(original)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0 || bounds.outWidth <= 0) return listOf(original)

        val slices = TallImageSlicer.computeSlices(height = bounds.outHeight, maxSliceHeight = MAX_SLICE_HEIGHT)
        if (slices.size <= 1) return listOf(original)

        @Suppress("DEPRECATION")
        val decoder = try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (_: Exception) { null } ?: return listOf(original)

        return try {
            cacheDir.mkdirs()
            slices.mapIndexed { sliceIndex, range ->
                val rect = Rect(0, range.first, bounds.outWidth, range.last + 1)
                val bitmap = decoder.decodeRegion(rect, null) ?: return listOf(original)
                val file = File(cacheDir, "${pageIndex}_$sliceIndex.jpg")
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                bitmap.recycle()
                Page(index = 0, url = file.absolutePath, imageUrl = "file://${file.absolutePath}")
            }
        } catch (_: Exception) {
            listOf(original)
        } finally {
            decoder.recycle()
        }
    }

    private fun Float.toChapterLabel(): String =
        if (this == this.toInt().toFloat()) this.toInt().toString() else this.toString()

    private fun parseDate(text: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(text)?.time ?: 0L
    } catch (_: Exception) { 0L }

    companion object {
        /** Bezpečný strop - běžný minimální GL_MAX_TEXTURE_SIZE napříč zařízeními je 4096. */
        private const val MAX_SLICE_HEIGHT = 4000
    }
}

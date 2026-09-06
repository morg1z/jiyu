package com.haise.jiyu.source.skythewood

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
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * skythewood.blogspot.com (puvodni ".sg" adresa v uzivatelove tabulce 302
 * presmerovava sem, overeno zive) - osobni Blogger blog s prekladem lehkych
 * romanu. Kazdy Blogger "label" (stitek) odpovida jedne prekladane serii
 * (napr. "Altina the Sword Princess", "Overlord", "Gamers", "Knight's & Magic"...).
 * Appka pouziva verejny Blogger GData JSON feed (Vzor A) misto HTML scrapovani.
 *
 * Seznam serii: root-level `feed.category` pole (overeno zive - JAKYKOLIV
 * "/feeds/posts/default?alt=json" dotaz vraci na urovni celeho feedu, ne jen
 * jednotlivych polozek, kompletni seznam VSECH 37 stitku pouzitych kdekoliv na
 * blogu, nezavisle na strankovani) minus rucne sestaveny blocklist obecnych/
 * mimotematickych stitku (Translation, Light Novel, Rant, Cafe, ...), ktere
 * neoznacuji samostatnou serii.
 *
 * DULEZITA CHYBA/OMEZENI BLOGGER FEEDU (overeno zive): strankovani pres CELY
 * blog bez filtru na label je nespolehlive - "start-index=1" vraci jen 4 z
 * deklarovanych 860 prispevku at uz s "max-results=500" nebo "orderby=published"
 * (zadny rozdil), zatimco "start-index=501" vraci uplne jine, korektni starsi
 * prispevky. Feed FILTROVANY podle konkretniho labelu je ale spolehlivy (overeno
 * zive na "Altina the Sword Princess", 89 vysledku) - strankovani funguje bez
 * mezer/prekryvu, pokud se dalsi "start-index" pocita jako soucet jiz skutecne
 * nactenych polozek (ne pozadovaneho "max-results", ktery Blogger tise oriznul
 * na promenlivy pocet dle velikosti obsahu, typicky 4-15 na jeden pozadavek).
 * getChapterList proto vzdy pracuje jen s per-label feedem, nikdy s celym blogem.
 *
 * "manga.url" je primo label-filtrovany feed endpoint
 * ("$base/feeds/posts/default/-/{label}?alt=json"), stejny trik jako u
 * HidamarisouTranslationsSource - getChapterList pak nemusi label znovu resit.
 * Kazda polozka feedu uz obsahuje PLNY text prispevku (`content.$t`) i staticky
 * "self" odkaz na jednotlivy prispevek - ten se uklada jako SChapter.url
 * (+ "&alt=json") a znovu se natahuje az v getPageList, aby se cely text
 * nemusel drzet v pameti mezi getChapterList a getPageList.
 *
 * Razeni: vychozi poradi per-label feedu je nejnovejsi->nejstarsi (overeno
 * zive), appka presto pro jistotu vysledky pred cislovanim kapitol explicitne
 * seradi podle "published" casove znamky (starsi->novejsi), aby cislovani
 * sedelo i kdyby poradi feedu nekdy neodpovidalo.
 *
 * NOVEL typ obsahu - getPageList vraci `Page(0, text, "novel://text")` stejne
 * jako HidamarisouTranslationsSource/NovelFullSource, text ziskany stripnutim
 * HTML z `content.$t` pres `Jsoup.parse(html).text()`.
 */
@Singleton
class SkythewoodSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "skythewood"
    override val name = "Skythewood"
    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://skythewood.blogspot.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // Stitky, ktere na tomto blogu neoznacuji samostatnou serii, ale obecne/
    // mimotematicke prispevky (overeno zive projitim ukazky nadpisu/obsahu
    // pro kazdy z 37 stitku ve feedu).
    private val nonSeriesLabels = setOf(
        "translation", "light novel", "web novel", "rant", "cafe", "death",
        "summary", "lewd", "food", "beta", "shadowskyexe", "webcomic",
    )

    private fun decodeHtmlEntities(text: String): String = Jsoup.parse(text).text()

    private fun encodeLabel(label: String): String =
        URLEncoder.encode(label, "UTF-8").replace("+", "%20")

    private fun labelToManga(label: String): SManga = SManga(
        sourceId = id,
        url = "$base/feeds/posts/default/-/${encodeLabel(label)}?alt=json",
        title = decodeHtmlEntities(label),
        coverUrl = null,
    )

    private fun fetchAllLabels(): List<String> {
        val feed = JSONObject(get("$base/feeds/posts/default?alt=json&max-results=1")).optJSONObject("feed")
        val cats = feed?.optJSONArray("category") ?: return emptyList()
        return (0 until cats.length()).mapNotNull { i ->
            cats.optJSONObject(i)?.optString("term")?.trim()?.ifBlank { null }
        }.filter { it.lowercase() !in nonSeriesLabels }.distinct()
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page > 1) return@withContext emptyList()
            fetchAllLabels().map { labelToManga(it) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page > 1) return@withContext emptyList()
            fetchAllLabels().filter { it.contains(query, ignoreCase = true) }.map { labelToManga(it) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = manga

    private fun selfLink(entry: JSONObject): String? {
        val links = entry.optJSONArray("link") ?: return null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            if (l.optString("rel") == "self") return l.optString("href").ifBlank { null }
        }
        return null
    }

    private fun parseIsoDate(iso: String): Long = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: Exception) { 0L }

    private data class RawPost(val title: String, val selfHref: String, val published: Long)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val raws = mutableListOf<RawPost>()
            var start = 1
            var iterations = 0
            // Postupuje o skutecny pocet vracenych polozek (ne o pozadovany
            // "max-results") - viz komentar u tridy, proc je to nutne.
            while (iterations < 40) {
                iterations++
                val json = JSONObject(get("${manga.url}&max-results=150&start-index=$start"))
                val entries = json.optJSONObject("feed")?.optJSONArray("entry") ?: break
                if (entries.length() == 0) break
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val href = selfLink(e) ?: continue
                    val title = decodeHtmlEntities(e.optJSONObject("title")?.optString("\$t").orEmpty())
                        .ifBlank { "Chapter" }
                    val published = parseIsoDate(e.optJSONObject("published")?.optString("\$t").orEmpty())
                    raws += RawPost(title, href, published)
                }
                start += entries.length()
            }
            raws.sortedBy { it.published }.mapIndexed { idx, r ->
                SChapter(
                    sourceId = id, mangaUrl = manga.url,
                    url = "${r.selfHref}?alt=json",
                    name = r.title, chapterNumber = (idx + 1).toFloat(),
                    dateUpload = r.published,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val entry = JSONObject(get(chapter.url)).optJSONObject("entry") ?: return@withContext emptyList()
            val html = entry.optJSONObject("content")?.optString("\$t").orEmpty()
            val text = Jsoup.parse(html).text().trim()
            if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}

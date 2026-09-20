package com.haise.jiyu.source.comic

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
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
 * Web je za Cloudflare, ale navíc má na úrovni originu VLASTNÍ JS+PoW anti-bot bránu
 * (ověřeno živě) - appce specificky (ne prohlížeči) servíruje STEJNOU výzvu, jakou by
 * dostal skutečný prohlížeč, jen schovanou pod nevinně vyhlížející 404 misto 403, aby
 * odradila automatizovane opakovane pokusy. `CloudflareInterceptor` proto detekci bloku
 * nerozhoduje jen podle 403/503 (viz `isCloudflareBlocked`), a úspěch WebView řešení
 * nekontroluje jen podle cookie `cf_clearance` (ten tenhle vlastní gate nemusí vubec
 * nastavit) - staci, ze se WebView po dokonceni navigace realne vrati na puvodni cilovou
 * URL.
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
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun postJson(url: String, json: JSONObject): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    // Protokol-relativni URL ("//cdn...") by se jinak slepila s `base` misto spravneho
    // "https:" - stejna oprava jako ComicSiteSource.absoluteUrl(). Obalky karet (viz
    // parseListing) davaji cesty k obrazkum VZDY relativni ("/uploads/..."), bez tohohle
    // by appka poslala Coilu neplatnou URL bez schematu/hostitele a obalka by se nenacetla
    // (nahlaseny bug - "obrázky se nenačítají").
    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http", ignoreCase = true) -> raw
        raw.startsWith("//") -> "https:$raw"
        else -> "$base$raw"
    }

    // Web pouziva DVA ruzne layouty karet podle stranky: "/comix/" (obecny vypis i
    // vysledky hledani) ma `.readed`/`.latest` karty s oddelenym title odkazem, zatimco
    // "/watched/" ("Popular now", viz getPopular) ma jednodussi `.poster` karty, kde je
    // href primo na obalujicim <a>, ne na vnorenem title odkazu (nahlaseny bug - appka na
    // "/watched/" pri puvodnim selektoru nikdy nic nenasla, Popularni a Nejnovejsi tak
    // vzdy vracely stejny seznam z "/comix/").
    private fun parseListing(doc: Document): List<SManga> {
        val readedCards = doc.select("#dle-content > .readed, #content-load > .latest").mapNotNull { el ->
            val link = el.selectFirst(".readed__title > a, .latest__title > a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            val img = el.selectFirst("img")
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = link.text().trim(),
                coverUrl = img?.attr("data-src")?.ifBlank { img.attr("src") }?.ifBlank { null }?.let(::absoluteUrl),
                contentType = "COMIC",
            )
        }
        if (readedCards.isNotEmpty()) return readedCards

        return doc.select("#dle-content > .poster").mapNotNull { el ->
            val href = el.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst(".poster__title")?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val img = el.selectFirst("img")
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = title,
                coverUrl = img?.attr("data-src")?.ifBlank { img.attr("src") }?.ifBlank { null }?.let(::absoluteUrl),
                contentType = "COMIC",
            )
        }
    }

    // Web nema vlastni "/genres/" index stranku (overeno zive - 404), seznam je proto
    // natvrdo opsany z postrannich odkazu na homepage (overeno zive). `id` je presne ten
    // uz URL-enkodovany tvar cesty, jak ho web sam pouziva ("%20" pro mezeru, "%26" pro
    // "&") - staci ho dosadit primo do URL bez dalsiho enkodovani.
    private val genres = listOf(
        FilterTag(id = "graphic%20novels", label = "Graphic Novels"),
        FilterTag(id = "action", label = "Action"),
        FilterTag(id = "horror", label = "Horror"),
        FilterTag(id = "superhero", label = "Superhero"),
        FilterTag(id = "supernatural", label = "Supernatural"),
        FilterTag(id = "fantasy", label = "Fantasy"),
        FilterTag(id = "romance", label = "Romance"),
        FilterTag(id = "movies%20%26%20tv", label = "Movies & TV"),
        FilterTag(id = "historical", label = "Historical"),
        FilterTag(id = "pulp", label = "Pulp"),
        FilterTag(id = "zombies", label = "Zombies"),
        FilterTag(id = "adventure", label = "Adventure"),
        FilterTag(id = "sci-fi", label = "Sci-Fi"),
        FilterTag(id = "western", label = "Western"),
        FilterTag(id = "vampires", label = "Vampires"),
        FilterTag(id = "robots", label = "Robots"),
        FilterTag(id = "war", label = "War"),
        FilterTag(id = "crime", label = "Crime"),
        FilterTag(id = "video%20games", label = "Video Games"),
        FilterTag(id = "mythology", label = "Mythology"),
        FilterTag(id = "mystery", label = "Mystery"),
        FilterTag(id = "military", label = "Military"),
        FilterTag(id = "comedy", label = "Comedy"),
        FilterTag(id = "drama", label = "Drama"),
        FilterTag(id = "martial%20arts", label = "Martial Arts"),
        FilterTag(id = "suspense", label = "Suspense"),
    )

    override val supportsTagFilter: Boolean get() = true

    override suspend fun getAvailableTags(): List<FilterTag> = genres

    // Stejna karetni struktura (.readed) a strankovani jako "/comix/" (overeno zive:
    // "/genres/horror/page/2/" vraci jine tituly nez strana 1).
    private fun genreUrl(slug: String, page: Int) =
        if (page <= 1) "$base/genres/$slug/" else "$base/genres/$slug/page/$page/"

    // "/watched/" ("Popular now" v navigaci webu) je oddelena stranka od obecneho "/comix/"
    // vypisu - ma VLASTNI (mensi, kurátorsky vybrany) seznam bez dalsiho strankovani
    // (/watched/page/2/ i /watched/?page=2 obe overene vraci 404 - viz komentar u parseListing),
    // proto se `page > 1` rovnou vraci prazdny seznam misto zbytecneho requestu, co stejne
    // nikdy nic nenajde. "/comix/" naopak strankovani ma a je razeny od nejnovejsiho - proto
    // ho appka pouziva i pro "Nejnovejsi" (filter.sortBy == "latest").
    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Vybrany zanr ma prednost pred razenim - archiv zanru nema vlastni "nejnovejsi"
            // vs "popularni" rozliseni, jen jednu (chronologickou) strankovanou sadu.
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            if (filter.sortBy != "latest") {
                if (page > 1) return@withContext emptyList()
                return@withContext parseListing(Jsoup.parse(get("$base/watched/")))
            }
            val url = if (page > 1) "$base/comix/page/$page/" else "$base/comix/"
            parseListing(Jsoup.parse(get(url)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.genres.isNotEmpty()) {
                return@withContext parseListing(Jsoup.parse(get(genreUrl(filter.genres.first(), page))))
            }
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = buildString {
                append(base).append("/search/").append(encoded)
                if (page > 1) append("/page/").append(page).append("/")
            }
            parseListing(Jsoup.parse(get(url)))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Textový obsah `<li>` v postranním seznamu detailu (Publisher/Writer/Artist/...), bez odkazu samotného. */
    private fun Document.pageListValue(label: String): String? =
        selectFirst(".page__list > li:has(> div:contains($label)) > a")?.text()?.trim()?.ifBlank { null }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val publisher = doc.pageListValue("Publisher")
            val description = buildString {
                if (publisher != null) append(publisher)
                appendLine()
                append(doc.selectFirst("div.page__text")?.text().orEmpty())
            }.trim()
            val releaseType = doc.selectFirst(".page__list > li:has(> div:contains(Release type))")?.ownText()?.trim()
            manga.copy(
                title = doc.selectFirst("header.page__header h1")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst("div.page__poster img")?.attr("src")?.ifBlank { null }?.let(::absoluteUrl) ?: manga.coverUrl,
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
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    // Seznam kapitol neni v HTML, ale v JSON bloku vlozenem primo do stranky - viz dokumentace
    // tridy. `chapter.url` si ulozime jako "comicId/chapterId/xhash", getPageList si to zpatky
    // rozparsuje (xhash je potreba poslat spolu s id, jinak API odpovi chybou).
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            // Lokalni, ne sdilene pole na tride - SimpleDateFormat neni thread-safe a tahle
            // trida je @Singleton, takze soubezne getChapterList() z ruznych mang by sdilenou
            // instanci mohly poskodit/hodit vyjimku (nahlaseny bug).
            val chapterDateFormat = SimpleDateFormat("d.M.yyyy", Locale.US)
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
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
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val parts = chapter.url.split("/", limit = 3)
            // Srozumitelna chyba (zachycena catch blokem nize stejne jako kazda jina) misto
            // neprehledne IndexOutOfBoundsException, kdyby chapter.url nekdy nesedelo na
            // ocekavany tvar "comicId/chapterId/xhash".
            if (parts.size < 3) throw java.io.IOException("Neplatné BatCave chapter.url (chybí comicId/chapterId/xhash): \"${chapter.url}\"")
            val (comicId, chapterId, xhash) = Triple(parts[0], parts[1], parts[2])
            val body = JSONObject().apply {
                put("news_id", comicId)
                put("chapter_id", chapterId)
                // Bez tohohle API vraci chybu (viz komentar u getChapterList) - drive se
                // xhash z chapter.url naparsoval, ale do requestu se nikdy neposlal
                // (nahlaseny bug).
                put("xhash", xhash)
            }
            val response = postJson("$base/engine/ajax/controller.php?mod=api&action=reader/getChapterData", body)
            val images = JSONObject(response).optJSONObject("data")?.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).map { i ->
                Page(index = i, url = absoluteUrl(images.getString(i).trim()))
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}

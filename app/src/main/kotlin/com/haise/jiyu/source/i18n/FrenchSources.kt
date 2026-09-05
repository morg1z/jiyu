package com.haise.jiyu.source.i18n

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

// ── Japscan (FR) ──────────────────────────────────────────────────────────────
@Singleton
class JapscanSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "japscan"
    override val name     = "Japscan 🇫🇷"
    override val language = "fr"
    override val homepageUrl get() = base
    // japscan.lol presmerovava na tuto novou domenu ("Nous avons demenage" -
    // "prestehovali jsme se") - zjisteno auditem 2026-07-27. Nova domena je
    // navic za Cloudflare JS vyzvou (spoleha na existujici CloudflareInterceptor).
    private val base      = "https://www.japscan.foo"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            Jsoup.parse(get("$base/mangas/$page/")).select(".d-flex.flex-column a.text-dark").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href,
                    title    = a.text().trim().ifBlank { return@mapNotNull null },
                    coverUrl = a.selectFirst("img")?.attr("src"))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/search/"))
            // Japscan search is JS-driven; fallback to popular
            doc.select(".card .card-body a").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                SManga(sourceId = id, url = href, title = a.text().trim(), coverUrl = null)
            }.filter { it.title.contains(query, ignoreCase = true) }
                .ifEmpty { getPopular(page, filter) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title       = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                coverUrl    = doc.selectFirst(".d-flex img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("p.m-0")?.text()?.trim(),
                genres      = doc.select("a[href*='/tags/']").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            doc.select("#chapters_list .chapters_list a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Chapitre ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("div#images img, .reading-content img").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ── Anime-Sama (FR) ───────────────────────────────────────────────────────────
// Domena se v roce 2026 zmenila z anime-sama.fr (mrtva) na anime-sama.to a web
// prosel kompletnim redesignem (Tailwind). Seznam kapitol/stranek NENI ve
// statickem HTML (generuje se JS souborem scans.js) - misto scrapovani DOM se
// proto vola primo interni JSON API webu, kterou scans.js sam pouziva:
//   GET /s2/scans/get_nb_chap_et_img.php?oeuvre={presny nazev z #titreOeuvre}
//   -> {"1": pocetStranek, "2": pocetStranek, ...}
//   obrazky: /s2/scans/{presny nazev}/{cislo kapitoly}/{cislo stranky}.jpg
// Presny nazev dila (z #titreOeuvre na podstrance .../scan/vf/) se muze lisit
// velikosti pismen/formatovanim od nazvu v katalogu, proto se nacita zvlast.
@Singleton
class AnimeSamaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "animesama"
    override val name     = "Anime-Sama 🇫🇷"
    override val language = "fr"
    override val homepageUrl get() = base
    private val base      = "https://anime-sama.to"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    private fun parseList(html: String): List<SManga> =
        Jsoup.parse(html).select(".catalog-card").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("h2.card-title")?.text()?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val cover = el.selectFirst("img.card-image")?.attr("src")?.takeIf { it.startsWith("http") }
            SManga(sourceId = id, url = if (href.startsWith("http")) href else "$base$href", title = title, coverUrl = cover)
        }

    // Katalog ma vlastni panel filtru s checkboxy "genre[]" (109 hodnot, zive
    // overeno) - hodnoty jsou uz hotovy zobrazitelny text (napr. "Réincarnation
    // / Transmigration"), zadne dalsi mapovani na label netreba. Kombinace
    // vice zanru najednou nebyla zive overena jako jednoznacne AND/OR (obe
    // varianty vratily stejny pocet vysledku = zrejme oriznuto na velikost
    // stranky), proto se stejne jako u Madary pouziva jen prvni vybrany zanr.
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/catalogue/?type=manga&sort=vues"))
            val tags = doc.select("input[name=genre[]]").mapNotNull { input ->
                val value = input.attr("value").trim().ifBlank { return@mapNotNull null }
                FilterTag(id = value, label = value)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    private fun genreQueryParam(filter: MangaFilter): String =
        filter.genres.firstOrNull()?.let { "&genre%5B%5D=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseList(get("$base/catalogue/?page=$page&type=manga&sort=vues${genreQueryParam(filter)}")) } catch (_: Exception) { emptyList() }
    }

    // Katalog nema server-side fulltextove hledani (search stranka vraci
    // prazdny vysledek bez JS) - search proto stahne prvni stranku a filtruje
    // lokalne, stejny vzor jako [com.haise.jiyu.source.hachirumi.HachirumiSource].
    // Genre filtr (na rozdil od textoveho hledani) uz server-side funguje
    // (zive overeno), proto se stejny query parametr posila i sem.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            parseList(get("$base/catalogue/?type=manga&sort=vues${genreQueryParam(filter)}")).filter { it.title.contains(query, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            manga.copy(
                title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title,
                description = doc.selectFirst("#synopsisText")?.text()?.trim()?.takeIf { it.isNotBlank() },
                genres = doc.select("span.genre-pill").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    private fun scanUrl(mangaUrl: String) = mangaUrl.trimEnd('/') + "/scan/vf/"

    private fun oeuvreName(mangaUrl: String): String? =
        Jsoup.parse(get(scanUrl(mangaUrl))).selectFirst("#titreOeuvre")?.text()?.trim()?.ifBlank { null }

    private fun chapterCounts(oeuvre: String): org.json.JSONObject =
        org.json.JSONObject(get("$base/s2/scans/get_nb_chap_et_img.php?oeuvre=${URLEncoder.encode(oeuvre, "UTF-8")}"))

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val oeuvre = oeuvreName(manga.url) ?: return@withContext emptyList()
            val counts = chapterCounts(oeuvre)
            val names = counts.names() ?: return@withContext emptyList()
            (0 until names.length()).mapNotNull { i ->
                val key = names.getString(i)
                val num = key.toFloatOrNull() ?: return@mapNotNull null
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "${scanUrl(manga.url)}$key",
                    name = "Chapitre $key",
                    chapterNumber = num,
                    dateUpload = 0L,
                )
            }.sortedBy { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapNum = chapter.url.substringAfterLast('/')
            val oeuvre = oeuvreName(chapter.mangaUrl) ?: return@withContext emptyList()
            val nbImages = chapterCounts(oeuvre).optInt(chapNum, 0)
            if (nbImages <= 0) return@withContext emptyList()
            val encodedOeuvre = URLEncoder.encode(oeuvre, "UTF-8").replace("+", "%20")
            (1..nbImages).map { i ->
                val url = "$base/s2/scans/$encodedOeuvre/$chapNum/$i.jpg"
                Page(i - 1, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

// ── ScanVF (FR) ───────────────────────────────────────────────────────────────
@Singleton
class ScanVFSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id       = "scanvf"
    override val name     = "ScanVF 🇫🇷"
    override val language = "fr"
    override val homepageUrl get() = base
    private val base      = "https://www.scan-vf.net"

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base).build()
    ).execute().use { it.bodyOrThrow(url) }

    // Web prošel redesignem na Bootstrap "media" karty (audit 2026-07-27) - stare
    // selektory (.manga-poster/.bsx/.novel-item) uz nikde v HTML neexistuji, proto
    // appka vzdy vracela prazdny seznam. Karta: div.media > div.media-left a.thumbnail
    // (obalka obrazku) + div.media-body h5.media-heading a.chart-title (nazev+odkaz).
    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseMediaList(Jsoup.parse(get(filterListUrl(filter.genres.first(), page)))) }
            catch (_: Exception) { emptyList() }
        }
        try {
            parseMediaList(Jsoup.parse(get("$base/manga-list?page=$page&sort=views")))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Genre archiv (AJAX "/filterList?cat=...") nema parametr pro fulltextovy
        // dotaz zaroven - stejne jako u Madary tak pri vybranem zanru filtr
        // vyhrava a textovy dotaz se ignoruje.
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseMediaList(Jsoup.parse(get(filterListUrl(filter.genres.first(), page)))) }
            catch (_: Exception) { emptyList() }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseMediaList(Jsoup.parse(get("$base/?s=$q")))
        } catch (_: Exception) { emptyList() }
    }

    private fun parseMediaList(doc: org.jsoup.nodes.Document): List<SManga> =
        doc.select("div.media").mapNotNull { el ->
            val a = el.selectFirst(".media-heading a.chart-title") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val titleText = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SManga(sourceId = id, url = href,
                title    = titleText,
                coverUrl = el.selectFirst(".media-left img")?.attr("src"))
        }

    // Detailni strana kazde mangy odkazuje na sve zanry pres "/manga-list/category/{slug}"
    // (dt "Catégories" v postrannim panelu, zive overeno), ale sidebar na /manga-list
    // ("Parcourir suivant la categorie") ma navic hotovy kompletni seznam vsech 32 zanru
    // s citelnymi anglickymi nazvy a numeric "?cat=N" odkazem - numericke ID i slug funguji
    // v AJAX endpointu /filterList stejne (zive overeno, oba vraci identicky vysledek),
    // takze appka pouziva numericke ID z tohodle sidebaru jako [FilterTag.id].
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/manga-list"))
            val tags = doc.select("ul.list-category a.category").mapNotNull { a ->
                val catId = Regex("""[?&]cat=([^&]+)""").find(a.attr("href"))?.groupValues?.get(1)
                    ?.ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = catId, label = label)
            }.distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (_: Exception) { emptyList() }
    }

    // Radi vzdy podle zhlednuti (sestupne) - stejna vychozi logika, jakou uz getPopular
    // pouziva pro nefiltrovany vypis ("sort=views"), jen pres jiny (AJAX) endpoint.
    private fun filterListUrl(catId: String, page: Int): String {
        val cat = URLEncoder.encode(catId, "UTF-8")
        return "$base/filterList?page=$page&cat=$cat&alpha=&sortBy=views&asc=false&author=&artist=&tag="
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            val author = doc.select("dt:matchesOwn((?i)Auteur)").first()?.nextElementSibling()?.text()?.trim()
            val status = doc.select("dt:matchesOwn((?i)Statut)").first()?.nextElementSibling()?.text()?.trim()
            manga.copy(
                coverUrl    = doc.selectFirst(".thumbnail img, img[itemprop=image]")?.attr("src") ?: manga.coverUrl,
                author      = author?.takeIf { it.isNotBlank() },
                status      = status?.takeIf { it.isNotBlank() },
                genres      = doc.select(".tag-links a").map { it.text().trim() }.filter { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    // Kapitoly jsou v h5.chapter-title-rtl > a (ne .chapter-list li a - ten uz neexistuje).
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(manga.url))
            doc.select("h5.chapter-title-rtl a").mapIndexed { i, a ->
                val href = a.attr("href")
                val name = a.text().trim().ifBlank { "Chapitre ${i + 1}" }
                SChapter(sourceId = id, mangaUrl = manga.url, url = href, name = name,
                    chapterNumber = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: (i + 1).toFloat(),
                    dateUpload = 0L)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(chapter.url))
            doc.select("img.img-responsive").mapIndexedNotNull { i, img ->
                val url = img.attr("data-src").trim().ifBlank { img.attr("src").trim() }.takeIf { it.startsWith("http") }
                    ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}

private fun String?.ifNullOrBlank(fallback: () -> Nothing): String =
    if (this.isNullOrBlank()) fallback() else this

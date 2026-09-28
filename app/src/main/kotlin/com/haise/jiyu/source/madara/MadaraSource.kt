package com.haise.jiyu.source.madara

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

enum class MadaraCommentStyle { WPDISCUZ, NATIVE_WP }

/**
 * CSS selektory pro parsování Madara markupu. Výchozí hodnoty odpovídají
 * nezměněnému Madara tématu; pole s `null` v [CustomSourceEntity] použijí
 * odpovídající výchozí hodnotu z [DEFAULT] - přepis je potřeba jen pokud
 * konkrétní web téma upravil a výchozí selektor tam nesedí.
 */
data class MadaraSelectors(
    val listItem: String = "div.page-item-detail, div.c-tabs-item__content",
    // Poradi zde je PRIORITA vyhledavani (viz firstMatchInPriorityOrder), ne
    // CSS selektor vyhodnoceny naraz - u nekterych motivu (napr. Lilymanga)
    // karta obsahuje i odkaz na "posledni kapitolu" DRIV v DOM nez odkaz na
    // titul, takze prosty combined-selector `selectFirst` by chytil spatny
    // odkaz (kapitolu misto detailu mangy) - pak getChapterList volal AJAX na
    // nesmyslnou URL a vzdy vratil prazdno ("zadne kapitoly" pro kazdy titul).
    val titleLink: String = ".post-title a, a[href]",
    val description: String = "div.summary__content, div.description-summary",
    val status: String = "div.post-status .summary-content, .post-content_item .summary-content",
    val chapterList: String = "li.wp-manga-chapter",
    // Nektere Madara weby renderuji stranky do <canvas class="protected-chapter-canvas"
    // data-page-url="..."> (obrazek se kresli JS, img vubec neni - napr. readhunters.xyz).
    // URL stranky je v data-page-url, ktere lazySrc() umi precist.
    val pageImage: String =
        "div.reading-content img, div.page-break img, div.reading-content canvas[data-page-url], canvas.protected-chapter-canvas[data-page-url]",
    val novelContent: String = "div.reading-content p",
    /** null = zdroj (tenhle konkretni web) komentare k pripadne kapitole neposkytuje, nebo
     * pouziva Disqus (nescrapovatelny bez JS) - vetsina Madara webu. Nastavuje se explicitne
     * jen pro zive overene weby (viz SourceManager.kt). */
    val commentStyle: MadaraCommentStyle? = null,
) {
    companion object {
        val DEFAULT = MadaraSelectors()
    }
}

/**
 * Generický zdroj pro weby postavené na Madara - WordPress šabloně,
 * kterou používá velké množství manga/manhwa/manhua webů bez oficiálního API.
 *
 * Nekonfiguruje se natvrdo na konkrétní web - `baseUrl` a `name` zadává
 * uživatel v Nastavení ("Vlastní zdroje"). CSS selektory v [MadaraSelectors]
 * odpovídají výchozímu, nezměněnému Madara markupu, ale lze je pro
 * konkrétní web přepsat, pokud tam téma upravilo strukturu.
 */
class MadaraSource(
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val selectors: MadaraSelectors = MadaraSelectors.DEFAULT,
    private val contentTypeOverride: String = "MANGA",
    private val isAdultOverride: Boolean = false,
    // Vetsina Madara webu pouziva vychozi WordPress permalink strukturu
    // ("/manga/page/N/?m_orderby=", "/page/N/?s=...&post_type=wp-manga"),
    // ale nektere weby maji vlastni post-type/taxonomy slug (napr.
    // manhwaz.com pouziva pro archiv "/genre/manga?page=N" misto
    // "/manga/page/N/"). Tyhle dvě lambdy jdou pro takove weby přepsat,
    // vychozi hodnota odpovida standardnimu Madara motivu beze zmeny.
    // Cesta výpisu titulů ("manga" u většiny webů, jinde "series", "comics", "webtoon" ...) a předpona
    // archivu žánrů ("genre", jinde "manga-genre") - vychází z nich výchozí [popularUrl] a [genreUrl].
    private val listPath: String = "manga",
    private val tagPrefix: String = "genre",
    private val popularUrl: (root: String, page: Int, orderby: String) -> String =
        { root, page, orderby -> "$root/$listPath/page/$page/?m_orderby=$orderby" },
    private val searchUrl: (root: String, query: String, page: Int) -> String =
        { root, query, page -> "$root/page/$page/?s=$query&post_type=wp-manga" },
    // Standardni Madara "wp-manga-genre" taxonomie ma rewrite slug "genre" a stejnou
    // /page/N/ paginaci jako archiv - overeno zive na toonily.com (odlisne tituly na
    // strance 1 vs 2). Prepsatelne pro weby, kde je taxonomie jinak pojmenovana.
    private val genreUrl: (root: String, slug: String, page: Int) -> String =
        { root, slug, page -> "$root/$tagPrefix/$slug/page/$page/" },
    /** Jazyk webu (BCP-47) - ukazuje se na kartě zdroje a řídí filtr jazyků v Procházet. */
    private val languageOverride: String = "en",
    /** Vzor data kapitoly konkrétního webu (např. "dd/MM/yyyy"); zkouší se před obecným parserem. */
    private val datePattern: String? = null,
    private val inGlobalSearch: Boolean = true,
    /** UA pro vsechny pozadavky zdroje. Nektere Madara weby za Cloudflare blokuji
     * desktop UA interaktivni vyzvou, ale mobilni UA pusti - viz USER_AGENT_ANDROID. */
    private val userAgent: String = BROWSER_USER_AGENT,
    /** Nektere Madara instalace zanrovou taxonomii vubec nevystavuji (zadna
     * /search/ stranka, zadne {tagPrefix}/ odkazy v archivu) - filtr by pak v UI
     * ukazal prazdny seznam, takze se pro takove weby vypina konfiguraci. */
    private val supportsTags: Boolean = true,
    /** Nektere weby strankovani v archivu ignoruji (page=N vraci identickou
     * stranku 1 - audit DUP) - pro ne se page>1 ukonci prazdnym vysledkem
     * misto nekonecneho opakovani scrollu. */
    private val hasPagination: Boolean = true,
    /** Web "m_orderby" parametr ignoruje (latest vraci identicky obsah jako
     * popular - audit) - pro takove se UI prepinac razeni vypina. */
    private val supportsSorts: Boolean = true,
    /** Filtr stavu serializace (status[] GET param madara-core vyhledavani -
     * on-going/end/on-hold/canceled). Funguje i na webech, co status ve
     * viditelnem formulari nemaji (madara-core ho zpracovava vzdy -
     * overeno zive na mangaread.org 2026-10). */
    private val supportsStatus: Boolean = true,
) : MangaSource {

    override val includeInGlobalSearch: Boolean get() = inGlobalSearch

    override val language: String get() = languageOverride

    override val contentType: String get() = contentTypeOverride
    override val homepageUrl: String get() = baseUrl
    override val isAdult: Boolean get() = isAdultOverride

    override val supportsChapterComments: Boolean get() = selectors.commentStyle != null

    override suspend fun getChapterComments(chapter: SChapter): List<com.haise.jiyu.source.comments.ChapterComment> =
        withContext(Dispatchers.IO) {
            val style = selectors.commentStyle ?: return@withContext emptyList()
            try {
                val doc = fetchDocument(chapter.url)
                when (style) {
                    MadaraCommentStyle.WPDISCUZ -> com.haise.jiyu.source.comments.parseWpDiscuzComments(doc)
                    MadaraCommentStyle.NATIVE_WP -> com.haise.jiyu.source.comments.parseNativeWpComments(doc)
                }
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }

    private val root get() = baseUrl.trimEnd('/')

    companion object {
        // Bez explicitniho User-Agent/Referer pouzival OkHttp svuj vlastni
        // ("okhttp/4.x") - snadno rozpoznatelny jako bot, coz nekterym webum
        // (napr. linkmanga.com/Mangalink, nahlaseno jako HTTP 403) stacilo k
        // odmitnuti requestu, i kdyz web samotny zadny JS/Cloudflare challenge
        // nema (na rozdil od skutecne Cloudflare-chranenych webu, kde tohle
        // samo o sobe nestaci).
        private const val BROWSER_USER_AGENT =
            SourceHttp.USER_AGENT_DESKTOP

        // Ruzne Madara motivy formatuji datum vydani jinak - zkousi se v tomto poradi
        // (viz parseRelativeOrAbsoluteDate).
        private val ABSOLUTE_DATE_FORMATS = listOf("MMMM d, yyyy", "MMM d, yyyy", "yyyy-MM-dd", "dd/MM/yyyy")

        // Interni slugy wp-manga-status taxonomie (madara-core je instaluje
        // fixne; "end" = dokonceno, "on-hold" = pozastaveno).
        private val MADARA_STATUS_SLUGS = mapOf(
            "ongoing" to "on-going",
            "completed" to "end",
            "hiatus" to "on-hold",
            "cancelled" to "canceled",
        )
    }

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    override val supportsTagFilter: Boolean get() = supportsTags
    override val supportsSortOrder: Boolean get() = supportsSorts
    override val availableSorts: Set<String> get() =
        if (supportsSorts) setOf("popular", "latest", "title") else setOf("popular")
    override val supportsStatusFilter: Boolean get() = supportsStatus
    // madara-core zna ctyri stavy: on-going / end / on-hold / canceled.
    override val availableStatuses: List<String> get() =
        if (supportsStatus) listOf("ongoing", "completed", "hiatus", "cancelled") else emptyList()

    // /search/ stranka je soucast madara-core pluginu (ne motivu), takze genre[]
    // checkboxy tam maji napric weby stejny HTML tvar - lisi se jen skutecne
    // hodnoty/slugy, ktere se ale nacitaji zive primo z webu, ne natvrdo.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            // /search/ na mnoha Madara webech vubec neexistuje (404) a
            // fetchDocument na ni haze IOException - ta by shodila i fallback
            // na zanrove odkazy v archivu. Proto se 404 tiše propadne na null.
            val doc = try {
                fetchDocument("$root/search/")
            } catch (e: Exception) { e.rethrowIfControl(); null }
            val tags = doc?.select("label.genre-item")?.mapNotNull { label ->
                // Nektere weby maji ve value uz percent-encoded slug (napr. arabske
                // taxonomie) - dekodujeme, jinak by genreUrl slug znovu zakodoval
                // na %25 a archivni stranka by vratila prazdno. No-op pro ascii.
                val slug = label.selectFirst("input[name=genre[]]")?.attr("value")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?.takeIf(::isSafeGenreSlug) ?: return@mapNotNull null
                val text = label.selectFirst("span")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = text)
            }.orEmpty().ifEmpty {
                // Nektere Madara weby (novelove child-themes) nemaji /search/ formular -
                // fallback: zanrove odkazy na archivni strance /{listPath}/ a homepage
                // (a[href*={tagPrefix}/]), pokud tam web taxonomii opravdu vystavuje.
                listOf("$root/$listPath/", root).asSequence().flatMap { url ->
                    try {
                        fetchDocument(url).select("a[href*='/$tagPrefix/']").asSequence()
                    } catch (e: Exception) { e.rethrowIfControl(); emptySequence() }
                }.mapNotNull { a ->
                    val slug = a.attr("href").substringAfter("/$tagPrefix/").trim('/')
                        .substringBefore('/')
                        .let { java.net.URLDecoder.decode(it, "UTF-8") }
                        .takeIf(::isSafeGenreSlug) ?: return@mapNotNull null
                    FilterTag(id = slug, label = a.text().trim().ifBlank { slug })
                }.distinctBy { it.id }.toList()
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: com.haise.jiyu.source.MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (page > 1 && !hasPagination) return@withContext emptyList()
            // Cisty genre archiv (jeden zanr, bez dotazu a stavu) ma nejcistsi
            // strankovani - jinak GET formular post_type=wp-manga, ktery na
            // rozdil od archivu umi kombinovat query + N zanru + status.
            if (filter.genres.size == 1 && query.isBlank() && filter.status == null) {
                return@withContext fetchGenrePage(filter.genres.first(), page)
            }
            if (filter.genres.isEmpty() && filter.status == null) {
                // Bez filtru zustava instancni searchUrl override funkcni
                // (custom weby meni cestu/pravidla hledani).
                return@withContext parseMangaList(fetchDocument(searchUrl(root, query, page)))
            }
            parseMangaList(fetchDocument(getFormUrl(query, page, filter)))
        }

    override suspend fun getPopular(page: Int, filter: com.haise.jiyu.source.MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (page > 1 && !hasPagination) return@withContext emptyList()
            // Stejne routovani jako search(): cisty jednozanr do archivu, kombinace
            // (status / vice zanru) do GET formulare, ktery oboji umi najednou.
            if (filter.status != null || filter.genres.size > 1) {
                return@withContext parseMangaList(fetchDocument(getFormUrl("", page, filter)))
            }
            if (filter.genres.size == 1) {
                return@withContext fetchGenrePage(filter.genres.first(), page)
            }
            val orderby = when (filter.sortBy) {
                "latest" -> "latest"
                "title"  -> "alphabet"
                else     -> "views"
            }
            val url = popularUrl(root, page, orderby)
            try {
                parseMangaList(fetchDocument(url))
            } catch (e: java.io.IOException) {
                // Na strane >1 je 404 legitimni konec katalogu (male weby nemaji
                // dost titulu na dalsi stranu) - vratime prazdno, aby se scroll
                // ukoncil ciste misto chyboveho stavu. Na p1 chybu propagujeme,
                // protoze tam znamena spatny listPath.
                if (page > 1 && e.message?.contains("404") == true) emptyList() else throw e
            }
        }

    /** GET formular madara-core (`/?s=&post_type=wp-manga&genre[]=&status[]=`).
     * Podporuje na rozdil od zanroveho archivu kombinaci dotazu, vice zanru
     * a stavu zaroven. Status slugy jsou interni termy wp-manga-status
     * taxonomie (madara-core je instaluje s fixnimi slugy). */
    private fun getFormUrl(query: String, page: Int, filter: com.haise.jiyu.source.MangaFilter): String =
        StringBuilder(root)
            .apply { if (page > 1) append("/page/").append(page) }
            .append("/?s=").append(URLEncoder.encode(query, "UTF-8"))
            .append("&post_type=wp-manga")
            .apply {
                filter.genres.forEach {
                    append("&genre[]=").append(URLEncoder.encode(it, "UTF-8"))
                }
                MADARA_STATUS_SLUGS[filter.status]?.let { append("&status[]=").append(it) }
                when (filter.sortBy) {
                    "latest" -> append("&orderby=latest")
                    "title"  -> append("&orderby=alphabet")
                    else     -> append("&orderby=views")
                }
            }
            .toString()

    /**
     * Dekodovany slug z webu (URLDecoder nad value/href) muze obsahovat '/', '?', '#', '&'
     * nebo mezery - v genreUrl by se vlepily do cesty a vyrobily traversing query do
     * jine cesty na webu (audit). Pustime jen slugs bez nebezpecnych znaku (unicode
     * taxonomie OK).
     */
    private fun isSafeGenreSlug(slug: String): Boolean =
        slug.isNotBlank() && slug.none { it == '/' || it == '?' || it == '#' || it == '&' || it.isWhitespace() }

    /** Zanrova archivni stranka - 404 na strane >1 znamena konec vysledku, ne chybu. */
    private fun fetchGenrePage(slug: String, page: Int): List<SManga> =
        if (page > 1 && !hasPagination || !isSafeGenreSlug(slug)) emptyList() else try {
            parseMangaList(fetchDocument(genreUrl(root, slug, page)))
        } catch (e: java.io.IOException) {
            if (page > 1 && e.message?.contains("404") == true) emptyList() else throw e
        }

    /**
     * Zkusí jednotlivé čárkou oddělené části [combinedSelector] POSTUPNĚ podle
     * pořadí (na rozdíl od `Element.selectFirst("a, b")`, který v Jsoup vrací
     * první SHODU V DOM POŘADÍ napříč oběma částmi najednou, ne podle priority).
     */
    private fun firstMatchInPriorityOrder(item: Element, combinedSelector: String): Element? =
        combinedSelector.split(",").map { it.trim() }.firstNotNullOfOrNull { part -> item.selectFirst(part) }

    private fun parseMangaList(doc: Document): List<SManga> {
        val items = doc.select(selectors.listItem)
        return items.mapNotNull { item ->
            val link = firstMatchInPriorityOrder(item, selectors.titleLink) ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { link.text() }.ifBlank { return@mapNotNull null }
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val cover = item.selectFirst("img")?.let { img ->
                img.lazySrc().orEmpty()
            }?.trim()?.ifBlank { null }

            SManga(sourceId = id, url = url, title = title, coverUrl = cover, contentType = contentTypeOverride)
        }
    }

    // ─── Detail mangy ────────────────────────────────────────────────────────

    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(manga.url)
            val desc = doc.selectFirst(selectors.description)
                ?.text()?.ifBlank { null }
            val status = doc.selectFirst(selectors.status)
                ?.text()?.trim()?.ifBlank { null }
            // Řada Madara webů (např. mangaread.org) hostí manga, manhwa i manhua
            // dohromady, takže pevný contentTypeOverride pro celý web sedí jen v
            // průměru - konkrétní titul může být jiný (ověřeno živě: "Return of the
            // Legendary Spear Knight" je na mangaread.org uvedený jako Manhwa, ale
            // web má override "MANGA"). Standardní Madara šablona ale u každého
            // titulu sama uvádí přesný typ v poli "Type" (stejná struktura jako pole
            // Status, jen jiný nadpis) - když ho najdeme a rozpoznáme, věří se mu
            // víc než odhadu za celý web.
            //
            // Pole "Type" ale na mangaread.org nemá u každého titulu stejný význam -
            // ověřeno živě na "The Former Supreme": stejná struktura, ale obsahem je
            // seznam alternativních názvů ("Supreme Job Change, ..."), ne typ. Tam,
            // kde selže, proto appka zkusí ještě štítky v poli "Genre(s)" - Madara
            // weby tam běžně mají mezi žánry i "Manga"/"Manhwa"/"Manhua"/"Novel"
            // jako klasifikaci (ověřeno živě: "The Former Supreme" má mezi žánry
            // "Manhwa").
            val detectedType = doc.selectFirst("div.post-content_item:has(h5:contains(Type)) .summary-content")
                ?.text()?.trim()?.let(::normalizeContentType)
                ?: doc.select("div.post-content_item:has(h5:contains(Genre)) a")
                    .firstNotNullOfOrNull { normalizeContentType(it.text()) }

            manga.copy(description = desc, status = status, contentType = detectedType ?: contentTypeOverride)
        }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            // Madara u vetsiny webu nacita seznam kapitol pres AJAX endpoint,
            // protoze na hlavni strance mangy je jen limitovany vypis.
            // Nektere (obvykle upravene) motivy vraci na ajax endpoint HTTP 200
            // s nesouvisejicim fragmentem (napr. jiny widget) misto kapitol -
            // takovy "uspesny" ale prazdny vysledek se proto zahodi a pouzije
            // se rovnou hlavni stranka mangy, kde uz je chapterList casto
            // kompletni server-rendered (jen skryty pres CSS/"Show more").
            val ajaxDoc = try {
                val request = Request.Builder()
                    .url("${manga.url.trimEnd('/')}/ajax/chapters/")
                    .post(FormBody.Builder().add("action", "manga_get_chapters").build())
                    .header("User-Agent", userAgent)
                    .header("Referer", manga.url)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Jsoup.parse(response.body?.string().orEmpty(), manga.url) else null
                }
            } catch (e: Exception) { e.rethrowIfControl();
                null
            }

            val doc = ajaxDoc?.takeIf { it.select(selectors.chapterList).isNotEmpty() } ?: fetchDocument(manga.url)
            var rows = doc.select(selectors.chapterList)
            if (rows.isEmpty()) {
                // Starší motivy načítají kapitoly přes admin-ajax.php (id titulu je v #manga-chapters-holder).
                val mangaId = doc.selectFirst("#manga-chapters-holder[data-id]")?.attr("data-id")?.ifBlank { null }
                if (mangaId != null) {
                    val viaAdminAjax = try {
                        val request = Request.Builder()
                            .url("$root/wp-admin/admin-ajax.php")
                            .post(FormBody.Builder().add("action", "manga_get_chapters").add("manga", mangaId).build())
                            .header("User-Agent", userAgent)
                            .header("Referer", manga.url)
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) Jsoup.parse(response.body?.string().orEmpty(), manga.url) else null
                        }
                    } catch (e: Exception) { e.rethrowIfControl(); null }
                    if (viaAdminAjax != null) rows = viaAdminAjax.select(selectors.chapterList)
                }
            }

            rows.mapNotNull { row -> chapterFromRow(row, manga.url) }
        }

    private fun chapterFromRow(row: Element, mangaUrl: String): SChapter? {
        // U nekterych motivu je radek kapitoly primo <a> (ne obal s vnorenym
        // odkazem) - selectFirst hleda jen potomky, takze sebe sama nenajde.
        // U radku "has-thumb" (napr. grabber.zone) je prvni <a> jen obal okolo
        // <img> nahledu bez textu - selectFirst("a") by ho chytil a radek zahodil
        // pro prazdny nazev. Proto preferujeme prvni odkaz S TEXTEM.
        val link = row.takeIf { it.tagName() == "a" && it.hasAttr("href") }
            ?: row.select("a").firstOrNull { it.text().isNotBlank() }
            ?: row.selectFirst("a") ?: return null
        // Zamcene premium kapitoly (napr. noicetranslations "coin-20 premium-block")
        // maji href="#" - cist se nedaji a "#" resolvuje na stranku titulu, takze
        // pageList vrati 0 stran. Radky bez skutecneho odkazu preskakujeme.
        val rawHref = link.attr("href").trim()
        if (rawHref.isEmpty() || rawHref == "#" || rawHref.startsWith("javascript")) return null
        val url = link.absUrl("href").ifBlank { return null }
        val name = link.text().trim().ifBlank { return null }
        val chapterNumber = parseChapterNumber(name) ?: 0f
        val dateText = row.selectFirst("span.chapter-release-date, i")?.text()?.trim()

        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = url,
            name = name,
            chapterNumber = chapterNumber,
            dateUpload = chapterDate(dateText),
        )
    }

    private fun chapterDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val locale = java.util.Locale.forLanguageTag(languageOverride)
        datePattern?.let { pattern ->
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, locale).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(text.trim())?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return com.haise.jiyu.util.parseChapterDate(text, locale)
    }


    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(chapter.url)

            // Madara sablona se pouziva i pro romanove (NOVEL) weby - obsah
            // kapitoly je pak text v <p> tazich stejneho ".reading-content"
            // kontejneru, ne <img> - puvodne se tu vzdy hledaly jen obrazky,
            // takze romanove Madara zdroje (wuxiaworldsite, ranovel) vzdy
            // vratily prazdny seznam stranek. "novel://text" je sentinel,
            // ktery ctecka (ReaderViewModel) rozezna jako text, ne obrazek.
            if (contentTypeOverride == "NOVEL") {
                val text = doc.select(selectors.novelContent).joinToString("\n\n") { it.text().trim() }
                return@withContext if (text.isBlank()) emptyList() else listOf(Page(0, text, "novel://text"))
            }

            val images = doc.select(selectors.pageImage)

            images.mapIndexedNotNull { i, img ->
                val src = img.lazySrc().orEmpty()
                    .trim().ifBlank { return@mapIndexedNotNull null }
                Page(index = i, url = src, imageUrl = src)
            }
        }

    // ─── Pomocné funkce ──────────────────────────────────────────────────────

    /** Mapuje text pole "Type" z Madara detailu na náš interní kód. Neznámá/prázdná hodnota vrátí null. */
    private fun normalizeContentType(text: String): String? = when (text.trim().lowercase()) {
        "manga" -> "MANGA"
        "manhwa" -> "MANHWA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> null
    }

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", baseUrl)
            .build()
        client.newCall(request).execute().use { response ->
            // IOException, ne check()/IllegalStateException - RetryInterceptor (AppModule.kt)
            // chyta jen IOException, takze IllegalStateException tenhle retry uplne obejde
            // (nahlaseno v auditu).
            if (!response.isSuccessful) throw java.io.IOException("Chyba ${response.code} pri nacitani $url")
            val body = response.body?.string().orEmpty()
            return Jsoup.parse(body, url)
        }
    }
}

package com.haise.jiyu.source.comick

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.util.normalizeMangaTitle
import com.haise.jiyu.util.report
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

/** Jeden nalezený reálný zdroj, který ComicK titul také má. */
data class ResolvedCandidate(
    val source: MangaSource,
    val manga: SManga,
    val matchedChapterCount: Int,
    val hasRequestedChapter: Boolean,
    val isFavorite: Boolean,
    // Vzdalenost nejblizsiho dostupneho cisla kapitoly od pozadovaneho - null, kdyz
    // se pozadovane cislo vubec neresi (requestedChapterNumber == null). Bez tohohle
    // by automaticky vyber (viz SourceResolverViewModel) mohl sahnout po zdroji s
    // nejvic kapitolami celkem, i kdyz uz preklad davno skoncil daleko pred cilem
    // (nebo teprve zacal az od nejake pozdejsi kapitoly) - "nejvic kapitol" totiz
    // nerika nic o tom, KDE presne ty kapitoly jsou.
    val nearestChapterDistance: Float? = null,
)

/**
 * Křížové vyhledání skutečného, čitelného zdroje pro ComicK titul (ComicK sám
 * jen katalogizuje, reálné stránky kapitol nikdy neposkytuje - viz design doc
 * "Sub-projekt 3"). Zužuje kandidáty podle typu obsahu, hledá živě paralelně,
 * porovnává normalizovaný název a cachuje výsledek na úrovni titulu (jen
 * v paměti, po dobu běhu appky - viz design doc "Cache rozsah").
 */
@Singleton
class ComicKChapterResolver @Inject constructor(
    private val sourceManager: SourceManager,
    private val settings: SettingsRepository,
    private val comicKSource: ComicKSource,
    private val cloudflareInterceptor: CloudflareInterceptor,
) {
    private data class CachedCandidate(val source: MangaSource, val manga: SManga, val chapters: List<SChapter>)

    // Ohranicena LRU cache - appka za dobu behu muze projit desitky/stovky ComicK titulu, bez
    // stropu by mapa rostla neomezene po celou dobu behu procesu (stejny audit nalez jako
    // ThrottleInterceptor.semaphores v AppModule.kt). Schvalne NE android.util.LruCache - ten
    // je v lokalnich JVM unit testech jen stub (isReturnDefaultValues v build.gradle.kts z nej
    // dela tichy no-op), coz by rozbilo testy, ktere cachovani primo overuji (viz
    // ComicKChapterResolverTest). LinkedHashMap s access-order=true + removeEldestEntry je
    // stejna LRU sémantika, ale čistý JDK, funguje shodně v testu i za běhu appky.
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<CachedCandidate>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CachedCandidate>>): Boolean =
                size > MAX_CACHED_TITLES
        },
    )

    /**
     * Stejné jako dřívější `findCandidates`, jen misto cekani na uplne vsechny zdroje najednou
     * (awaitAll) emituje kazdeho kandidata hned, jak ho najde - viz [searchAndFetchStreaming].
     * Cachovany vysledek (druhe a dalsi otevreni stejneho titulu) se posle vsechen naraz, tam
     * uz neni na co cekat.
     *
     * @param comicKMangaId klíč pro cache (Room id ComicK manga entity)
     * @param comicKMangaUrl url ComicK manga entity - použije se pro dotažení alternativních
     *   názvů a content_rating (viz [ComicKSource.getTitleInfo]), protože `comicKTitle` sám
     *   o sobě často nesedí s tím, jak titul jmenují ostatní zdroje (viz [searchAndFetchStreaming]).
     * @param requestedChapterNumber null = zajímá nás jen "existuje vůbec zdroj", jinak
     *   se navíc spočítá [ResolvedCandidate.hasRequestedChapter] pro tohle konkrétní číslo.
     */
    fun findCandidatesFlow(
        comicKMangaId: String,
        comicKMangaUrl: String,
        comicKTitle: String,
        comicKContentType: String,
        requestedChapterNumber: Float?,
    ): Flow<ResolvedCandidate> = channelFlow {
        val favorites = settings.favoriteSourceIds.first()
        val cached = cache.get(comicKMangaId)
        if (cached != null) {
            cached.forEach { send(toResolvedCandidate(it, favorites, requestedChapterNumber)) }
            return@channelFlow
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<CachedCandidate>())
        searchAndFetchStreaming(comicKMangaUrl, comicKTitle, comicKContentType) { candidate ->
            found.add(candidate)
            send(toResolvedCandidate(candidate, favorites, requestedChapterNumber))
        }
        // I prazdny vysledek se cachuje (negativni cache) - bez tohohle drahe cross-source
        // hledani přes VŠECHNY zdroje probíhalo znovu při každém otevření titulu bez shody,
        // ne jen jednou (audit nalez).
        cache.put(comicKMangaId, found.toList())
    }

    private fun toResolvedCandidate(c: CachedCandidate, favorites: Set<String>, requestedChapterNumber: Float?): ResolvedCandidate =
        // floor(), ne primy distinct(chapterNumber): nektere zdroje (napr. MangaPark) delci
        // jeden "logicky" preklad na vic zapisu s cisly X, X.1, X.2 - bez floor() by to
        // v pomeru vypadalo jako "242/209 kapitol" (vic nez 100 %), overeno zive na
        // MangaPark API pro Solo Leveling. floor() je stejna transformace jako u
        // SourceResolverViewModel.totalComicKChapters, takze pomer zustava srovnatelny.
        ResolvedCandidate(
            source = c.source,
            manga = c.manga,
            matchedChapterCount = c.chapters.map { floor(it.chapterNumber).toInt() }.distinct().size,
            hasRequestedChapter = requestedChapterNumber == null ||
                c.chapters.any { abs(it.chapterNumber - requestedChapterNumber) < 0.01f },
            isFavorite = c.source.id in favorites,
            nearestChapterDistance = requestedChapterNumber?.let { target ->
                c.chapters.minOfOrNull { abs(it.chapterNumber - target) }
            },
        )

    /**
     * `comicKTitle` je jen JEDEN z ComicK titulu md_titles - u řady titulů to není ten, pod
     * kterým ho eviduje většina ostatních zdrojů (např. ComicK primárně eviduje Solo Leveling
     * pod "I am the only the one who levels up", "Solo Leveling" je md_titles položka s
     * `is_default: true`). Bez alternativních názvů by přesná shoda selhala úplně, i když
     * reálný zdroj existuje. Dotažení alt. názvů (+ content_rating, viz níže) je jen jeden
     * extra request navíc (ne za zdroj), a pokud selže, spadneme zpátky na `comicKTitle`
     * samotný a titul se pro jistotu bere jako POTENCIÁLNĚ adult (viz `titleInfoFetchFailed`
     * níže) - opačný předpoklad by transientní výpadek requestu proměnil v trvalé tiché
     * vynechání adult zdrojů.
     *
     * `onFound` se voláva souběžně z více zdrojů najednou (semafor pouští až 5 zaráz) - volající
     * ([findCandidatesFlow] přes `channelFlow.send`) musí umět bezpečně přijímat souběžná volání.
     */
    private suspend fun searchAndFetchStreaming(
        comicKMangaUrl: String,
        comicKTitle: String,
        comicKContentType: String,
        onFound: suspend (CachedCandidate) -> Unit,
    ) {
        // Hromadne prohledavani desitek zdroju najednou nema interaktivni Cloudflare vyzvu
        // (viz CloudflareInterceptor.suppressInteractiveChallenge) prekazet uzivateli dialogem
        // od zdroje, ktery zrovna nehleda - zdroj, co potrebuje skutecnou CAPTCHU, se proste
        // preskoci jako nedostupny pro tenhle pokus. Finally i pri zruseni (viz SourceResolverViewModel
        // early-exit) flag spolehlive vrati zpet, aby normalni prime prochazeni zdroje dal fungovalo.
        cloudflareInterceptor.suppressInteractiveChallenge = true
        try {
            searchAndFetchStreamingInternal(comicKMangaUrl, comicKTitle, comicKContentType, onFound)
        } finally {
            cloudflareInterceptor.suppressInteractiveChallenge = false
        }
    }

    private suspend fun searchAndFetchStreamingInternal(
        comicKMangaUrl: String,
        comicKTitle: String,
        comicKContentType: String,
        onFound: suspend (CachedCandidate) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(5)
        var titleInfoFetchFailed = false
        val titleInfo = try {
            comicKSource.getTitleInfo(comicKMangaUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("comick:resolver:titleInfo")
            titleInfoFetchFailed = true
            ComicKTitleInfo(alternateTitles = emptyList(), contentRating = null)
        }
        val alternateTitles = titleInfo.alternateTitles
        // Selhani requestu neznamena, ze titul NENI adult - jen ze to nevime. Radeji
        // prohledat i adult zdroje navic (levne - presna shoda nazvu je stejne odfiltruje),
        // nez aby transientni sitovy vypadek navzdy tise vyradil adult zdroje pro adult
        // titul (nahlaseny bug). Skutecne bezpecny non-adult vysledek z API (contentRating
        // == null, ale request USPEL) se timhle nemeni.
        val isAdultTitle = titleInfoFetchFailed || isAdultRating(titleInfo.contentRating)
        val searchTitle = alternateTitles.firstOrNull() ?: comicKTitle
        val normalizedTargets = (alternateTitles + comicKTitle).map { normalizeMangaTitle(it) }.toSet()
        sourceManager.getAllForCrossSourceSearch()
            .filter { it.id != "comick" && it.includeInGlobalSearch && isSameContentGroup(it.contentType, comicKContentType) }
            // Ne-adult ComicK titul nikdy neprohledává isAdult zdroje (i kdyz je uzivatel
            // globalne povolil v Nastaveni) - a adult titul je naopak vzdy zahrne, i kdyz
            // je uzivatel globalne skryl z Prochazet/hledani. Zamerne nezavisle na
            // SourceManager.getAll()/showAdultSources - viz getAllForCrossSourceSearch.
            .filter { isAdultTitle || !it.isAdult }
            .map { source ->
                launch {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(8_000) {
                                val results = source.search(searchTitle, 1, MangaFilter())
                                val match = results.firstOrNull { normalizeMangaTitle(it.title) in normalizedTargets }
                                match?.let { m -> onFound(CachedCandidate(source, m, source.getChapterList(m))) }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: com.haise.jiyu.source.SourceRateLimitedException) {
                            // Zdroj je docasne omezeny (429) - proste se pro tenhle titul preskoci,
                            // ostatni zdroje bezi dal a nema to jit do Crashlytics jako chyba.
                        } catch (e: Exception) {
                            e.report("comick:resolver:${source.id}")
                        }
                    }
                }
            }.forEach { it.join() }
    }

    /** "erotica"/"pornographic" = 18+ na ComicK škále (stejná škála jako MangaDex/MangaFire content_rating filtr), "safe"/"suggestive"/null = ne. */
    private fun isAdultRating(contentRating: String?): Boolean = contentRating in ADULT_CONTENT_RATINGS

    /**
     * MANGA/MANHWA/MANHUA se pro účely hledání zdroje berou jako jedna skupina (region
     * asijského komiksu) - stejná konvence jako `BrowseViewModel.MANGA_GROUP` pro
     * Procházet, protože spousta zdrojů má title-level typ smíchaný a jen jeden
     * "výchozí" contentType na úrovni celého zdroje. Novely a americké komiksy se
     * nikdy neprohledávají u ComicK titulu (ComicK sám je jen manga/manhwa/manhua tracker).
     */
    private fun isSameContentGroup(sourceType: String, targetType: String): Boolean {
        val asianComicTypes = setOf("MANGA", "MANHWA", "MANHUA")
        return if (targetType in asianComicTypes) sourceType in asianComicTypes else sourceType == targetType
    }

    private companion object {
        val ADULT_CONTENT_RATINGS = setOf("erotica", "pornographic")
        const val MAX_CACHED_TITLES = 128
    }
}

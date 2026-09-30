package com.haise.jiyu.source.comick

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.isSameContentGroup
import com.haise.jiyu.source.interceptor.InteractiveChallengePolicy
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
    /** Nejnižší a nejvyšší číslo kapitoly, kterou zdroj u titulu má - null = neznámé. Z rozsahu se pozná zdroj, který
     * má jen pár kapitol (nebo jen začátek/konec), i když je jeho počet kapitol "dost velký". */
    val minChapterNumber: Float? = null,
    val maxChapterNumber: Float? = null,
    /** Kandidát nalezený deterministickým probe stejného slugu na comick.art mirroru
     * (ne fuzzy hledáním názvu) - sdílený slug = potvrzeně stejná série. Mirror nese
     * stejné skupinové verze jako ComicK, proto pro early-exit stačí kvalitní brány
     * (požadovaná kapitola + kompletnost + rozsah) bez podmínky oblíbený/skupina. */
    val isDirectMirror: Boolean = false,
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
) {
    private data class CachedCandidate(
        val source: MangaSource,
        val manga: SManga,
        val chapters: List<SChapter>,
        val isDirectMirror: Boolean = false,
    )

    /** Položka cache s časem vzniku - potřebné pro TTL negativních výsledků (SRC-4). */
    private class CacheEntry(val candidates: List<CachedCandidate>, val createdAtMs: Long)

    // Ohranicena LRU cache - appka za dobu behu muze projit desitky/stovky ComicK titulu, bez
    // stropu by mapa rostla neomezene po celou dobu behu procesu (stejny audit nalez jako
    // ThrottleInterceptor.semaphores v AppModule.kt). Schvalne NE android.util.LruCache - ten
    // je v lokalnich JVM unit testech jen stub (isReturnDefaultValues v build.gradle.kts z nej
    // dela tichy no-op), coz by rozbilo testy, ktere cachovani primo overuji (viz
    // ComicKChapterResolverTest). LinkedHashMap s access-order=true + removeEldestEntry je
    // stejna LRU sémantika, ale čistý JDK, funguje shodně v testu i za běhu appky.
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
                size > MAX_CACHED_TITLES
        },
    )

    /** Čas se v testech nahrazuje (simulace vypršení negativní cache). */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

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
        priorityGroupTokens: List<String> = emptyList(),
    ): Flow<ResolvedCandidate> = channelFlow {
        val favorites = settings.favoriteSourceIds.first()
        val cached = cache.get(comicKMangaId)
        // Negativni vysledek (prazdny seznam) ma TTL - transientni vypadek site nebo
        // docasne nedostupne zdroje nesmi navzdy (do restartu appky) zablokovat titul
        // jako "zadny zdroj to nema" (audit SRC-4). Pozitivni vysledek je porad session-long.
        if (cached != null && (cached.candidates.isNotEmpty() ||
                nowMs() - cached.createdAtMs < NEGATIVE_CACHE_TTL_MS)
        ) {
            cached.candidates.forEach { send(toResolvedCandidate(it, favorites, requestedChapterNumber)) }
            return@channelFlow
        }
        if (cached != null) cache.remove(comicKMangaId)
        val found = java.util.Collections.synchronizedList(mutableListOf<CachedCandidate>())
        searchAndFetchStreaming(comicKMangaUrl, comicKTitle, comicKContentType, priorityGroupTokens) { candidate ->
            // Dedupe podle source.id: comick.art muze prijit dvakrat - z probe faze 0
            // (slug, isDirectMirror) i z bezneho sweepu (title-search, kdyz mirror ma
            // titul pod jinym slugem). Prvni emise vyhrava a je to vzdy probe -
            // sweep se pousti az po probeJob.join(), viz searchAndFetchStreamingInternal.
            val isNew = synchronized(found) {
                if (found.none { it.source.id == candidate.source.id }) {
                    found.add(candidate)
                    true
                } else false
            }
            if (isNew) send(toResolvedCandidate(candidate, favorites, requestedChapterNumber))
        }
        // I prazdny vysledek se cachuje (negativni cache) - bez tohohle drahe cross-source
        // hledani přes VŠECHNY zdroje probíhalo znovu při každém otevření titulu bez shody,
        // ne jen jednou (audit nalez). Negativni polozka ale ma TTL, viz cteni vyse.
        cache.put(comicKMangaId, CacheEntry(found.toList(), nowMs()))
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
            minChapterNumber = c.chapters.minOfOrNull { it.chapterNumber },
            maxChapterNumber = c.chapters.maxOfOrNull { it.chapterNumber },
            isDirectMirror = c.isDirectMirror,
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
        priorityGroupTokens: List<String>,
        onFound: suspend (CachedCandidate) -> Unit,
    ) {
        // Hromadne prohledavani desitek zdroju najednou nema interaktivni Cloudflare vyzvu
        // prekazet uzivateli dialogem od zdroje, ktery zrovna nehleda - zdroj, co potrebuje
        // skutecnou CAPTCHU, se proste preskoci jako nedostupny pro tenhle pokus. Potlaceni
        // je scope-bound na korutinu sweepu (InteractiveChallengePolicy ThreadLocal) -
        // soubezne prime prochazeni zdroje uzivatelem ve foregroundu tim neni nikdy
        // dotceno (driv globalni flag, audit SRC-2).
        InteractiveChallengePolicy.suppressed {
            searchAndFetchStreamingInternal(comicKMangaUrl, comicKTitle, comicKContentType, priorityGroupTokens, onFound)
        }
    }

    private suspend fun searchAndFetchStreamingInternal(
        comicKMangaUrl: String,
        comicKTitle: String,
        comicKContentType: String,
        priorityGroupTokens: List<String>,
        onFound: suspend (CachedCandidate) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(5)
        // Fáze 0: přímý slug-probe comick.art mirroru - uzivatelsky pozadavek:
        // comick.art se prohledava jako PRVNÍ zdroj u KAZDEHO titulu (at je 18+
        // nebo ne). Probe bezi soubezne jen s fetch titleInfo (nezavisi na nem)
        // a sweep se pusti az PO jeho dokonceni (probeJob.join() nize) - mirror
        // kandidat tak emituje pred kazdym sweep vysledkem a kompletni mirror
        // vyhraje early-exit/auto-open, nez se vubec rozjede fuzzy hledani.
        // Zpozdeni sweepu o probe stoji typicky ~0.3-1s; pri nedostupnem mirroru
        // strop je MIRROR_PROBE_TIMEOUT_MS.
        val probeJob = launch {
            probeComicKArtMirror(comicKMangaUrl, comicKTitle, comicKContentType, onFound)
        }
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
        probeJob.join()
        val alternateTitles = titleInfo.alternateTitles
        // Selhani requestu neznamena, ze titul NENI adult - jen ze to nevime. Radeji
        // prohledat i adult zdroje navic (levne - presna shoda nazvu je stejne odfiltruje),
        // nez aby transientni sitovy vypadek navzdy tise vyradil adult zdroje pro adult
        // titul (nahlaseny bug). Skutecne bezpecny non-adult vysledek z API (contentRating
        // == null, ale request USPEL) se timhle nemeni.
        val isAdultTitle = titleInfoFetchFailed || isAdultRating(titleInfo.contentRating)
        // Potvrzeny adult titul (rating primo z API) - odlisuje se od "nevim" stavu
        // (fetch selhal), protoze jen potvrzene adult tituly se prohledavaji VYHRADNE
        // v adult zdrojich (uzivatelsky pozadavek); u neznameho ratingu se pro jistotu
        // hleda vsude, aby transientni vypadek nesmzal zdroje ne-adult titulu.
        val isConfirmedAdult = isAdultRating(titleInfo.contentRating)
        val normalizedTargets = (alternateTitles + comicKTitle).map { normalizeMangaTitle(it) }.toSet()
        // Dotaz se zkousi postupne se vsemi nazvy titulu, ne jen s prvnim altem - zdroj
        // muze titul evidovat pod jinou alternativou, se kterou prvni query nic nenajde
        // (audit SRC-3). Poradi zachovava drivejsi prioritu (prvni alt = default nazev,
        // pak ostatni alty, nakonec comicKTitle); strop MAX_QUERY_TITLES a per-source
        // timeout drzi cenu sweepu stejnou jako driv.
        val queryTitles = (alternateTitles + comicKTitle)
            .filter { it.isNotBlank() }
            .distinctBy { normalizeMangaTitle(it) }
            .take(MAX_QUERY_TITLES)
        val eligible = sourceManager.getAllForCrossSourceSearch()
            .filter { it.id != "comick" && it.includeInGlobalSearch && isSameContentGroup(it.contentType, comicKContentType) }
            // Ne-adult ComicK titul nikdy neprohledává isAdult zdroje (i kdyz je uzivatel
            // globalne povolil v Nastaveni). Potvrzeny adult titul se prohledava JEN
            // v adult zdrojich + comick.art mirror (patri do "18+ sady" - mirror nese
            // stejna data jako ComicK vcetne adult titulu a uz bezi jako faze 0, tedy
            // prvni). Neznamy rating (fetch selhal) = konzervativne vsechny zdroje.
            // Zamerne nezavisle na SourceManager.getAll()/showAdultSources - viz
            // getAllForCrossSourceSearch.
            .filter { src ->
                when {
                    !isAdultTitle -> !src.isAdult
                    isConfirmedAdult -> src.isAdult || src.id == com.haise.jiyu.source.comickart.ComicKArtSource.SOURCE_ID
                    else -> true
                }
            }
            // Hledá se jen v anglických zdrojích: ComicK je anglický katalog, překlad do jiného jazyka (ru, pt, es...)
            // by při otevření kapitoly dal titul, který uživatel nemůže číst, a zbytečně by zatěžoval hledání.
            .filter { isSearchLanguage(it.language) }
        // Dvě fáze: nejdřív zdroje skupin, které titul překládají TEĎ (skupiny posledních kapitol) - když ten zdroj
        // máme a má titul kompletní, hledání se ukončí hned (viz early-exit ve ViewModelu); teprve potom všechny
        // ostatní anglické zdroje včetně agregátorů (hubů).
        val (priority, rest) = eligible.partition { matchesGroupSource(it.name, priorityGroupTokens) }
        for (phase in listOf(priority, rest)) {
            phase.map { source ->
                launch {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(8_000) {
                                var match: SManga? = null
                                for (query in queryTitles) {
                                    val results = source.search(query, 1, MangaFilter())
                                    match = results.firstOrNull { normalizeMangaTitle(it.title) in normalizedTargets }
                                    if (match != null) break
                                }
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
    }

    /**
     * Fáze 0 - deterministický probe comick.art mirroru. Mirror má vlastní DB se
     * sdíleným `slug` titulu (hid se NESDÍLÍ - viz ComicKArtSource doc), takže
     * stačí zkusit jeho chapter-list pro stejný slug: žádné fuzzy hledání názvu,
     * žádný search request. Kapitoly navíc nesou stejné skupinové verze jako
     * ComicK, takže match názvu není potřeba vůbec - slug je jednoznačný.
     *
     * Prázdný seznam kapitol / jakákoli chyba = mirror titul nemá → tiše přeskočit
     * (normální sweep přes ostatní zdroje poběží dál). `onFound` se volá z
     * paralelní coroutiny - volající musí zvládat souběžná volání (viz výše).
     */
    private suspend fun probeComicKArtMirror(
        comicKMangaUrl: String,
        comicKTitle: String,
        comicKContentType: String,
        onFound: suspend (CachedCandidate) -> Unit,
    ) {
        try {
            val slug = comicKMangaUrl.substringAfterLast("/").ifBlank { return }
            val source = sourceManager.getAllForCrossSourceSearch()
                .find { it.id == com.haise.jiyu.source.comickart.ComicKArtSource.SOURCE_ID && it.includeInGlobalSearch }
                ?.takeIf { isSameContentGroup(it.contentType, comicKContentType) }
                ?: return
            val manga = SManga(
                sourceId = source.id,
                url = "${source.homepageUrl ?: com.haise.jiyu.source.comickart.ComicKArtSource.DEFAULT_BASE}/comic/$slug",
                title = comicKTitle,
                coverUrl = null,
            )
            val chapters = withTimeoutOrNull(MIRROR_PROBE_TIMEOUT_MS) { source.getChapterList(manga) } ?: return
            if (chapters.isEmpty()) return
            onFound(CachedCandidate(source, manga, chapters, isDirectMirror = true))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("comick:resolver:mirror")
        }
    }

    /** Anglický zdroj ("en", "en-US"...). */
    private fun isSearchLanguage(language: String): Boolean = language.lowercase().startsWith("en")

    /** "erotica"/"pornographic" = 18+ na ComicK škále (stejná škála jako MangaDex/MangaFire content_rating filtr), "safe"/"suggestive"/null = ne. */
    private fun isAdultRating(contentRating: String?): Boolean = contentRating in ADULT_CONTENT_RATINGS

    private companion object {
        /** Zdroj skupiny = normalizovaný název zdroje obsahuje normalizovaný token skupiny (nebo naopak). */
        fun matchesGroupSource(sourceName: String, tokens: List<String>): Boolean {
            if (tokens.isEmpty()) return false
            val name = sourceName.lowercase().filter { it.isLetterOrDigit() }
            return name.isNotEmpty() && tokens.any { name.contains(it) || it.contains(name) }
        }

        val ADULT_CONTENT_RATINGS = setOf("erotica", "pornographic")
        const val MAX_CACHED_TITLES = 128
        /** Kolik ruznych nazvu titulu se na jeden zdroj zkusi jako dotaz (viz SRC-3). */
        const val MAX_QUERY_TITLES = 4
        /** TTL negativniho (prazdneho) vysledku sweepu - po jejim vyprseni se titul zkusi znovu (SRC-4). */
        const val NEGATIVE_CACHE_TTL_MS = 10 * 60 * 1000L
        /** Timeout fáze 0 (comick.art slug probe) - probe teď GATEuje start sweepu
         * (comick.art = vzdy prvni zdroj, viz searchAndFetchStreamingInternal), takze
         * jeho strop je zaroven nejhorsi zpozdeni zbytku hledani pri visicim mirroru.
         * 5s (kratsi nez per-source 8s): jeden request, zdravy mirror odpovi za <2s. */
        const val MIRROR_PROBE_TIMEOUT_MS = 5_000L
    }
}

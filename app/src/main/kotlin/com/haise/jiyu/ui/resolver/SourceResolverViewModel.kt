package com.haise.jiyu.ui.resolver

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.comick.ComicKChapterResolver
import com.haise.jiyu.source.comick.ResolvedCandidate
import com.haise.jiyu.source.comickart.ComicKArtSource
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor

/**
 * Early-exit (viz [SourceResolverViewModel] init) smi vybrat zdroj JEN kdyz ma skoro
 * vsechny kapitoly, ne jen tu jednu pozadovanou - nahlaseny bug: zdroj mel jmenem
 * sedici prekladatelskou skupinu i pozadovanou kapitolu, ale jinak byl neuplny
 * (chybely kapitoly jinde v serii). 90% hranice necha prostor pro rozdil v tom, jak
 * rychle ktery web zverejnuje nejnovejsi kapitolu (ComicK muze mit o par kapitol vic).
 * `totalComicKChapters <= 0` znamena, ze jeste nemame spolehlivy udaj o celkovem
 * poctu (nemelo by nastat, ale radeji nebrzdit early-exit na chybejicich datech).
 */
internal fun isCompleteEnoughForEarlyExit(matchedChapterCount: Int, totalComicKChapters: Int): Boolean {
    if (totalComicKChapters <= 0) return true
    return matchedChapterCount >= totalComicKChapters * EARLY_EXIT_COMPLETENESS_THRESHOLD
}

private const val EARLY_EXIT_COMPLETENESS_THRESHOLD = 0.9

/**
 * Je zapamatovaný reálný zdroj (viz MangaEntity.resolvedMangaId) použitelný pro požadovanou
 * kapitolu? Musí ji mít A zároveň pokrývat rozsah ComicK titulu (viz [coversChapterRange]) -
 * zdroj, který je oproti ComicKu pozadu (chybí mu nejnovější kapitoly), se přeskočí a místo
 * něj proběhne plný sweep, aby se našel aktuálnější. Bez druhé podmínky by se appka "lepila"
 * na zdroj, který přestal vydávat - nahlaseny pozadavek "kdyztak projit, jestli tam je
 * nejaktualnejsi kapitola".
 */
internal fun rememberedSourceUsable(
    sourceChapterNumbers: List<Float>,
    requestedChapter: Float,
    comicKRange: ChapterRange?,
): Boolean =
    sourceChapterNumbers.any { abs(it - requestedChapter) < 0.01f } &&
        coversChapterRange(sourceChapterNumbers.minOrNull(), sourceChapterNumbers.maxOrNull(), comicKRange)

/** Od které do které kapitoly ComicK titul eviduje (nejnižší a nejvyšší číslo kapitoly). */
internal data class ChapterRange(val first: Float, val last: Float)

/**
 * Pokrývá zdroj rozsah kapitol titulu? Zdroj s počtem kapitol "skoro jako ComicK" může mít jen jeho část (třeba jen
 * posledních 40 kapitol) a nemá smysl ho otevírat místo úplného. Tolerance: na konci pár kapitol (zdroje vydávají
 * nejnovější o něco později), na začátku ~1-2 kapitoly (ComicK často začíná od 0, zdroje od 1).
 * Neznámý rozsah zdroje nebo titulu = pokrývá (není důvod ho vyřazovat).
 */
internal fun coversChapterRange(candidateFirst: Float?, candidateLast: Float?, range: ChapterRange?): Boolean {
    if (range == null || candidateFirst == null || candidateLast == null) return true
    val span = (range.last - range.first).coerceAtLeast(0f)
    val endTolerance = maxOf(3f, span * 0.03f)
    val startTolerance = maxOf(1.5f, span * 0.03f)
    return candidateLast >= range.last - endTolerance && candidateFirst <= range.first + startTolerance
}

/** Skupiny, které titul překládají TEĎ (posledních 2 kapitoly), a ty, které ho začaly (první 2 kapitoly). */
internal data class GroupSignals(val activeTokens: List<String>, val originTokens: List<String>) {
    /**
     * Které skupiny mají ovlivnit výběr zdroje: jen ty, co překládají teď. Skupina, která titul jen začala a v
     * posledních kapitolách už není (skončila, zdroj mohl být zrušen), se NEpreferuje - jinak by appka otevřela
     * zdroj s nedokončeným překladem. Bez informace o posledních skupinách se použijí aspoň ty původní.
     */
    val preferredTokens: List<String> get() = activeTokens.ifEmpty { originTokens }
}

/**
 * Z kapitol ComicK titulu (jedna kapitola bývá vícekrát, po jednom záznamu za skupinu) vytáhne skupiny první dvou a
 * posledních dvou kapitol. Čísla se sjednocují přes floor(), stejně jako počítání kompletnosti.
 */
internal fun deriveGroupSignals(chapters: List<ChapterEntity>, normalize: (String) -> String, isGeneric: (String) -> Boolean): GroupSignals {
    val numbersDesc = chapters.map { floor(it.chapterNumber).toInt() }.distinct().sortedDescending()
    fun tokensOf(numbers: Set<Int>): List<String> = chapters
        .filter { floor(it.chapterNumber).toInt() in numbers }
        .flatMap { (it.scanlationGroup ?: "").split(",") }
        .map(normalize)
        .filter { it.length >= 3 && !isGeneric(it) }
        .distinct()
    return GroupSignals(
        activeTokens = tokensOf(numbersDesc.take(2).toSet()),
        originTokens = tokensOf(numbersDesc.takeLast(2).toSet()),
    )
}

private const val SUSPICIOUSLY_SHORT_PAGE_FLOOR = 6

/**
 * Skupinove tokeny (viz preferredGroupTokens/matchesPreferredGroup) pouzivaji fuzzy
 * `contains` obema smery - samotne obecne slovo jako "scans" by tak matchlo KAZDY zdroj
 * se stejnym obecnym slovem ve jmene (napr. kazdy "*-scans" zdroj), i kdyz jde o uplne
 * jinou prekladatelskou skupinu (nahlaseny bug). Delkovy filtr (>=3 znaky) tohle
 * nezachyti, protoze "scans"/"manga" uz maji 3+ znaku sami o sobe.
 */
private val GENERIC_GROUP_TOKENS = setOf(
    "scans", "scan", "manga", "manhwa", "manhua", "team", "group",
    "translations", "translation", "sub", "subs", "comics", "comic",
)

internal fun isGenericGroupToken(token: String): Boolean = token in GENERIC_GROUP_TOKENS

/** Strop na kontrolu kompletnosti kapitoly (viz resolveCompleteChapter) - stejny duvod jako
 * ReaderViewModel.CHAPTER_LOAD_TIMEOUT_MS (RetryInterceptor x CloudflareInterceptor umi viset
 * beze jakekoli vyjimky i pres minutu). Kratsi nez tam (45s) - u alternativnich kandidatu jde
 * jen o spekulativni kontrolu (appka ma vzdy fallback na puvodni bestMatch), ne o skutecne
 * cteni, na ktere uzivatel primo ceka. */
private const val FALLBACK_PAGE_CHECK_TIMEOUT_MS = 15_000L

/** Kapitola s min poctem stranek je podezrela z neuplnosti - viz nahlaseny bug (MangaK/The
 * Raider/kap.19: 5 stranek vs. 11-19 u sousednich). Zaporne/nulove hodnoty (getPageList
 * selhalo/prazdne) jsou taky podezrele. */
internal fun isSuspiciouslyShort(pageCount: Int): Boolean = pageCount < SUSPICIOUSLY_SHORT_PAGE_FLOOR

/** Ze seznamu uz OVERENYCH alternativ (kandidat, pocet stranek) vybere tu s nejvic strankami,
 * pokud prekonava jak puvodni pocet, tak minimalni prah - jinak null (puvodni kapitola je porad
 * nejlepsi dostupna moznost, i kdyz je kratka - napr. MangaK/kap.19, kde zadna alternativa
 * nemela vic). Pri shode poctu stranek zustava puvodni (>, ne >=). */
internal fun <T> pickBetterAlternative(originalPageCount: Int, alternatives: List<Pair<T, Int>>): T? =
    alternatives
        .filter { (_, count) -> count >= SUSPICIOUSLY_SHORT_PAGE_FLOOR && count > originalPageCount }
        .maxByOrNull { (_, count) -> count }
        ?.first

/**
 * Sdilene razeni kandidatu zdroje - comick.art mirror > oblibeny > shoda prekladatelske
 * skupiny > ma pozadovanou kapitolu > nejuplnejsi pokryti > nejblizsi kapitola.
 *
 * "Oblibeny"/"shoda skupiny" bonus plati JEN kdyz je kandidat aspon skoro kompletni (stejny
 * prah jako [isCompleteEnoughForEarlyExit], zamerne sdileny - jedna hranice "kompletnosti" v
 * cele tride) - bez tehle podminky vyhral oblibeny/skupinovy zdroj se 4 kapitolami ze 163 nad
 * uplnym, jen neoblibenym zdrojem (nahlaseno uzivatelem: early-exit ma tenhle strop uz drive,
 * ale finalni razeni po dokoncenem hledani ho vubec nepouzivalo). Uzivatelsky pozadavek
 * "oblibeny/skupina napred" porad plati, jen ne na ukor drasticky neuplneho pokryti - kompletni
 * zdroj bez preference porad vyhraje nad neuplnym s preferenci. Kdyz ZADNY kandidat neni dost
 * kompletni, oba boolean bonusy vyjdou false pro vsechny a razeni spadne na matchedChapterCount
 * nize - stale se vybere nejlepsi dostupny, jen uz bez umeleho zvyhodneni.
 */
internal fun rankCandidates(
    candidates: List<ResolvedCandidate>,
    totalComicKChapters: Int,
    isPreferredGroup: (ResolvedCandidate) -> Boolean,
    comicKRange: ChapterRange? = null,
): List<ResolvedCandidate> {
    fun isCompleteEnough(c: ResolvedCandidate) =
        isCompleteEnoughForEarlyExit(c.matchedChapterCount, totalComicKChapters) &&
            coversChapterRange(c.minChapterNumber, c.maxChapterNumber, comicKRange)
    return candidates.sortedWith(
        // comick.art mirror (sdileny slug = stejna serie) PRVNI - uzivatelsky
        // pozadavek: comick.art ma byt vychozi zdroj pro kazdy titul, kdyz ho ma.
        // Nese stejne kapitoly i skupinove verze jako ComicK, takze kompletni
        // mirror je spolehlivejsi shoda nez fuzzy title-match cehokoliv jineho.
        compareByDescending<ResolvedCandidate> { it.isDirectMirror && isCompleteEnough(it) }
            .thenByDescending { it.isFavorite && isCompleteEnough(it) }
            .thenByDescending { isPreferredGroup(it) && isCompleteEnough(it) }
            .thenByDescending { it.hasRequestedChapter }
            .thenByDescending { it.matchedChapterCount }
            .thenBy { it.nearestChapterDistance ?: Float.MAX_VALUE }
    )
}

@HiltViewModel
class SourceResolverViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val resolver: ComicKChapterResolver,
    private val repository: MangaRepository,
    private val sourceManager: SourceManager,
    private val settings: SettingsRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val chapterId: String = checkNotNull(savedStateHandle["chapterId"])
    val incognito: Boolean = savedStateHandle["incognito"] ?: false

    private var requestedChapterNumber: Float? = null

    // ID ComicK titulu (ne realneho zdroje, na ktery se to nakonec vyresi) - potreba pro
    // synchronizaci "precteno"/Pokracovat ve cteni zpet na ComicK entitu, viz selectCandidate.
    private var comicKMangaId: String? = null

    // Jmeno/jmena prekladatelske skupiny prave te kapitoly, kterou uzivatel otevrel v seznamu
    // (napr. "Asura" u radku "Ch.5 Asura") - normalizovano (lowercase, jen alfanumericke znaky)
    // pro fuzzy porovnani se jmeny nasich zdroju (viz matchesPreferredGroup). ComicK umi u jedne
    // kapitoly vracet i vic skupin najednou, oddelene carkou (ChapterEntity.scanlationGroup).
    private var preferredGroupTokens: List<String> = emptyList()

    // Od které do které kapitoly ComicK titul eviduje - zdroj s jen částí rozsahu se nebere jako "kompletní"
    // (viz coversChapterRange) a nevyhrává ani early-exit, ani bonusy za oblíbený zdroj / skupinu.
    private var comicKRange: ChapterRange? = null

    // Jakmile prijde dost dobry kandidat (viz collect nize), appka uz nemusi cekat na
    // zbytek desitek zdroju - zrusi zbyvajici hledani (searchJob) a rovnou otevre. Flag
    // hlida, aby se stejny vysledek nevybral 2x (jednou tady, jednou v onCompletion,
    // ktery po zruseni jobu taky jeste dobehne - viz onCompletion nize).
    private var searchJob: Job? = null
    private var hasAutoResolved = false

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _comicKTitle = MutableStateFlow("")
    val comicKTitle: StateFlow<String> = _comicKTitle.asStateFlow()

    private val _candidates = MutableStateFlow<List<ResolvedCandidate>>(emptyList())
    val candidates: StateFlow<List<ResolvedCandidate>> = _candidates.asStateFlow()

    // Zdroje se prohledavaji soubezne a kazdy nalezeny kandidat se do seznamu prida hned,
    // ne az uplne vsechny dohledaji - viz ComicKChapterResolver.findCandidatesFlow. Tenhle
    // flag rika, jestli se jeste na pozadi hleda dal (drobny "Hledam dalsi zdroje..." radek
    // pod uz nalezenymi kandidaty), nezavisle na _loading (ten je jen pro uplne prvni spinner,
    // nez prijde vubec prvni vysledek).
    private val _searchingMore = MutableStateFlow(false)
    val searchingMore: StateFlow<Boolean> = _searchingMore.asStateFlow()

    private val _totalComicKChapters = MutableStateFlow(0)
    val totalComicKChapters: StateFlow<Int> = _totalComicKChapters.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _openedChapterId = MutableStateFlow<String?>(null)
    val openedChapterId: StateFlow<String?> = _openedChapterId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    init {
        searchJob = viewModelScope.launch {
            try {
                val chapter = repository.getChapter(chapterId)
                if (chapter == null) { _loading.value = false; return@launch }
                val manga = repository.getManga(chapter.mangaId)
                if (manga == null) { _loading.value = false; return@launch }
                _comicKTitle.value = manga.title
                comicKMangaId = manga.id
                requestedChapterNumber = chapter.chapterNumber

                // ComicK eviduje jednu kapitolu vícekrát, jednou za každou skupinu, co ji
                // přeložila - .size by tak počítal "3× Ch.890" jako 3, ne 1. floor() navíc
                // sjednocuje granularitu s ComicKChapterResolver.matchedChapterCount (některé
                // zdroje dělí jeden překlad na X, X.1, X.2 - bez floor() by šel poměr přes
                // 100 %, viz komentář tam).
                val allComicKChapters = repository.getAllChapters(chapter.mangaId)
                _totalComicKChapters.value = allComicKChapters
                    .map { floor(it.chapterNumber).toInt() }.distinct().size

                // Preferovane skupiny: nejen ta, co prelozila prave otevrenou kapitolu, ale i
                // 1., posledni a predposledni kapitola titulu - uzivatelsky pozadavek. Prvni
                // kapitola ukazuje, kdo preklad zacal, posledni dve kdo ho aktivne dodava ted -
                // dohromady spolehlivejsi signal "hlavniho" prekladatele nez jen jedna nahodna
                // otevrena kapitola (ta muze byt od vedlejsi/jednorazove skupiny).
                //
                // Upřesnění: rozhodují skupiny posledních DVOU kapitol (kdo titul překládá teď). Skupina, která
                // přeložila jen začátek, se nepreferuje - její zdroj bývá zrušený/nedokončený. Když nemáme žádný
                // zdroj aktivních skupin, hledá se dál mezi všemi anglickými zdroji včetně agregátorů.
                val signals = deriveGroupSignals(allComicKChapters, ::normalizeGroupToken, ::isGenericGroupToken)
                preferredGroupTokens = (signals.preferredTokens +
                    (chapter.scanlationGroup ?: "").split(",")
                        .map(::normalizeGroupToken)
                        .filter { it.length >= 3 && !isGenericGroupToken(it) })
                    .distinct()
                val numbers = allComicKChapters.map { it.chapterNumber }
                comicKRange = if (numbers.isEmpty()) null else ChapterRange(numbers.min(), numbers.max())

                // Fast path A: tahle kapitola uz byla drive vyresolvovana na konkretni
                // realnou kapitolu (fallbackChapterId) - otevrit ji rovnou, nula site.
                chapter.fallbackChapterId
                    ?.let { repository.getChapter(it) }
                    ?.takeIf { it.sourceId != "comick" }
                    ?.let { resolved ->
                        // Stejne ukotveni "Pokracovat" jako selectCandidate - otevrena
                        // ComicK kapitola se stane lastRead titulu.
                        repository.updateLastReadChapter(manga.id, chapterId)
                        _loading.value = false
                        _openedChapterId.value = resolved.id
                        return@launch
                    }

                // Fast path B: titul ma zapamatovany realny zdroj z minuleho resolvovani
                // (resolvedMangaId) - refreshnout jen jeho seznam kapitol, overit cil i
                // aktualnost (nejnovetsi ComicK kapitola) a otevrit bez sweepu vsech zdroju.
                rememberedCandidate(manga, chapter.chapterNumber)?.let { candidate ->
                    // Stejny duvod jako early-exit v collect nize: auto-vyber bez kompletniho
                    // seznamu kandidatu - resolveCompleteChapter pak nezapisuje
                    // verifiedPageCount, dokud nemelo sanci zkusit zadnou alternativu.
                    hasAutoResolved = true
                    _loading.value = false
                    selectCandidate(candidate)
                    return@launch
                }

                _searchingMore.value = true
                resolver.findCandidatesFlow(
                    comicKMangaId = manga.id,
                    comicKMangaUrl = manga.url,
                    comicKTitle = manga.title,
                    comicKContentType = manga.contentType,
                    requestedChapterNumber = chapter.chapterNumber,
                    priorityGroupTokens = preferredGroupTokens,
                )
                    .onCompletion {
                        _searchingMore.value = false
                        // Uz vybrano drive (viz "early-exit" v collect nize, ktery searchJob
                        // zrusil) - onCompletion po zruseni jobu porad jeste dobehne (s
                        // CancellationException jako "cause"), ale znovu vybirat/otevirat netreba.
                        if (hasAutoResolved) return@onCompletion
                        // Trideni az na konci hledani, ne po kazdem prubeznem vysledku - uzivatelsky
                        // pozadavek: seznam by se jinak mohl prehazet pod prstem, kdyz uz si nekdo
                        // vybira, zatimco jeste hleda dal na pozadi.
                        //
                        // Priorita (vsechny urovne sestupne dulezite):
                        // 1. comick.art mirror (stejna DB jako ComicK, sdileny slug) -
                        //    uzivatelsky pozadavek: vychozi zdroj pro kazdy titul, kdyz ho ma
                        // 2. oblibeny zdroj
                        // 3. zdroj STEJNE prekladatelske skupiny, jako mela otevrena kapitola
                        //    (matchesPreferredGroup) - uzivatelsky pozadavek: kdyz napr. "Asura"
                        //    prekladala kapitolu, kterou chce cist, a appka Asuru mezi zdroji ma,
                        //    dat ji prednost pred jinym zdrojem, i kdyz ma o par kapitol vic
                        // 4. zdroj, ktery ma presne POZADOVANOU kapitolu
                        // 5. zdroj s nejuplnejsim pokrytim (nejvic kapitol celkem) - NENI to proste
                        //    "nejvic kapitol" samo o sobe (to by mohlo sahnout po zdroji, co uz davno
                        //    skoncil daleko pred cilem, nebo zacal az pozdeji), ale az po bodech 1-3
                        //    uz to jen odlisuje kompletni zdroj od neuplneho
                        // 6. nejmensi vzdalenost nejblizsi dostupne kapitoly od cile (kdyz ani jeden
                        //    kandidat pozadovanou kapitolu nema)
                        val sorted = rankedCandidates()
                        _candidates.value = sorted
                        // Uzivatelsky pozadavek: appka ma vzdycky sama vybrat a rovnou otevrit
                        // nejvhodnejsi zdroj podle poradi vyse - rucni seznam (SourceResolverScreen)
                        // se tak realne ukaze jen na kratky okamzik pred prekrytim "resolving"
                        // overlayem, pripadne vubec, kdyz zadny kandidat nebyl nalezen (viz
                        // candidates.isEmpty() stav v SourceResolverScreen).
                        sorted.firstOrNull()?.let { selectCandidate(it) }
                    }
                    .collect { candidate ->
                        _candidates.value = _candidates.value + candidate
                        // Prvni vysledek uz staci na to prestat ukazovat celoobrazovkovy
                        // spinner - dal se hleda na pozadi, viz _searchingMore.
                        _loading.value = false

                        // Early-exit: uzivatelsky pozadavek - kdyz dorazi zdroj, ktery je
                        // oblibeny NEBO stejne prekladatelske skupiny jako otevirana kapitola
                        // (Asura, Thunderscans, ...) NEBO je to comick.art mirror nalezeny
                        // primo pres sdileny slug (isDirectMirror - mirror nese stejnou DB
                        // i skupinove verze jako ComicK, takze "shoda skupiny" je u nej
                        // implicitni) A rovnou ma pozadovanou kapitolu, appka uz
                        // nema duvod cekat, az se prohledaji zbyvajici desitky zdroju - tohle je
                        // uz jasna volba (nejsilnejsi kriteria z razeni v onCompletion), takze
                        // rovnou otevre a zbytek hledani zrusi (viz searchJob).
                        if (!hasAutoResolved && candidate.hasRequestedChapter &&
                            (candidate.isFavorite || matchesPreferredGroup(candidate) || candidate.isDirectMirror) &&
                            isCompleteEnoughForEarlyExit(candidate.matchedChapterCount, _totalComicKChapters.value) &&
                            coversChapterRange(candidate.minChapterNumber, candidate.maxChapterNumber, comicKRange)
                        ) {
                            hasAutoResolved = true
                            selectCandidate(candidate)
                            searchJob?.cancel()
                        }
                    }
            } catch (e: Exception) {
                e.report("resolver:findCandidates")
            } finally {
                _loading.value = false
                _searchingMore.value = false
            }
        }
    }

    /** lowercase + jen alfanumericke znaky, aby "Asura Scans" a "Asura" vysly stejne. */
    private fun normalizeGroupToken(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Fuzzy shoda: normalizovane jmeno zdroje obsahuje normalizovany token skupiny nebo naopak
     * (delsi retezec obvykle obsahuje kratsi - "asurascans" obsahuje "asura", ne naopak). Kratke
     * tokeny (< 3 znaky) i obecna slova (viz GENERIC_GROUP_TOKENS/isGenericGroupToken) uz
     * preferredGroupTokens vyfiltrovalo pri nastaveni, aby se predeslo falesnym shodam u
     * krakich/prilis obecnych jmen skupin.
     */
    private fun matchesPreferredGroup(candidate: ResolvedCandidate): Boolean {
        if (preferredGroupTokens.isEmpty()) return false
        val sourceName = normalizeGroupToken(candidate.source.name)
        return preferredGroupTokens.any { token -> sourceName.contains(token) || token.contains(sourceName) }
    }

    /**
     * Sdilene razeni kandidatu - viz [rankCandidates] pro poradi priorit a duvod, proc "oblibeny"/
     * "shoda skupiny" bonus vyzaduje i kompletnost. Pouziva se jak pro finalni serazeny seznam
     * pro uzivatele, tak pro vyber alternativ v [resolveCompleteChapter].
     */
    private fun rankedCandidates(): List<ResolvedCandidate> =
        rankCandidates(_candidates.value, _totalComicKChapters.value, ::matchesPreferredGroup, comicKRange)

    /**
     * Fast-path kandidát ze zapamatovaného reálného zdroje titulu ([MangaEntity.resolvedMangaId]).
     * Refreshne jeho seznam kapitol (tim zaroven overi, ze ma cilovou i nejnovetsi ComicK
     * kapitolu - viz [rememberedSourceUsable]) a vrati synteticky kandidat pro
     * [selectCandidate]; null = sweep se spusti normalne (neexistujici/mrtvy/nedostatecny
     * zdroj, sitovy vypadek - vse se hlasit neresi, plny sweep to vyresi sam).
     */
    private suspend fun rememberedCandidate(manga: MangaEntity, requested: Float): ResolvedCandidate? {
        val resolvedManga = manga.resolvedMangaId?.let { repository.getManga(it) } ?: return null
        if (resolvedManga.sourceId == "comick") return null
        val source = sourceManager.getById(resolvedManga.sourceId) ?: return null
        val smanga = resolvedManga.toSManga()
        val chapters = try {
            repository.refreshChapters(resolvedManga.id, smanga)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("resolver:remembered:${source.id}")
            return null
        }
        if (!rememberedSourceUsable(chapters.map { it.chapterNumber }, requested, comicKRange)) return null
        return ResolvedCandidate(
            source = source,
            manga = smanga,
            matchedChapterCount = chapters.map { floor(it.chapterNumber).toInt() }.distinct().size,
            hasRequestedChapter = true,
            isFavorite = source.id in settings.favoriteSourceIds.first(),
            nearestChapterDistance = 0f,
            minChapterNumber = chapters.minOfOrNull { it.chapterNumber },
            maxChapterNumber = chapters.maxOfOrNull { it.chapterNumber },
            isDirectMirror = source.id == ComicKArtSource.SOURCE_ID,
        )
    }

    /** Rekonstrukce SManga z entity zapamatovaneho zdroje. Upsert (openPreview) null/blank
     *  hodnoty nikdy neprepisuje bohatsi ulozena metadata, takze staci pole, co entity nese. */
    private fun MangaEntity.toSManga() = SManga(
        sourceId = sourceId,
        url = url,
        title = title,
        coverUrl = coverUrl,
        description = description,
        status = status,
        author = author,
        artist = artist,
        genres = genres.split(",").filter { it.isNotBlank() },
        year = year,
        contentType = contentType,
        demographic = demographic,
        translationCompleted = translationCompleted,
        hasAnime = hasAnime,
        finalChapter = finalChapter,
        rating = rating,
        followCount = followCount,
        rank = rank,
    )

    fun selectCandidate(candidate: ResolvedCandidate) {
        val target = requestedChapterNumber ?: return
        _resolving.value = true
        viewModelScope.launch {
            try {
                val mangaId = repository.openPreview(candidate.manga)
                val resolvedChapters = repository.getAllChapters(mangaId)
                val bestMatch = resolvedChapters.minByOrNull { abs(it.chapterNumber - target) }
                if (bestMatch == null) {
                    _error.value = appContext.getString(R.string.resolver_chapter_missing_after_select)
                } else {
                    val finalChapter = resolveCompleteChapter(candidate, bestMatch, target)
                    // Realny zdroj (ktery appka jen tise pouziva na pozadi) se do knihovny
                    // NEPRIDAVA (viz MangaRepository.openPreview) - proto se postup cteni musi
                    // dostat zpet na SKUTECNY ComicK titul (ten uzivatel ma v knihovne), jinak by
                    // "Pokracovat ve cteni" i procenta na detailu titulu zustaly navzdy na 0 % i
                    // po precteni desitek kapitol - viz observeContinueReading (vyzaduje
                    // inLibrary = 1, ktere ComicK entita ma, ale resolvnuty realny zdroj nikdy).
                    //
                    // Nikdy tu NEOZNACUJEME kapitolu jako prectenou (drive read=true uz pri
                    // vyberu zdroje - kapitola se pocitala jako prectena hned po otevreni, i
                    // kdyz ji uzivatel zavrel na prvni strance; nahlaseny bug "jedna stranka =
                    // 100 %"). Misto toho ComicK kapitolu prelinkujeme na resolved kapitolu
                    // (fallbackChapterId) a skutecny postup/read na ni propaguje ctecka az
                    // pri cteni (viz ReaderViewModel.processPageProgress ->
                    // propagateReadProgressToLinkedChapters). updateLastReadChapter jen ukotvi
                    // "Pokracovat ve cteni" na tuto ComicK kapitolu.
                    val comicKId = comicKMangaId
                    if (comicKId != null) {
                        repository.setChapterFallbackTarget(chapterId, finalChapter.id)
                        repository.updateLastReadChapter(comicKId, chapterId)
                        // Zapamatovat zdroj, na kterem se skutecne cte (i kdyz
                        // resolveCompleteChapter presmerovalo na jineho kandidata) -
                        // pristi otevreni kapitoly titulu ho zkusi prvni misto sweepu.
                        repository.setResolvedManga(comicKId, finalChapter.mangaId)
                    }
                    _openedChapterId.value = finalChapter.id
                }
            } catch (e: Exception) {
                e.report("resolver:selectCandidate")
                _error.value = e.toFriendlyMessage()
            } finally {
                _resolving.value = false
            }
        }
    }

    /**
     * Zkontroluje, jestli ma [bestMatch] podezrele malo stranek (viz [isSuspiciouslyShort]), a
     * pokud ano, tise zkusi az 3 dalsi jiz nalezene kandidaty (viz [rankedCandidates]) - kdyz
     * nejaky ma vic stranek, appka na nej kapitolu presmeruje. Vysledek se trvale zapise (viz
     * ChapterEntity.verifiedPageCount/fallbackChapterId), takze se pri pristim otevreni teto
     * kapitoly cely tenhle proces preskoci (viz prvni vetev nize).
     */
    private suspend fun resolveCompleteChapter(candidate: ResolvedCandidate, bestMatch: ChapterEntity, target: Float): ChapterEntity {
        // Uz drive overeno - bud zustava, nebo presmerovat na drive nalezenou nahradu.
        if (bestMatch.verifiedPageCount != null) {
            val redirectId = bestMatch.fallbackChapterId
            if (redirectId != null) {
                repository.getChapter(redirectId)?.let { return it }
            }
            return bestMatch
        }

        // Skutecny pocet stranek puvodniho kandidata.
        val originalPages = try {
            kotlinx.coroutines.withTimeoutOrNull(FALLBACK_PAGE_CHECK_TIMEOUT_MS) {
                repository.getChapterPages(bestMatch.sourceId, bestMatch.url, candidate.manga.url)
            }
        } catch (e: Exception) {
            e.report("resolver:fallback:originalPages")
            null
        }
        if (originalPages == null) return bestMatch // network selhal/timeout - kontrola se proste neprovede, dnesni chovani

        // V poradku - zapamatovat a skoncit.
        if (!isSuspiciouslyShort(originalPages.size)) {
            repository.setVerifiedPageCount(bestMatch.id, originalPages.size, isFallback = false)
            return bestMatch
        }

        // Podezrele kratka - zkusit az 3 dalsi jiz nalezene kandidaty se stejnou kapitolou.
        val checked = mutableListOf<Pair<ChapterEntity, Int>>()
        val alternatives = rankedCandidates().filter { it.hasRequestedChapter && it.source.id != candidate.source.id }.take(3)
        for (alt in alternatives) {
            try {
                val altResult = kotlinx.coroutines.withTimeoutOrNull(FALLBACK_PAGE_CHECK_TIMEOUT_MS) {
                    val altMangaId = repository.openPreview(alt.manga)
                    val altChapters = repository.getAllChapters(altMangaId)
                    val altChapter = altChapters.firstOrNull { abs(it.chapterNumber - target) < 0.01f } ?: return@withTimeoutOrNull null
                    val altPages = repository.getChapterPages(altChapter.sourceId, altChapter.url, alt.manga.url)
                    altChapter to altPages.size
                }
                if (altResult != null) checked.add(altResult)
            } catch (e: Exception) {
                e.report("resolver:fallback:altPages:${alt.source.id}")
            }
        }

        // Vybrat nejlepsi (nebo zustat u puvodni).
        val better = pickBetterAlternative(originalPages.size, checked)
        return if (better == null) {
            // Na early-exit ceste (viz init{} - "hasAutoResolved") se hledani zrusi driv, nez
            // stihne najit dalsi kandidaty - kdyz checked zustalo prazdne, nevime jeste, jestli
            // opravdu neni lepsi zdroj, jen ze se zadny nestihl zkusit. Nezapisovat verifiedPageCount
            // v tomhle konkretnim pripade, aby se kontrola priste (az uz bude _candidates bohatsi)
            // zopakovala - jinak by early-exit cesta (nejcastejsi) tenhle fallback temer vzdy proste
            // vypla natrvalo hned pri prvnim pokusu (nalezeno finalnim whole-branch review).
            if (!(hasAutoResolved && checked.isEmpty())) {
                repository.setVerifiedPageCount(bestMatch.id, originalPages.size, isFallback = false)
            }
            bestMatch
        } else {
            repository.setVerifiedPageCount(bestMatch.id, originalPages.size, isFallback = false, fallbackChapterId = better.id)
            val betterPageCount = checked.first { it.first.id == better.id }.second
            repository.setVerifiedPageCount(better.id, betterPageCount, isFallback = true)
            better
        }
    }
}

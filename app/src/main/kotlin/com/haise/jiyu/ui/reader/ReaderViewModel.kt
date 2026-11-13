package com.haise.jiyu.ui.reader

import com.haise.jiyu.data.tracking.TrackerSyncCoordinator
import com.haise.jiyu.data.repository.HistoryRepository
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import coil.Coil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.tracking.KitsuRepository
import com.haise.jiyu.data.tracking.MalRepository
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.util.SleepTimerManager
import com.haise.jiyu.work.AutoDeleteWorker
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.data.repository.englishOnlyChapters
import com.haise.jiyu.data.repository.isEnglishChapter
import com.haise.jiyu.ui.resolver.ChapterRange
import com.haise.jiyu.ui.resolver.RESOLUTION_FRESH_MS
import com.haise.jiyu.ui.resolver.deriveGroupSignals
import com.haise.jiyu.ui.resolver.isGenericGroupToken
import com.haise.jiyu.ui.resolver.normalizeGroupToken
import com.haise.jiyu.ui.resolver.rankCandidates
import com.haise.jiyu.util.toFriendlyMessage
import com.haise.jiyu.data.repository.preferEnglishChapters
import com.haise.jiyu.source.Page
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import com.haise.jiyu.util.ErrorReporter
import com.haise.jiyu.util.LazyPageUrl
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toErrorAction

/** Jak dlouho po poslednim cteni jeste ma smysl obnovovat presnou stranku/scroll - viz [ReaderViewModel.loadChapter]. */
private const val POSITION_FRESHNESS_MS = 10L * 24 * 60 * 60 * 1000

/**
 * Strop na stažení seznamu stránek kapitoly (repository.getChapterPages) - beze stropu umí
 * RetryInterceptor × CloudflareInterceptor (viz AppModule.kt) v nejhorším případě viset i přes
 * minutu BEZE JAKÉKOLI výjimky (retry opakuje celý interceptor řetězec včetně interaktivního
 * Cloudflare řešení), takže appka jinak zůstane trvale na "načítání" bez chybové hlášky - přesně
 * to uživatel hlásil ("kapitola se někdy nenačte správně").
 */
private const val CHAPTER_LOAD_TIMEOUT_MS = 45_000L

/** Kolikrat appendNextWebtoonSegment zkusi fetch stranek dalsi kapitoly, nez se vzdá - viz retry smycka (dead-end fix). */
private const val APPEND_FETCH_ATTEMPTS = 3
private const val APPEND_RETRY_DELAY_MS = 2_000L

/** Kolik stranek pred koncem posledniho segmentu se v paged nekonecnem cteni
 *  spusti appendNextWebtoonSegment - viz [ReaderViewModel.onPagedFlatPageChanged]. */
private const val PAGED_APPEND_PREFETCH_DISTANCE = 4

/** Kolik stranek od ZACATKU prvniho segmentu se v paged nekonecnem cteni
 *  spusti prependPreviousWebtoonSegment - viz [ReaderViewModel.onPagedFlatPageChanged]. */
private const val PAGED_PREPEND_PREFETCH_DISTANCE = 4

/** Kolik stránek dopředu drží omezený (úsporný/zpoplatněná síť) prefetch - viz [ReaderViewModel.prefetchNextPage]. */
private const val LIMITED_PREFETCH_AHEAD = 3

private const val TAG = "ReaderPrefetch"

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val trackerSyncCoordinator: TrackerSyncCoordinator,
    private val sleepTimerManager: SleepTimerManager,
    private val networkMonitor: com.haise.jiyu.util.NetworkMonitor,
    private val errorActionHandler: com.haise.jiyu.source.ErrorActionHandler,
    private val sourceManager: com.haise.jiyu.source.SourceManager,
    private val pageGapFiller: com.haise.jiyu.source.PageGapFiller,
    private val comicKResolver: com.haise.jiyu.source.comick.ComicKChapterResolver,
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])
    private val _currentMangaId = MutableStateFlow<String?>(null)
    val mangaId: StateFlow<String?> = _currentMangaId.asStateFlow()

    /** Akce k selhání načtení kapitoly (Vyřešit ověření, nová adresa) - viz [com.haise.jiyu.util.ErrorAction]. */
    private val _chapterErrorAction = MutableStateFlow<com.haise.jiyu.util.ErrorAction?>(null)
    val chapterErrorAction: StateFlow<com.haise.jiyu.util.ErrorAction?> = _chapterErrorAction.asStateFlow()

    /** Provede nabízenou akci a při úspěchu načte kapitolu znovu. */
    fun performChapterErrorAction() {
        val action = _chapterErrorAction.value ?: return
        viewModelScope.launch {
            val retry = errorActionHandler.perform(action)
            _chapterErrorAction.value = null
            if (retry) launchLoadChapter(currentChapter?.id ?: chapterEntityId)
        }
    }
    private val startIncognito: Boolean = savedStateHandle["incognito"] ?: false
    private var currentChapter: ChapterEntity? = null
    private var currentManga: MangaEntity? = null
    private var allChapters: List<ChapterEntity> = emptyList()

    private val _allChaptersFlow = MutableStateFlow<List<ChapterEntity>>(emptyList())
    val allChaptersFlow: StateFlow<List<ChapterEntity>> = _allChaptersFlow.asStateFlow()

    private val _jumpToPage = MutableStateFlow<Int?>(null)
    val jumpToPage: StateFlow<Int?> = _jumpToPage.asStateFlow()
    fun jumpToPage(pageIndex: Int) { _jumpToPage.value = pageIndex }
    fun clearJump() { _jumpToPage.value = null }

    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    // Referer hlavicka pro stahovani obrazku aktualni kapitoly - viz
    // MangaRepository.sourceHomepage. Jeden referer pro celou kapitolu (ne per-stranka),
    // protoze vsechny stranky kapitoly patri stejnemu zdroji.
    private val _pageReferer = MutableStateFlow<String?>(null)
    val pageReferer: StateFlow<String?> = _pageReferer.asStateFlow()

    private val _comickUnavailable = MutableStateFlow(false)
    val comickUnavailable: StateFlow<Boolean> = _comickUnavailable.asStateFlow()

    private val _chapterComments = MutableStateFlow<List<com.haise.jiyu.source.comments.ChapterComment>>(emptyList())
    val chapterComments: StateFlow<List<com.haise.jiyu.source.comments.ChapterComment>> = _chapterComments.asStateFlow()

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()

    /** true, pokud AKTUALNI zdroj kapitoly komentare vubec poskytuje (viz MangaSource.
     * supportsChapterComments) - ridi, jestli se tlacitko "Komentare" v ctecce vubec zobrazi. */
    private val _commentsSupported = MutableStateFlow(false)
    val commentsSupported: StateFlow<Boolean> = _commentsSupported.asStateFlow()

    private var commentsJob: Job? = null

    fun loadChapterComments() {
        if (_chapterComments.value.isNotEmpty() || commentsJob?.isActive == true) return
        val chapter = currentChapter ?: return
        // coroutineContext.job = reference na sebe sama bez lateinit - drive `lateinit var job`
        // se mohla cist jeste neinicializovana, kdyby coroutine nabehla drive, nez launch()
        // vratil (audit - hypoteticky, ale levna pojistka).
        commentsJob = viewModelScope.launch {
            val self = coroutineContext.job
            _commentsLoading.value = true
            try {
                _chapterComments.value = repository.getChapterComments(chapter.sourceId, chapter.url)
            } catch (e: Exception) {
                e.report("reader:loadChapterComments")
            } finally {
                if (commentsJob === self) _commentsLoading.value = false
            }
        }
    }

    // Jednorazova hlaska "tahle kapitola byla dotazena z jineho zdroje" - viz
    // SourceResolverViewModel.resolveCompleteChapter a ChapterEntity.isFallbackSource.
    private val _fallbackNotice = MutableStateFlow<String?>(null)
    val fallbackNotice: StateFlow<String?> = _fallbackNotice.asStateFlow()
    fun clearFallbackNotice() { _fallbackNotice.value = null }

    /** true pokud je nastavený Supabase/Groq/Gemini klíč - jinak překlad jede jen přes on-device ML Kit. */

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _initialPage = MutableStateFlow(0)
    val initialPage: StateFlow<Int> = _initialPage.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    // ── Mezichapterová navigace ──────────────────────────────────────────────
    private val _hasPrevChapter = MutableStateFlow(false)
    val hasPrevChapter: StateFlow<Boolean> = _hasPrevChapter.asStateFlow()

    private val _hasNextChapter = MutableStateFlow(false)
    val hasNextChapter: StateFlow<Boolean> = _hasNextChapter.asStateFlow()

    private val _chapterTitle = MutableStateFlow("")
    val chapterTitle: StateFlow<String> = _chapterTitle.asStateFlow()

    // ID aktualne otevrene kapitoly - seznam kapitol v horni liste na nej scrolluje
    // a zvyraznuje ho, viz ReaderTopBar.
    private val _currentChapterId = MutableStateFlow<String?>(null)
    val currentChapterId: StateFlow<String?> = _currentChapterId.asStateFlow()

    // Nazev titulu (ne kapitoly) - horni lista ho zobrazuje klikatelny, viz onOpenManga
    // v ReaderScreen.
    private val _mangaTitle = MutableStateFlow("")
    val mangaTitle: StateFlow<String> = _mangaTitle.asStateFlow()

    // ── Nastavení čtení ──────────────────────────────────────────────────────
    private val _mangaDirectionOverride = MutableStateFlow<String?>(null)

    /** Per-manga přepsání směru/režimu čtení (null/LTR/RTL/WEBTOON, viz MangaEntity.
     *  readerDirectionOverride) - sheet nastavení čtečky ho nabízí k přepnutí. */
    val mangaDirectionOverride: StateFlow<String?> = _mangaDirectionOverride.asStateFlow()

    /** Surový globální směr čtení (neefektivní - per-manga override řeší [reverseLayout]);
     *  pro výchozí volbu v sheetu nastavení čtečky. */
    val readingDirection: StateFlow<String> = settings.readingDirection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadingDirection.LTR)

    val reverseLayout: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        settings.readingDirection,
        _mangaDirectionOverride,
    ) { globalDir, override ->
        when (override) {
            "RTL"     -> true
            "LTR"     -> false
            "WEBTOON" -> false
            else      -> globalDir == ReadingDirection.RTL
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isWebtoonMode: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        settings.readingMode,
        _mangaDirectionOverride,
    ) { globalMode, override ->
        override == "WEBTOON" || (override == null && globalMode == ReadingMode.WEBTOON)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val readingMode: StateFlow<String> = settings.readingMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadingMode.MANGA)

    val tapZonesEnabled: StateFlow<Boolean> = settings.tapZonesEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val tapZoneGrid: StateFlow<TapZoneGrid> = settings.tapZoneGrid
        .map { TapZoneGrid.deserialize(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TapZoneGrid())

    fun setTapZoneGrid(grid: TapZoneGrid) {
        viewModelScope.launch { settings.setTapZoneGrid(grid.serialize()) }
    }

    val webtoonScrollSpeed: StateFlow<Float> = settings.webtoonScrollSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)


    val doublePageSpread: StateFlow<Boolean> = settings.doublePageSpread
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val fullscreenEnabled: StateFlow<Boolean> = settings.fullscreenEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val readerTheme: StateFlow<String> = settings.readerTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "dark")

    val oledMode: StateFlow<Boolean> = settings.oledMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val pageCurlEnabled: StateFlow<Boolean> = settings.pageCurlEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val curlStyle: StateFlow<String> = settings.curlStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.haise.jiyu.settings.CurlStyleSetting.CLASSIC)

    val pageScale: StateFlow<String> = settings.pageScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fit_width")

    val autoNextChapter: StateFlow<Boolean> = settings.autoNextChapter
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val infiniteScrollEnabled: StateFlow<Boolean> = settings.infiniteScrollEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val cropBorders: StateFlow<Boolean> = settings.cropBorders
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val volumeKeysNav: StateFlow<Boolean> = settings.volumeKeysNav
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val readerOrientation: StateFlow<String> = settings.readerOrientation
        .stateIn(viewModelScope, SharingStarted.Eagerly, "free")

    val skipReadChapters: StateFlow<Boolean> = settings.skipReadChapters
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Prefetch CELÉ kapitoly jen na nezpoplatněné síti (výchozí zapnuto) - viz
     * [shouldLimitPrefetch]. Na mobilních datech se místo ~5-15 MB předstahování
     * použije rolling +1 stránka jako v úsporném režimu.
     */
    private val prefetchPagesWifiOnly: StateFlow<Boolean> = settings.prefetchPagesWifiOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setReaderOrientation(orientation: String) { viewModelScope.launch { settings.setReaderOrientation(orientation) } }

    // ── Settery pro sheet nastavení čtečky ───────────────────────────────────
    // Všechny jen delegují na SettingsRepository (DataStore) - čtečka hodnoty čte zpět
    // přes StateFlow výše, takže změna se projeví okamžitě bez restartu kapitoly.

    /** Per-manga přepsání režimu/směru (null = globální výchozí) - stejný zápis jako
     *  MangaDetailViewModel.setReaderDirection, plus okamžitá aktualizace lokálního
     *  stavu, aby se čtečka přepnula bez čekání na re-emisi z DB. */
    fun setMangaReaderDirection(direction: String?) {
        _mangaDirectionOverride.value = direction
        val id = currentManga?.id ?: return
        viewModelScope.launch { repository.setMangaReaderDirection(id, direction) }
    }

    fun setReadingMode(mode: String) { viewModelScope.launch { settings.setReadingMode(mode) } }
    fun setReadingDirection(dir: String) { viewModelScope.launch { settings.setReadingDirection(dir) } }
    fun setPageScale(scale: String) { viewModelScope.launch { settings.setPageScale(scale) } }
    fun setDoublePageSpread(enabled: Boolean) { viewModelScope.launch { settings.setDoublePageSpread(enabled) } }
    fun setCropBorders(enabled: Boolean) { viewModelScope.launch { settings.setCropBorders(enabled) } }
    fun setOledMode(enabled: Boolean) { viewModelScope.launch { settings.setOledMode(enabled) } }
    fun setFullscreenEnabled(enabled: Boolean) { viewModelScope.launch { settings.setFullscreenEnabled(enabled) } }
    fun setWebtoonScrollSpeed(speed: Float) { viewModelScope.launch { settings.setWebtoonScrollSpeed(speed) } }
    fun setTapZonesEnabled(enabled: Boolean) { viewModelScope.launch { settings.setTapZonesEnabled(enabled) } }
    fun setVolumeKeysNav(enabled: Boolean) { viewModelScope.launch { settings.setVolumeKeysNav(enabled) } }
    fun setKeepScreenOn(enabled: Boolean) { viewModelScope.launch { settings.setKeepScreenOn(enabled) } }
    fun setSkipReadChapters(enabled: Boolean) { viewModelScope.launch { settings.setSkipReadChapters(enabled) } }
    fun setAutoNextChapter(enabled: Boolean) { viewModelScope.launch { settings.setAutoNextChapter(enabled) } }
    fun setInfiniteScrollEnabled(enabled: Boolean) { viewModelScope.launch { settings.setInfiniteScrollEnabled(enabled) } }
    fun setPageCurlEnabled(enabled: Boolean) { viewModelScope.launch { settings.setPageCurlEnabled(enabled) } }
    fun setCurlStyle(style: String) { viewModelScope.launch { settings.setCurlStyle(style) } }
    fun setReaderTheme(theme: String) { viewModelScope.launch { settings.setReaderTheme(theme) } }

    // ── Přednačítání další kapitoly ──────────────────────────────────────────
    // ConcurrentHashMap - pise se z IO (preloadNextChapter, appendNextWebtoonSegment) a cte/maze z Main
    // (loadChapter); obycejna mapa dovolovala ConcurrentModificationException (audit nalez JIYU-UI-5).
    // Seznam URL stranek predstahovanych sousednich kapitol (next i prev) - loadChapter
    // z nej bere hotovy vysledek misto sitoveho fetchu (viz preloadNextChapter/preloadPrevChapter).
    private val adjacentChapterCache = ConcurrentHashMap<String, List<String>>()
    // Rozběhnuté přednačítací požadavky běží jako potomci viewModelScope (execute v
    // prefetchPagesFrom) - po odchodu ze čtečky se zruší s ním a dál nedrží sloty
    // hostitele ani nezdržují obálky/loga ve výpisu zdroje.
    private var preloadJob: Job? = null
    // Zpetna symetrie [preloadJob]/[preloadChapterId] - predstahovani predchozi kapitoly,
    // viz preloadPrevChapter (samostatne pole, aby prev/next preoady neutekly pres sebe).
    private var prevPreloadJob: Job? = null
    private var prevPreloadChapterId: String? = null
    // Kapitola, pro kterou preloadJob prave stahuje stranky - appendNextWebtoonSegment
    // se na ni pripoji (join) misto druheho paralelniho fetchu.
    private var preloadChapterId: String? = null
    private var spreadDetectJob: Job? = null

    // Jediné probíhající načtení kapitoly - rychlé přepínání (další/předchozí, skok) by jinak
    // nechalo běžet víc loadChapter najednou, které si mezi suspend body přepisují stejné StateFlow.
    private var loadJob: Job? = null

    // ── Přepínač zdrojů (agregovaný ComicK režim) - stav musí být deklarovaný
    // PŘED init blokem: launchLoadChapter z init běží na unconfined dispatcheru
    // eager už během konstrukce a pole deklarovaná až za init by tehdy byla null.
    private val _comickParent = MutableStateFlow<com.haise.jiyu.data.db.entity.MangaEntity?>(null)
    /** Non-null = kapitola patri agregovanemu ComicK titulu -> horni lista ukaze tlacitko prepinace. */
    val comickParent: StateFlow<com.haise.jiyu.data.db.entity.MangaEntity?> = _comickParent.asStateFlow()

    private val _sourceSwitchRows = MutableStateFlow<List<SourceSwitchRow>>(emptyList())
    val sourceSwitchRows: StateFlow<List<SourceSwitchRow>> = _sourceSwitchRows.asStateFlow()

    /** true = probiha prepinani (openPreview + hledani kapitoly) - sheet ukaze spinner na radce. */
    private val _sourceSwitching = MutableStateFlow(false)
    val sourceSwitching: StateFlow<Boolean> = _sourceSwitching.asStateFlow()

    /** true = na pozadi bezi sweep zdroju (stara cache) - sheet ukaze "hledam dalsi" stav. */
    private val _sourceScanning = MutableStateFlow(false)
    val sourceScanning: StateFlow<Boolean> = _sourceScanning.asStateFlow()

    private var rescanJob: Job? = null

    private fun launchLoadChapter(id: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadChapter(id) }
    }
    private var novelPreloadJob: Job? = null

    /** Indexy stránek AKTUÁLNÍ kapitoly, pro které už proběhl prefetch - viz reset v [loadChapter].
     *  ConcurrentHashMap-backed set - paralelni prefetch workery (PREFETCH_PARALLELISM) ho
     *  zapisuji z Dispatchers.IO a prefetchNextPage z Main; obycejny set by riskoval
     *  ConcurrentModificationException. */
    private val prefetchedPageIndices = ConcurrentHashMap.newKeySet<Int>()

    // Sekvencni prefetch cele kapitoly (viz startChapterPrefetch) - jeden job na kapitolu,
    // pri vymene kapitoly se zrusi a spusti novy.
    private var chapterPrefetchJob: Job? = null

    private val _webtoonScrollOffset = MutableStateFlow(0)
    val webtoonScrollOffset: StateFlow<Int> = _webtoonScrollOffset.asStateFlow()

    // ── Nekonečné čtení (webtoon segmenty) ───────────────────────────────────
    //
    // Mimo "Nekonečné čtení" má tenhle seznam vždy přesně JEDEN segment (aktuálně otevřenou
    // kapitolu - viz konec loadChapter) a chová se úplně stejně jako dřívější plochý seznam
    // `pages` předávaný do WebtoonReaderu. Se zapnutým nastavením appendNextWebtoonSegment()
    // přidává na konec DALŠÍ kapitolu (mísí se do souvislého scrollu) - vědomě se NEMAŽE
    // (viz dokumentace u onWebtoonVisibleChapterChanged, proč).
    private val _webtoonSegments = MutableStateFlow<List<WebtoonSegment>>(emptyList())
    val webtoonSegments: StateFlow<List<WebtoonSegment>> = _webtoonSegments.asStateFlow()

    // True po dobu, co appendNextWebtoonSegment stahuje stranky dalsi kapitoly -
    // WebtoonReader tim ukaze "Nacitam dalsi kapitolu" radek na konci listu misto
    // tiche slepe ulicky (live audit: ~28 s bez jakekoliv odezvy).
    private val _webtoonAppendingNext = MutableStateFlow(false)
    val webtoonAppendingNext: StateFlow<Boolean> = _webtoonAppendingNext.asStateFlow()
    private var appendingSegmentJob: Job? = null

    // Zpetna symetrie nekonecneho cteni - viz prependPreviousWebtoonSegment.
    private val _webtoonPrependingPrev = MutableStateFlow(false)
    val webtoonPrependingPrev: StateFlow<Boolean> = _webtoonPrependingPrev.asStateFlow()
    private var prependingSegmentJob: Job? = null

    // Stabilni identita otevreneho proudu nekonecneho cteni ("epoch" pro klice
    // ctecek). Nastavuje se JEN pri plnem otevreni kapitoly (loadChapter) -
    // append/prepend ho nemeni. Bez nej by prepend zmenil `segments.first()` a
    // paged ctecka by jako na novou kapitolu resetovala pozici na initialPage.
    private val _webtoonEpoch = MutableStateFlow<String?>(null)
    val webtoonEpoch: StateFlow<String?> = _webtoonEpoch.asStateFlow()

    // Scroll ve webtoon rezimu emituje pozici na kazdy pixel behem flingu - zapis do DB
    // na kazdou zmenu by appku zbytecne zatezoval. Misto toho se pri kazde zmene zrusi
    // predchozi cekajici zapis a naplanuje novy o 600 ms pozdeji, takze se skutecne
    // zapise az kdyz se scrollovani na chvili zastavi (presne tam, kde uzivatel realne
    // skoncil cteni), ne prubezne behem pohybu.
    private var scrollPersistJob: Job? = null
    // Posledni debounce-ovany offset cekajici na DB zapis - onCleared ho flushne,
    // jinak se poslednich ~600 ms scroll pozice pri killu/obrazovce-zpet ztratilo.
    private var pendingScrollPersist: Pair<String, Int>? = null

    fun saveWebtoonScrollOffset(offset: Int) {
        val chapterId = currentChapter?.id ?: return
        if (_incognitoMode.value) return
        // In-memory hodnota hned (pro restore po config-change) - do DB se zapise az
        // debounce-ovane nize; driv se _webtoonScrollOffset menil jen v loadChapter a
        // po rotaci se obnovoval stale pixel offset z doby otevreni kapitoly (audit).
        _webtoonScrollOffset.value = offset
        pendingScrollPersist = chapterId to offset
        scrollPersistJob?.cancel()
        scrollPersistJob = viewModelScope.launch {
            delay(600L)
            repository.updateScrollOffset(chapterId, offset, System.currentTimeMillis())
            pendingScrollPersist = null
        }
    }

    // ── Incognito mode ───────────────────────────────────────────────────────
    private val _incognitoMode = MutableStateFlow(startIncognito)
    val incognitoMode: StateFlow<Boolean> = _incognitoMode.asStateFlow()
    fun toggleIncognito() { _incognitoMode.value = !_incognitoMode.value }

    // ── Session timer ────────────────────────────────────────────────────────
    private val sessionStartMs = System.currentTimeMillis()
    private val _sessionElapsed = MutableStateFlow(0L)
    val sessionElapsed: StateFlow<Long> = _sessionElapsed.asStateFlow()

    private val _isOfflineChapter = MutableStateFlow(false)
    val isOfflineChapter: StateFlow<Boolean> = _isOfflineChapter.asStateFlow()

    private val _isNovelSource = MutableStateFlow(false)
    val isNovelSource: StateFlow<Boolean> = _isNovelSource.asStateFlow()

    private val _novelText = MutableStateFlow("")
    val novelText: StateFlow<String> = _novelText.asStateFlow()

    private val _chapterIndex = MutableStateFlow(0)
    private val _chapterCount = MutableStateFlow(0)
    val chapterProgress: StateFlow<Float> = kotlinx.coroutines.flow.combine(_chapterIndex, _chapterCount) { idx, count ->
        if (count <= 1) 0f else idx.toFloat() / (count - 1).toFloat()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    private val _spreadPageIndices = MutableStateFlow<Set<Int>>(emptySet())
    val spreadPageIndices: StateFlow<Set<Int>> = _spreadPageIndices.asStateFlow()

    // ── Sleep timer (#42) ────────────────────────────────────────────────────
    val sleepTimerRemaining: StateFlow<Int?> = sleepTimerManager.remainingSeconds

    // ── Panel mode (#38) ─────────────────────────────────────────────────────
    private val _panelMode = MutableStateFlow(false)
    val panelMode: StateFlow<Boolean> = _panelMode.asStateFlow()

    private val _panelRects = MutableStateFlow<List<android.graphics.Rect>>(emptyList())
    val panelRects: StateFlow<List<android.graphics.Rect>> = _panelRects.asStateFlow()

    private val _currentPanel = MutableStateFlow(0)
    val currentPanel: StateFlow<Int> = _currentPanel.asStateFlow()

    // ── Viditelnost ovládacích prvků (auto-hide po 3s) ───────────────────────
    // Dřív žila jako rememberSaveable přímo v ReaderContent (Composable) - přesunuto sem,
    // aby ReaderContent zůstal čistě parametrický a auto-hide časovač šel testovat/sledovat
    // nezávisle na Compose lifecycle.
    private val _controlsVisible = MutableStateFlow(true)
    val controlsVisible: StateFlow<Boolean> = _controlsVisible.asStateFlow()
    private var controlsHideJob: Job? = null

    fun toggleControlsVisible() {
        _controlsVisible.value = !_controlsVisible.value
        scheduleControlsAutoHide()
    }

    private fun scheduleControlsAutoHide() {
        controlsHideJob?.cancel()
        if (_controlsVisible.value && !advancedSheetOpen && !edgeScrubActive && !sourceSheetOpen) {
            controlsHideJob = viewModelScope.launch {
                delay(5_000L)
                _controlsVisible.value = false
            }
        }
    }

    private var advancedSheetOpen = false
    private var edgeScrubActive = false
    private var sourceSheetOpen = false

    /**
     * Hold na auto-hide pro prepinac zdroju - stejny duvod jako
     * [onAdvancedSheetVisibilityChanged]: sheet se vsemi zdroji by se jinak zavrel
     * spolu s hornim/spodnim panelem po 5 s (nahlaseny bug).
     */
    fun onSourceSheetVisibilityChanged(visible: Boolean) {
        sourceSheetOpen = visible
        if (visible) controlsHideJob?.cancel() else scheduleControlsAutoHide()
    }

    /**
     * Stejny hold na auto-hide jako [onAdvancedSheetVisibilityChanged], jen pro edge
     * scrubber (ReaderEdgeScrubber) - jinak by se controls i scrubber schovaly uprostred
     * tazeni, pokud drag prekroci 5s auto-hide limit.
     */
    fun onEdgeScrubActive(active: Boolean) {
        edgeScrubActive = active
        if (active) controlsHideJob?.cancel() else scheduleControlsAutoHide()
    }

    /**
     * Pokud je otevreny "Dalsi moznosti" sheet ve spodni liste (preklad/jazyky/
     * orientace...), auto-hide se úplně zastaví - dřív zmizel i s otevřeným sheetem
     * po 3s bez ohledu na to, ze uzivatel s nim aktivne pracuje. Zavře se
     * jen explicitním tapnutím mimo (sheet's own dismiss).
     */
    fun onAdvancedSheetVisibilityChanged(visible: Boolean) {
        advancedSheetOpen = visible
        if (visible) {
            controlsHideJob?.cancel()
        } else {
            scheduleControlsAutoHide()
        }
    }


    /**
     * Konec odpočtu se ohlašuje tudy, ne callbackem - předávaná lambda `{ activity.finish() }`
     * držela naživu celou Activity, viz [SleepTimerManager].
     */
    val sleepTimerFinished: SharedFlow<Unit> = sleepTimerManager.finished

    fun startSleepTimer(minutes: Int) = sleepTimerManager.start(minutes)

    fun cancelSleepTimer() = sleepTimerManager.cancel()

    fun togglePanelMode() { _panelMode.value = !_panelMode.value; _currentPanel.value = 0 }

    fun nextPanel() {
        val rects = _panelRects.value
        if (rects.isEmpty()) return
        _currentPanel.value = (_currentPanel.value + 1).coerceAtMost(rects.lastIndex)
    }

    fun prevPanel() {
        _currentPanel.value = (_currentPanel.value - 1).coerceAtLeast(0)
    }

    fun detectPanels(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _panelRects.value = analyzePanelBorders(bitmap)
            _currentPanel.value = 0
        }
    }

    private fun analyzePanelBorders(bmp: android.graphics.Bitmap): List<android.graphics.Rect> {
        val w = bmp.width
        val h = bmp.height
        val threshold = 80
        val minPanelHeight = h / 12

        // Analyza na zmensene kopii - webtoon stranky byvaji klidne 2000x30000 px, IntArray
        // w*h by sam sezral ~240 MB heapu a OOMl appku pri prvnim Panel mode (audit).
        // Na detekci horizontalnich rezu staci ~1.3 MP; vysledne rezy se prepocitaji zpatky.
        val maxAnalysisPixels = 1_300_000L
        val scale = kotlin.math.sqrt(maxAnalysisPixels.toDouble() / (w.toLong() * h)).coerceAtMost(1.0)
        val aw = (w * scale).toInt().coerceAtLeast(1)
        val ah = (h * scale).toInt().coerceAtLeast(1)
        val work = if (scale < 1.0) android.graphics.Bitmap.createScaledBitmap(bmp, aw, ah, true) else bmp

        // Single getPixels() call instead of w/4 * h individual JNI calls
        val pixels = IntArray(aw * ah)
        work.getPixels(pixels, 0, aw, 0, 0, aw, ah)
        if (work !== bmp) work.recycle()

        val aMinPanelHeight = ah / 12
        val horizontalCuts = mutableListOf(0)
        for (y in 0 until ah) {
            var darkCount = 0
            var x = 0
            while (x < aw) {
                val pixel = pixels[y * aw + x]
                val brightness = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                if (brightness < threshold) darkCount++
                x += 4
            }
            if (darkCount > aw / 8 && (horizontalCuts.last() == 0 || y - horizontalCuts.last() > aMinPanelHeight)) {
                horizontalCuts.add(y)
            }
        }
        horizontalCuts.add(ah)

        // Prepoctat rezy zpet na souradnice plneho obrazku.
        val invScale = 1.0 / scale
        return (0 until horizontalCuts.lastIndex).map { i ->
            android.graphics.Rect(
                0,
                (horizontalCuts[i] * invScale).toInt().coerceIn(0, h),
                w,
                (horizontalCuts[i + 1] * invScale).toInt().coerceIn(0, h),
            )
        }.filter { it.height() > minPanelHeight }
    }

    private var lastPageChangeMs = 0L

    // Deklarace MUSI byt pred `init` (viz nize) - jinak by je init blok videl jako null.
    private val pageProgressEvents = Channel<PageProgressEvent>(Channel.UNLIMITED)

    /** Kapitoly, u kterych uz tato relace dosla na posledni stranku - trackery a auto-delete jen jednou. */
    private val markedReadChapterIds = mutableSetOf<String>()

    /**
     * Kapitoly dočtené v téhle relaci, jejichž smazání (autoDeleteDelayDays == 0) čeká na OPUŠTĚNÍ
     * čtečky nebo přepnutí kapitoly. Dřív se soubory mazaly hned po dosažení poslední stránky, tedy
     * zatímco je uživatel pořád viděl (file:// stránky) - rotace nebo krok zpět pak ukázaly prázdno
     * (audit nalez JIYU-UI-4). Sahá na ně jen hlavní vlákno (konzument událostí + loadChapter + onCleared).
     */
    private val chaptersPendingAutoDelete = mutableSetOf<String>()

    init {
        scheduleControlsAutoHide()
        // Jediny konzument udalosti o postupu cteni - viz onPageChanged. Chyba jedne udalosti
        // nesmi zastavit zpracovani dalsich (ani shodit appku).
        viewModelScope.launch {
            for (event in pageProgressEvents) {
                try {
                    processPageProgress(event)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.report("reader:pageProgress")
                }
            }
        }
        launchLoadChapter(chapterEntityId)
        // Série čtení je taky zapsaná stopa, takže ji anonymní čtení nezvedá. Rozhoduje stav
        // při otevření kapitoly - přepnutí přepínače uprostřed už zpětně nic neubírá.
        if (!startIncognito) viewModelScope.launch { settings.updateReadingStreak() }
        // repeatOnLifecycle(STARTED) na ProcessLifecycleOwner - bez tohohle tikal ticker i po
        // zaminimalizovani appky, protoze viewModelScope zije, dokud existuje ViewModel (cela
        // obrazovka), ne dokud je appka v popredi (nahlaseno v auditu).
        viewModelScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1000)
                    _sessionElapsed.value = System.currentTimeMillis() - sessionStartMs
                }
            }
        }
    }

    // ── Načítání kapitoly ────────────────────────────────────────────────────

    private suspend fun loadChapter(id: String) {
        _loading.value = true
        _chapterErrorAction.value = null
        _pages.value = emptyList()
        prefetchedPageIndices.clear()
        chapterPrefetchJob?.cancel()
        chapterPrefetchJob = null
        _chapterComments.value = emptyList()
        _commentsSupported.value = false
        commentsJob?.cancel()
        commentsJob = null
        // Reset i flag - finally zruseneho jobu ho pod `commentsJob === self` uz
        // nevycisti (pole je null), takze by v nove kapitole visel vlecny spinner.
        _commentsLoading.value = false
        val chapter = repository.getChapter(id) ?: run { _loading.value = false; return }
        currentChapter = chapter
        // Po proces-deathu (Android zabil appku na pozadi, typicky po delsim
        // spanku telefonu) se VM vytvori znovu a init cte chapterId ze
        // savedStateHandle - bez aktualizace by se otevřela PUVODNI kapitola z nav
        // argumentu a ctenare to hodilo o kapitoly zpet (nahlaseno: "vrati me o
        // 1-4 kapitoly zpatky"). Presnou stranku pak doplni lastPageRead/scroll
        // offset z DB, ktery se zapisuje debounce-ovane pri listovani.
        savedStateHandle["chapterId"] = chapter.id
        _commentsSupported.value = repository.sourceSupportsChapterComments(chapter.sourceId)
        // ComicK rodic pro prepinac zdroju v horni liste (uzivatelsky pozadavek:
        // rucni prepnuti na jiny zdroj, kde kapitoly jsou). null u bezneho
        // neagregovaneho titulu = tlacitko se neukaze.
        _comickParent.value = repository.comickParentForResolvedChapter(chapter.id)
        _sourceSwitchRows.value = emptyList()
        // Hlaska se MUSI vycistit i pro ne-fallback kapitolu - jinak by po prechodu
        // z fallback kapitoly na normalni zustala viset navzdy (audit - stale notice).
        _fallbackNotice.value = if (chapter.isFallbackSource) {
            context.getString(R.string.reader_fallback_source_notice)
        } else null
        _chapterTitle.value = chapter.name
        _currentChapterId.value = chapter.id
        // Presna pozice (stranka + scroll) se pamatuje jen POSITION_FRESHNESS_MS od posledniho
        // cteni - starsi otevreme rovnou od zacatku kapitoly, viz [POSITION_FRESHNESS_MS].
        val positionIsFresh = System.currentTimeMillis() - chapter.lastReadAt <= POSITION_FRESHNESS_MS
        _initialPage.value = if (positionIsFresh) chapter.lastPageRead.coerceAtLeast(0) else 0
        _currentPage.value = _initialPage.value
        _webtoonScrollOffset.value = if (positionIsFresh) chapter.lastScrollOffset else 0

        // Vicejazycne agregatory (ComicK, comickart): bez prefer-EN filtru by
        // navigace/next+prev a nekonecny proud skakaly do cizojazycne verze
        // tehoz cisla kapitoly (nhlaseno uzivatelem u Sato-san). Filtrovany radek
        // muze byt i otevrena kapitola sama - guard pod nim ji v takovem pripade
        // dosadi zpet, aby indexOfFirst nikdy nevracel -1.
        val dedupedChapters = repository.getAllChapters(chapter.mangaId).preferEnglishChapters()
        // Navigace prev/next a nekonecny proud stejne jako detail seznam jen pres
        // EN-oteviratelne radky (resolver na ne-EN odpovi errorem); fallback na
        // plny seznam, kdyby titul EN kapitoly nemel vubec (cela rada v jinem
        // jazyce - pak nechame navigaci zit).
        val loadedChapters = dedupedChapters.englishOnlyChapters().ifEmpty { dedupedChapters }
        // Ochrana proti relinku: kdyz refresh/migrace mezi getChapter a getAllChapters
        // nahradil otevrenou kapitolu entitou s jinym id, dosadime ji zpet na jeji
        // misto v DESC poradi. Bez tohohle indexOfFirst vracel -1 -> navigace byla
        // mrtva a davno navigatePrev hodil ctenare na allChapters[0] (ch1 -> ~200).
        allChapters = if (loadedChapters.any { it.id == chapter.id }) {
            loadedChapters
        } else {
            ErrorReporter.breadcrumb(
                "reader:chapterMissing:loadChapter",
                "chapterId=${chapter.id} mangaId=${chapter.mangaId} listSize=${loadedChapters.size} - inserted back",
            )
            loadedChapters.insertSortedDesc(chapter)
        }
        _allChaptersFlow.value = allChapters
        _chapterCount.value = allChapters.size
        _chapterIndex.value = chapterIndexOrTrace(chapter.id, "loadChapter").coerceAtLeast(0)
        updateNavState()

        val mangaForDir = repository.getManga(chapter.mangaId)
        currentManga = mangaForDir
        _currentMangaId.value = mangaForDir?.id
        _mangaTitle.value = mangaForDir?.title ?: ""
        _mangaDirectionOverride.value = mangaForDir?.readerDirectionOverride

        // Rozbehla detekce dvoustran z PREDCHOZI kapitoly nesmi po prepnuti zapsat do noveho stavu.
        spreadDetectJob?.cancel()
        // Predchozi docetene kapitoly cekajici na smazani (auto-delete 0 dni) se ted, po opusteni,
        // smazou - jen ne ta, kterou prave otevirame.
        chaptersPendingAutoDelete.remove(chapter.id)
        flushPendingAutoDelete()
        if (chapter.sourceId == "comick") {
            // ComicK je zatim jen metadatovy katalog - nikdy nedokaze poskytnout stranky kapitoly.
            // Blokujeme na urovni ctecky (nejen v detailu titulu), aby se nezobrazoval prazdny/chybovy stav.
            //
            // Kapitola uz ale muze byt drive resolvovana na realnou kapitolu jineho zdroje
            // (fallbackChapterId - viz SourceResolverViewModel.selectCandidate /
            // MangaRepository.propagateReadProgressToLinkedChapters). V takovem pripade se
            // preskoci cely "Najit zdroj" wall a nacte se rovnou resolved cil - toto je
            // i cesta, kterou jede "Pokracovat ve cteni" (lastReadChapterId ComicK titulu
            // vzdy ukazuje na ComicK kapitolu).
            val resolved = chapter.fallbackChapterId
                ?.let { repository.getChapter(it) }
                ?.takeIf { it.sourceId != "comick" }
            if (resolved != null) {
                loadChapter(resolved.id)
                return
            }
            _pages.value = emptyList()
            _isOfflineChapter.value = false
            _isNovelSource.value = false
            _spreadPageIndices.value = emptySet()
            _comickUnavailable.value = true
        } else if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
            _comickUnavailable.value = false
            // Stranky jsou lokalni, referer nepouzivaji - ale drzime ho na zdroji kapitoly,
            // aby nikdy nezustal viset z predchozi (audit: stale referer z cache cesty).
            _pageReferer.value = repository.sourceHomepage(chapter.sourceId)
            val pageUrls = withContext(kotlinx.coroutines.Dispatchers.IO) { ChapterStorage.listPageUrls(context, chapter.localPath) }
            _pages.value = pageUrls
            _isOfflineChapter.value = true
            // Detect landscape pages for smart spread grouping - mimo Main (BitmapFactory + u SAF
            // ContentResolver blokuje kazdou stranku), audit nalez JIYU-UI-2.
            detectSpreadPages(pageUrls)
        } else {
            _comickUnavailable.value = false
            _isOfflineChapter.value = false
            _spreadPageIndices.value = emptySet()
            // Referer se nastavuje pro KAZDOU online cestu - i tu, kde stranky prisly
            // z adjacentChapterCache. Drive se nastavoval az ve vetvi rucniho fetchu, takze
            // preloadnuta kapitola jela se stale/null refererem a u hotlink-chranenych
            // CDN se stranky vracely jako 403 (cerne stranky - viz audit).
            _pageReferer.value = repository.sourceHomepage(chapter.sourceId)
            val cached = adjacentChapterCache.remove(chapter.id)
            if (cached != null) {
                _isNovelSource.value = false
                _novelText.value = ""
                _pages.value = cached
            } else {
                val manga = repository.getManga(chapter.mangaId)
                if (manga != null) {
                    // withTimeoutOrNull - viz CHAPTER_LOAD_TIMEOUT_MS dokumentace: bez tohohle
                    // stropu umi zdroj chraneny Cloudflare (RetryInterceptor okolo
                    // CloudflareInterceptor, AppModule.kt) viset beze jakekoli vyjimky i pres
                    // minutu, appka by pak zustala trvale na "nacitani".
                    val rawPages = try {
                        kotlinx.coroutines.withTimeoutOrNull(CHAPTER_LOAD_TIMEOUT_MS) {
                            repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.report("reader:loadChapter:getChapterPages")
                        _chapterErrorAction.value = e.toErrorAction()
                        null
                    }
                    val isNovel = rawPages?.any { it.imageUrl == "novel://text" } ?: false
                    _isNovelSource.value = isNovel
                    when {
                        rawPages == null -> {
                            // Vyjimka (nahlasena vyse) nebo vyprseni CHAPTER_LOAD_TIMEOUT_MS -
                            // prazdny seznam stranek uz UI zobrazi jako "Kapitolu se nepodařilo
                            // načíst.", misto vecneho viseni na "nacitani".
                            _novelText.value = ""
                            _pages.value = emptyList()
                        }
                        isNovel -> {
                            _novelText.value = rawPages.firstOrNull()?.url ?: ""
                            _pages.value = emptyList()
                        }
                        else -> {
                            _novelText.value = ""
                            _pages.value = rawPages.map { pageDisplayUrl(it, chapter.sourceId) }
                        }
                    }
                }
            }
        }
        // Pozice obnovena z lastPageRead muze byt za koncem noveho seznamu stranek
        // (re-scrape s mene strankami, orezane extras, relink na zdroj s jinou
        // paginaci). Bez clampu by out-of-range index hned vyhodnotil reachedEnd=true
        // -> kapitola by se sama oznacila jako prectena a spustila tracker sync i
        // pripadnou auto-delete registraci, ani ze by uzivatel stranku videl (audit
        // RD-5). Novel/offline-error cesty maji _pages prazdne - tam je index jedno.
        if (_pages.value.isNotEmpty()) {
            val clampedPage = _initialPage.value.coerceIn(0, _pages.value.size - 1)
            if (clampedPage != _initialPage.value) {
                _initialPage.value = clampedPage
                _currentPage.value = clampedPage
            }
        }
        // Usporny rezim NEBO zpoplatnena sit (viz shouldLimitPrefetch): jen stranka dopredu
        // pri kazdem otoceni. Normalni rezim: sekvencni prefetch cele kapitoly 0->N jednou za kapitolu.
        if (shouldLimitPrefetch()) {
            Log.d(TAG, "prefetch: OMEZENY rezim (saving=${com.haise.jiyu.util.DeviceResourcePolicy.isSavingResources(context)}, unmetered=${networkMonitor.isUnmetered}, wifiOnly=${prefetchPagesWifiOnly.value}) - jen +$LIMITED_PREFETCH_AHEAD dopredu")
            prefetchNextPage(_initialPage.value)
        } else {
            startChapterPrefetch()
        }
        // Otevreni rovnou u okraje kapitoly (resume posledni stranky / prvni) - bez
        // otoceni se onPageChanged nezavola, takze sousedni kapitolu predstahnout hned.
        if (_pages.value.isNotEmpty()) {
            if (_initialPage.value <= 2 && _hasPrevChapter.value) preloadPrevChapter()
            if (_initialPage.value >= _pages.value.size - 3 && _hasNextChapter.value) preloadNextChapter()
        }
        lastPageChangeMs = System.currentTimeMillis()
        _loading.value = false
        // "Naposledy ctena kapitola" se zapise uz pri OTEVRENI, ne az pri prvnim otoceni
        // stranky - pristavaci stranka snapshotFlow neemituje, takze preskoceni na jinou
        // kapitolu + odchod bez otoceni nechal "Pokracovat" trvale na predchozi kapitole
        // (hlaseno: skok z kap.0 na kap.5 a domu stale ukazovalo 0). Pod inkognitem se
        // jako vsude nezapisuje nic.
        if (!_incognitoMode.value) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                repository.recordChapterOpened(chapter.id, chapter.mangaId)
            }
        }
        // Kazde plne nacteni kapitoly (jumpToChapter/navigateNext/navigatePrev/pocatecni otevreni)
        // zacina cerstvym jednosegmentovym seznamem - i pri zapnutem "Nekonecnem cteni" se dalsi
        // segmenty pridavaji az prubezne za cteni (viz appendNextWebtoonSegment), ne predem.
        appendingSegmentJob?.cancel()
        prependingSegmentJob?.cancel()
        // Oba flagy resetovat - zruseni jobu pred jeho startem na IO dispatcheru by
        // nechalo _webtoonAppendingNext zasekle na true ("Nacitam dalsi kapitolu" chip
        // na nove kapitole navzdy, audit #25).
        _webtoonAppendingNext.value = false
        _webtoonPrependingPrev.value = false
        _webtoonSegments.value = listOf(WebtoonSegment(chapter.id, chapter.name, _pages.value))
        _webtoonEpoch.value = chapter.id
    }

    /**
     * Detekce širších-než-vyšších stránek pro dvoustránkový spread ([_spreadPageIndices]) -
     * vytaženo z loadChapter, spouští se i při přepnutí "aktivní" kapitoly na jiný segment
     * v nekonečném čtení ([switchActiveSegment]), jinak by se do nového segmentu promitly
     * indexy z předchozí kapitoly. Mimo Main (BitmapFactory + u SAF ContentResolver blokuje
     * každou stránku), audit nález JIYU-UI-2.
     */
    private fun detectSpreadPages(pageUrls: List<String>) {
        spreadDetectJob?.cancel()
        spreadDetectJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val spread = pageUrls.mapIndexedNotNull { idx, url ->
                ensureActive()
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    if (url.startsWith("content://")) {
                        context.contentResolver.openInputStream(android.net.Uri.parse(url))?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, opts)
                        }
                    } else {
                        android.graphics.BitmapFactory.decodeFile(url.removePrefix("file://"), opts)
                    }
                } catch (e: Exception) {
                    e.report("reader:spreadDetect:decodeBounds")
                }
                if (opts.outWidth > 0 && opts.outWidth > opts.outHeight * 1.2f) idx else null
            }.toSet()
            _spreadPageIndices.value = spread
        }
    }

    /**
     * indexOfFirst kapitoly v [allChapters] s vystopováním: když tam není
     * (refresh/migrace/relink mezi getChapter a getAllChapters ji nahradil
     * jiným id), zanechá Crashlytics breadcrumb. Právě tahle situace způsobila
     * hlášený skok ch1 → ~200 a předtím proběhla potichu - po tomto je vidět,
     * kde a jak často se děje.
     */
    private fun chapterIndexOrTrace(chapterId: String, where: String): Int {
        val idx = allChapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) {
            ErrorReporter.breadcrumb(
                "reader:chapterMissing:$where",
                "chapterId=$chapterId mangaId=${currentChapter?.mangaId} listSize=${allChapters.size}",
            )
        }
        return idx
    }

    /**
     * Vloží kapitolu na její místo v seznamu seřazeném DESC podle
     * `chapterNumber` (stejné pořadí jako `ChapterDao`). Používá se, když se
     * otevřená kapitola v čerstvě načteném seznamu nenajde - migrace/relink
     * ji nahradil entitou s jiným id, ale pořadí zůstává podle čísla kapitoly.
     */
    private fun List<ChapterEntity>.insertSortedDesc(chapter: ChapterEntity): List<ChapterEntity> {
        val pos = indexOfFirst { it.chapterNumber < chapter.chapterNumber }
            .takeIf { it >= 0 } ?: size
        return toMutableList().apply { add(pos, chapter) }
    }

    /**
     * Index sousedni kapitoly o [direction] od [fromIdx] v [allChapters] (seznam je
     * DESC: -1 = dalsi/novejsi cislo, +1 = predchozi/starsi). Preskakuje vsechny
     * radky se STEJNYM cislem kapitoly - vicenasobne verze od ruznych scanlacnich
     * skupin jsou jedna logicka kapitola; bez dedupu "dalsi kapitola" prochazela
     * 3x stejne cislo (hlaseno: 4 kliknuti na postup o jednu kapitolu v
     * agregovanem ComicK rezimu).
     *
     * V bloku tehoz cisla preferuje radek se stejnou scanlacni skupinou jako
     * vychozi kapitola ("drz se vybraneho prekladatele"); jeho skupina cilove
     * cislo nema-li, vezme se prvni dostupna verze (fallback na jinou skupinu).
     *
     * [unreadOnly] (nastaveni "preskocit prectene") preskoci cela cisla, jejichz
     * NEJAKA verze uz je prectena - prectenost se hodnoti na urovni cisla, ne
     * radku, jinak by se neprectena duplikatni verze dala znovu otevrit.
     *
     * Cislo 0 znamena "zdroj cislo neudelil" - kazdy takovy radek je samostatna
     * kapitola a dedup se na nej nevztahuje (stejna konvence jako
     * preferEnglishChapters/chapterKey).
     *
     * @return index v [allChapters], nebo -1 kdyz zadny cil neexistuje.
     */
    private fun adjacentChapterIndex(fromIdx: Int, direction: Int, unreadOnly: Boolean): Int {
        if (fromIdx !in allChapters.indices) return -1
        val from = allChapters[fromIdx]
        val fromGroup = from.scanlationGroup?.trim()?.lowercase()?.ifBlank { null }
        fun sameGroup(i: Int) = fromGroup != null &&
            allChapters[i].scanlationGroup?.trim()?.lowercase() == fromGroup
        var i = fromIdx + direction
        // Preskocit radky tehoz cisla jako vychozi kapitola (jen ocislovane).
        if (from.chapterNumber != 0f) {
            while (i in allChapters.indices &&
                allChapters[i].chapterNumber == from.chapterNumber) i += direction
        }
        while (i in allChapters.indices) {
            val num = allChapters[i].chapterNumber
            // Konec bloku radku se stejnym nenulovym cislem.
            var end = i
            if (num != 0f) {
                while (end + direction in allChapters.indices &&
                    allChapters[end + direction].chapterNumber == num) end += direction
            }
            val block = minOf(i, end)..maxOf(i, end)
            val pick = block.firstOrNull(::sameGroup) ?: i
            if (!unreadOnly) return pick
            if (block.none { allChapters[it].read }) return pick
            i = end + direction
        }
        return -1
    }

    private fun updateNavState() {
        val chapter = currentChapter ?: return
        val idx = chapterIndexOrTrace(chapter.id, "updateNavState")
        // allChapters je DESC (nejnovější první)
        // prev = starší = vyšší index; next = novější = nižší index
        // idx == -1 = aktualni kapitola uz v seznamu neni (refresh/migrace mezi
        // getChapter a getAllChapters ji nahradil jinym id) - bez podminky by se
        // prev chapalo jako target=0 a skocilo na NEJNOVEJSI kapitolu.
        _hasPrevChapter.value = idx >= 0 && adjacentChapterIndex(idx, +1, unreadOnly = false) >= 0
        _hasNextChapter.value = idx >= 0 && adjacentChapterIndex(idx, -1, unreadOnly = false) >= 0
    }

    fun jumpToChapter(chapterId: String) {
        launchLoadChapter(chapterId)
    }

    fun navigateNext() {
        val chapter = currentChapter ?: return
        val idx = chapterIndexOrTrace(chapter.id, "navigateNext")
        if (idx < 0) return
        val target = adjacentChapterIndex(idx, -1, skipReadChapters.value)
            .takeIf { it >= 0 } ?: adjacentChapterIndex(idx, -1, unreadOnly = false)
        if (target < 0) return
        launchLoadChapter(allChapters[target].id)
    }

    fun navigatePrev() {
        val chapter = currentChapter ?: return
        val idx = chapterIndexOrTrace(chapter.id, "navigatePrev")
        // idx == -1 = aktualni kapitola se v allChapters nenachazi (mezitim
        // probehla migrace/relink entit) - bez guardu by target=0 hodil na
        // allChapters[0], tedy NEJNOVEJSI kapitolu (hlaseny skok ch1 -> ch ~200).
        if (idx < 0) return
        val target = adjacentChapterIndex(idx, +1, skipReadChapters.value)
            .takeIf { it >= 0 } ?: adjacentChapterIndex(idx, +1, unreadOnly = false)
        if (target < 0) return
        launchLoadChapter(allChapters[target].id)
    }

    // ── Čtení ────────────────────────────────────────────────────────────────

    private fun preloadNextChapter() {
        val chapter = currentChapter ?: return
        val idx = chapterIndexOrTrace(chapter.id, "preloadNextChapter")
        if (idx < 0) return
        val nextIdx = adjacentChapterIndex(idx, -1, unreadOnly = false)
        if (nextIdx < 0) return
        val nextChapter = allChapters[nextIdx]
        if (adjacentChapterCache.containsKey(nextChapter.id)) return
        if (preloadChapterId == nextChapter.id) return // in-flight - dalsi otoceni ho nesmi restartovat
        if (nextChapter.downloadStatus == DownloadStatus.DOWNLOADED) return
        preloadJob?.cancel()
        preloadChapterId = nextChapter.id
        preloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val manga = repository.getManga(nextChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(nextChapter.sourceId, nextChapter.url, manga.url)
                val urls = rawPages.map { pageDisplayUrl(it, nextChapter.sourceId) }.filter { it.isNotBlank() }
                if (urls.isNotEmpty()) {
                    adjacentChapterCache[nextChapter.id] = urls
                    // Samotny seznam URL nestaci - bez predstazenych obrazku zacinala kazda
                    // dalsi kapitola studenym stahovanim (live audit "cekani na dalsi page").
                    prefetchChapterStart(urls, nextChapter.sourceId)
                }
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterPages")
            } finally {
                // Znicit jen kdyz nikdo mezitim nezadal preloadovat jinou kapitolu - identity
                // guard: preloadChapterId muze ukazovat na NOVEJSI job a ten nesmime
                // odpojit (append by pak joinoval mrtvy job misto ziveho).
                if (preloadChapterId == nextChapter.id) preloadChapterId = null
            }
        }
    }

    /**
     * Zpětná symetrie [preloadNextChapter] - předstáhne PŘEDCHOZÍ kapitolu, když se
     * čtenář blíží začátku aktuální. Bez ní každý návrat o kapitolu zpět čekal na
     * síťový fetch seznamu stránek i studené obrázky ("seka se").
     * Předstahuje KONEC kapitoly - přeskočení o kapitolu zpět přistane na její
     * poslední přečtené stránce (lastPageRead dočtené kapitoly).
     */
    private fun preloadPrevChapter() {
        val chapter = currentChapter ?: return
        val idx = chapterIndexOrTrace(chapter.id, "preloadPrevChapter")
        if (idx < 0) return
        val prevIdx = adjacentChapterIndex(idx, +1, unreadOnly = false)
        if (prevIdx < 0) return
        val prevChapter = allChapters[prevIdx]
        if (adjacentChapterCache.containsKey(prevChapter.id)) return
        if (prevPreloadChapterId == prevChapter.id) return // in-flight
        if (prevChapter.downloadStatus == DownloadStatus.DOWNLOADED) return
        prevPreloadJob?.cancel()
        prevPreloadChapterId = prevChapter.id
        prevPreloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val manga = repository.getManga(prevChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(prevChapter.sourceId, prevChapter.url, manga.url)
                val urls = rawPages.map { pageDisplayUrl(it, prevChapter.sourceId) }.filter { it.isNotBlank() }
                if (urls.isNotEmpty()) {
                    adjacentChapterCache[prevChapter.id] = urls
                    prefetchChapterPages(urls.takeLast(NEXT_CHAPTER_PREFETCH_PAGES), prevChapter.sourceId)
                }
            } catch (e: Exception) {
                e.report("reader:preloadPrevChapterPages")
            } finally {
                if (prevPreloadChapterId == prevChapter.id) prevPreloadChapterId = null
            }
        }
    }

    /**
     * Má se předstahování omezit na rolling +1 stránku ([prefetchNextPage]) místo celokapitolového
     * prefetchu? Úsporný režim (baterie/málo RAM) NEBO zpoplatněná síť při zapnutém
     * "prefetch jen na WiFi" (výchozí) - předstahování celé kapitoly je ~5-15 MB dat, která
     * čtenář třeba ani nedočte.
     */
    private fun shouldLimitPrefetch(): Boolean =
        com.haise.jiyu.util.DeviceResourcePolicy.isSavingResources(context) ||
            (prefetchPagesWifiOnly.value && !networkMonitor.isUnmetered)

    /**
     * Spustí prefetch CELÉ kapitoly najednou - [PREFETCH_PARALLELISM] paralelních workerů
     * nad frontou v pořadí [prefetchOrder] (dopředu od aktuální pozice, zpětně nakonec).
     * Dřívější verze stahovala SEKVENČNĚ 0..N - jedna stránka po druhé, takže se kapitola
     * na pomalém CDN naplnila až po desítkách sekund a každé otočení před doběhnutím
     * prefetchu znamenalo čekání na síť (live audit proti Kotatsu).
     *
     * Stránku okolo aktuální pozice (currentPage ±1) přeskakuje, protože ji načítá
     * zobrazovací cesta pageru - prefetch by ji jinak stahoval duplicitně vedle rozjetého
     * zobrazovacího requestu. Kontrola se dělá DYNAMICKY uvnitř workeru (čte live
     * _currentPage), ne jednorázově při startu - když čtenář přeskočí dopředu, workery
     * si aktuální stránku nechají ujít a nevypálí na ni druhý download.
     *
     * Selhání jednotlivé stránky ji vrátí do fronty a po dokončení průchodu se nevyřízené
     * po [PREFETCH_RETRY_DELAY_MS] zkusi znovu - celkem max [PREFETCH_MAX_PASSES] průchodů.
     * Stažená data zůstávají v disk cache pod klíčem URL (disk drží RAW bajty), takže se
     * zobrazení v plné velikosti čte z disku bez sítě. Lokální soubory (offline stažené
     * kapitoly) se přeskočí - už jsou na disku.
     *
     * V úsporném režimu (šetření baterie / málo paměti) místo toho běží jen [prefetchNextPage]
     * na každé otočení stránky, aby se nestahovalo něco, co čtenář třeba neotevře.
     * Totéž platí na zpoplatněné síti při zapnutém "prefetch jen na WiFi" (viz [shouldLimitPrefetch]).
     */
    private fun startChapterPrefetch(centerIndex: Int = _currentPage.value) {
        chapterPrefetchJob?.cancel()
        chapterPrefetchJob = viewModelScope.launch {
            val pages = _pages.value.toList()
            if (pages.isEmpty()) return@launch
            val imageLoader = Coil.imageLoader(context)
            // Zbývající indexy jako SET, ne fixní fronta - každý pick bere stránku
            // NEJBLÍŽ DOPŘEDU od živé pozice čtenáře (_currentPage), takže skok
            // scrubberem/rychlý scroll okamžitě přeorientuje vlnu místo aby dojížděla
            // pořadím z okamžiku otevření kapitoly (dřív se po skoku stránky pod
            // novou pozicí čekalo na konec staré fronty = "kousek nenačtený").
            val remaining = ConcurrentHashMap.newKeySet<Int>()
            remaining += 0 until pages.size
            remaining -= prefetchedPageIndices
            Log.d(TAG, "prefetch start: kapitola=${pages.size} stranek, k_stazeni=${remaining.size}, center=$centerIndex")
            for (pass in 1..PREFETCH_MAX_PASSES) {
                if (remaining.isEmpty()) break
                val failed = java.util.concurrent.ConcurrentLinkedQueue<Int>()
                val sawRateLimit = java.util.concurrent.atomic.AtomicBoolean(false)
                val maxRetryAfterMs = java.util.concurrent.atomic.AtomicLong(0)
                kotlinx.coroutines.coroutineScope {
                    repeat(PREFETCH_PARALLELISM) {
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            while (true) {
                                ensureActive()
                                val center = _currentPage.value
                                // Stejné pořadí jako prefetchOrder (dopředu vzestupně, zpět
                                // od nejbližší), ale přepočítané při KAŽDÉM výběru z živé
                                // pozice - skok čtenáře okamžitě přeorientuje frontu.
                                val index = nextPrefetchIndex(remaining, center, pages.size)
                                    ?: break
                                if (!remaining.remove(index)) continue
                                if (index in prefetchedPageIndices) continue
                                val url = pages.getOrElse(index) { "" }
                                if (url.isBlank()) continue
                                // Stranka na kterou se zrovna kouka (a jeji soused v pageru) -
                                // zobrazovaci cesta ji drzi sama, duplicitni fetch je zbytecny.
                                if (kotlin.math.abs(index - _currentPage.value) <= 1) {
                                    prefetchedPageIndices += index
                                    continue
                                }
                                if (url.startsWith("/") || url.startsWith("file://")) {
                                    prefetchedPageIndices += index
                                    continue
                                }
                                prefetchedPageIndices += index
                                // buildPageImageRequest (ne rucne stavany request) - musi sedet
                                // se skutecnym zobrazovacim requestem, jinak si Coil spocita jiny
                                // cache klic a stranka se pri zobrazeni stahne znovu.
                                // size(PREFETCH_DECODE_SIZE): disk cache uklada RAW bajty pod
                                // klicem URL, do memory cache pribude jen mala bitmapa.
                                val request = buildPageImageRequest(context, url, _pageReferer.value, cropBorders.value)
                                    .newBuilder()
                                    .size(PREFETCH_DECODE_SIZE)
                                    .build()
                                val result = imageLoader.execute(request)
                                if (result is coil.request.ErrorResult) {
                                    prefetchedPageIndices -= index
                                    failed += index
                                    val cause = result.throwable
                                    if (cause is com.haise.jiyu.source.SourceRateLimitedException) {
                                        sawRateLimit.set(true)
                                        maxRetryAfterMs.accumulateAndGet(cause.retryAfterMs, ::maxOf)
                                        // Worker se na chvíli odmlčí - v rate-limit okně další
                                        // requesty jen prodlužují ban, zobrazovací cesta stránky
                                        // pod prstem má přednost.
                                        delay(pageAutoRetryDelayMs(cause))
                                    }
                                }
                            }
                        }
                    }
                }
                if (failed.isEmpty()) break
                remaining += failed
                Log.d(TAG, "prefetch pass $pass: ${failed.size} selhalo (429=${sawRateLimit.get()}), zbyva=${remaining.size}")
                // Po 429 další průchod rozespat aspoň na RATE_LIMIT_MIN_DELAY_MS
                // (Retry-After často chybí), jinak klasický krátký delay.
                delay(
                    if (sawRateLimit.get()) maxOf(maxRetryAfterMs.get(), RATE_LIMIT_MIN_DELAY_MS)
                    else PREFETCH_RETRY_DELAY_MS,
                )
            }
            if (remaining.isEmpty()) {
                Log.d(TAG, "prefetch hotovo: vsechny stranky v cache")
            } else {
                Log.w(TAG, "prefetch vzdal: ${remaining.size} stranek se nepovedlo ani po $PREFETCH_MAX_PASSES pruchodech")
            }
        }
    }

    /**
     * Předstáhne zadané stránky sousední kapitoly, jejíž seznam URL už známe (viz
     * preloadNextChapter / preloadPrevChapter / appendNextWebtoonSegment), ale její
     * obrázky ještě nejsou v disk cache. Bez toho přechod na sousední díl vždycky
     * studeně čekal na síť - stránky se stahovaly až po přepnutí.
     *
     * Bezpečné opakování: jede jen přes Coil requesty se STEJNÝM builderem jako display
     * cesta (disk cache klíč URL), takže stránky, co už stažené jsou, se jen potvrdí z disku.
     * V omezeném režimu (úsporný / zpoplatněná síť - viz [shouldLimitPrefetch]) se přeskočí
     * - další kapitola je bonus, ne nutnost.
     */
    private fun prefetchChapterPages(urls: List<String>, sourceId: String) {
        if (shouldLimitPrefetch()) return
        val imageLoader = Coil.imageLoader(context)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val referer = repository.sourceHomepage(sourceId)
            // Davky po 3 - dřívější `urls.forEach { launch }` pustil všechny stránky
            // najednou: na pomalém hostu sežraly celý dispatcher pool (10 slotů/host)
            // a ZOBRAZOVACÍ request čekal za nimi ve frontě až po 120s timeouty -
            // "stránka se načítá donekonečna". Display requesty mají dnes vlastní
            // dispatcher (PageDisplayImageLoader), ale davkovani stejne nechavame -
            // burst ~12 soubezných requestů na jeden host provokuje rate-limity.
            urls.chunked(3).forEach { batch ->
                ensureActive()
                kotlinx.coroutines.coroutineScope {
                    batch.forEach { url ->
                        if (url.isBlank() || url.startsWith("/") || url.startsWith("file://")) return@forEach
                        launch {
                            val request = buildPageImageRequest(context, url, referer, cropBorders.value)
                                .newBuilder()
                                .size(PREFETCH_DECODE_SIZE)
                                .build()
                            imageLoader.execute(request)
                        }
                    }
                }
            }
        }
    }

    private fun prefetchChapterStart(urls: List<String>, sourceId: String) =
        prefetchChapterPages(urls.take(NEXT_CHAPTER_PREFETCH_PAGES), sourceId)

    /**
     * Úsporný režim - místo sekvenčního prefetchu celé kapitoly (viz [startChapterPrefetch])
     * předstáhne jen krátké okno stránek dopředu při každém otočení stránky. Okno je
     * [LIMITED_PREFETCH_AHEAD] stránek, ne jedna - webtoon viewport drží několik stránek
     * najednou, takže +1 znamenalo nenačtený kousek hned po prvním scrollu.
     */
    private fun prefetchNextPage(currentIndex: Int) {
        val pages = _pages.value
        for (index in (currentIndex + 1)..(currentIndex + LIMITED_PREFETCH_AHEAD)) {
            if (index >= pages.size) break
            if (index in prefetchedPageIndices) continue
            val url = pages[index]
            if (url.isBlank() || url.startsWith("/") || url.startsWith("file://")) continue
            prefetchedPageIndices += index
            val request = buildPageImageRequest(context, url, _pageReferer.value, cropBorders.value)
                .newBuilder()
                .size(PREFETCH_DECODE_SIZE)
                .build()
            viewModelScope.launch {
                if (Coil.imageLoader(context).execute(request) is coil.request.ErrorResult) {
                    prefetchedPageIndices -= index
                }
            }
        }
    }

    /**
     * "Nekonečné čtení" (viz [infiniteScrollEnabled]) - přilepí DALŠÍ kapitolu (tu, co následuje
     * za POSLEDNÍM aktuálně přidaným segmentem, ne nutně za `currentChapter` - viz
     * [onWebtoonVisibleChapterChanged]) na konec [_webtoonSegments], takže čtečka
     * pokračuje plynule dál bez viditelného přepnutí. Volají ho [WebtoonReader]
     * (ke konci scrollu), paged čtečky přes [onPagedFlatPageChanged] a curl čtečka
     * přes `onNeedMorePages` - přestože nese "Webtoon" v názvu, je režimově neutrální.
     */
    fun appendNextWebtoonSegment() {
        if (!infiniteScrollEnabled.value) return
        if (appendingSegmentJob?.isActive == true) return
        val segments = _webtoonSegments.value
        val lastChapterId = segments.lastOrNull()?.chapterId ?: return
        val lastIdx = chapterIndexOrTrace(lastChapterId, "appendNextWebtoonSegment")
        // allChapters je DESC (nejnovější první) - dalsi/novejsi kapitola je NIZSI index.
        // Dedup podle cisla + lepivost na skupinu - viz adjacentChapterIndex.
        if (lastIdx < 0) return
        val nextIdx = adjacentChapterIndex(lastIdx, -1, unreadOnly = false)
        if (nextIdx < 0) return
        val nextChapter = allChapters[nextIdx]
        if (segments.any { it.chapterId == nextChapter.id }) return
        _webtoonAppendingNext.value = true
        appendingSegmentJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var pages: List<String>? = null
                // Az 3 pokusy - driv jeden neuspesny/timeoutnuty fetch znamenal tichy
                // dead-end: scroll stal na posledni strance a dalsi pokus se spustil
                // az nahodnym swiipem (live audit: ~28 s "zasekleho" scrollu na pomalem
                // zdroji). Bezpecne opakovatelne - jen nacita seznam stranek.
                for (attempt in 1..APPEND_FETCH_ATTEMPTS) {
                    // Kdyz bezi preloadNextChapter pro TUTO kapitolu, pockame na jeho
                    // vysledek misto druheho paralelniho fetchu - driv se stejny request
                    // poslal dvakrat a append cekal ~dvojnasob.
                    if (preloadChapterId == nextChapter.id) {
                        preloadJob?.join()
                    }
                    pages = adjacentChapterCache.remove(nextChapter.id)
                        ?: kotlinx.coroutines.withTimeoutOrNull(CHAPTER_LOAD_TIMEOUT_MS) {
                            fetchChapterPagesForSegment(nextChapter)
                        }
                    if (!pages.isNullOrEmpty()) break
                    if (attempt < APPEND_FETCH_ATTEMPTS) delay(APPEND_RETRY_DELAY_MS)
                }
                if (pages.isNullOrEmpty()) return@launch
                val newSegment = WebtoonSegment(nextChapter.id, nextChapter.name, pages)
                _webtoonSegments.value = _webtoonSegments.value + newSegment
                // LazyColumn komponuje jen ~1-2 stranky predem - rychly scroll by drive
                // dojel na nepreddstazene stranky a kazda dalsi cekala na sit.
                prefetchChapterStart(pages, nextChapter.sourceId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("reader:infiniteScroll:appendNextSegment")
            } finally {
                _webtoonAppendingNext.value = false
            }
        }
    }

    /**
     * Zpetna varianta [appendNextWebtoonSegment] - prilepi PREDCHOZI kapitolu (tu, co v
     * DESC seznamu lezi ZA prvni aktualne znamou kapitolou) na ZACATEK [_webtoonSegments],
     * takze "Nekonecne cteni" pokracuje plynule i pri cteni zpatky pres hranici kapitoly.
     * Volaji WebtoonReader (scroll k zacatku), paged ctecky pres [onPagedFlatPageChanged]
     * a curl ctecka pres `onNeedPrevPages` (tah pred prvni stranku).
     *
     * Posun indexu vlozeneho obsahu kompenzuji ctecky samy - LazyColumn drzi viditelnou
     * pozici pres klice polozek, paged ctecky si po prependu posunou plochy index o
     * velikost noveho segmentu (viz ReaderPager/MangaPageCurlReader).
     */
    fun prependPreviousWebtoonSegment() {
        if (!infiniteScrollEnabled.value) return
        if (prependingSegmentJob?.isActive == true) return
        val segments = _webtoonSegments.value
        val firstChapterId = segments.firstOrNull()?.chapterId ?: return
        val firstIdx = chapterIndexOrTrace(firstChapterId, "prependPreviousWebtoonSegment")
        // allChapters je DESC (nejnovější první) - predchozi/starsi kapitola je VYSSI index.
        // Dedup podle cisla + lepivost na skupinu - viz adjacentChapterIndex.
        if (firstIdx < 0) return
        val prevIdx = adjacentChapterIndex(firstIdx, +1, unreadOnly = false)
        if (prevIdx < 0) return
        val prevChapter = allChapters[prevIdx]
        if (segments.any { it.chapterId == prevChapter.id }) return
        _webtoonPrependingPrev.value = true
        prependingSegmentJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Kdyz uz preloadPrevChapter pro tuto kapitolu bezi, pripojit se na nej
                // (stejny pattern jako appendNextWebtoonSegment nad preloadJob) a vzit
                // hotovy seznam z adjacentChapterCache misto druheho paralelniho fetchu.
                if (prevPreloadChapterId == prevChapter.id) {
                    prevPreloadJob?.join()
                }
                var pages: List<String>? = adjacentChapterCache.remove(prevChapter.id)
                // Stejna retry politika jako appendNextWebtoonSegment - neuspesny fetch
                // nesmi zustat tichym dead-endem na zacatku proudu.
                if (pages == null) {
                    for (attempt in 1..APPEND_FETCH_ATTEMPTS) {
                        pages = kotlinx.coroutines.withTimeoutOrNull(CHAPTER_LOAD_TIMEOUT_MS) {
                            fetchChapterPagesForSegment(prevChapter)
                        }
                        if (!pages.isNullOrEmpty()) break
                        if (attempt < APPEND_FETCH_ATTEMPTS) delay(APPEND_RETRY_DELAY_MS)
                    }
                }
                if (pages.isNullOrEmpty()) return@launch
                val newSegment = WebtoonSegment(prevChapter.id, prevChapter.name, pages)
                _webtoonSegments.value = listOf(newSegment) + _webtoonSegments.value
                prefetchChapterStart(pages, prevChapter.sourceId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("reader:infiniteScroll:prependPrevSegment")
            } finally {
                _webtoonPrependingPrev.value = false
            }
        }
    }

    private fun pageDisplayUrl(page: Page, sourceId: String): String {
        page.imageUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val url = page.url.takeIf { it.isNotBlank() } ?: return ""
        // Stránka vložená cross-source gap fillem nese marker DONORA - znovu
        // nezabalovat, dvojitý fragment by se při decode rozbil.
        if (LazyPageUrl.isMarked(url)) return url
        return LazyPageUrl.encode(sourceId, page.index, url)
    }

    // ── Cross-source doplnění mrtvé stránky ("gap fill" za běhu) ──────────────
    //
    // Stránka, která je v seznamu, ale její obsah je mrtvý/poškozený (404 na CDN,
    // useknutý upload), projde detekcí PageGapDetector (ta chytá jen díry v
    // číslování URL) a dopadne na trvalou chybu RetryableAsyncImage. Čtečka ji po
    // vyčerpání retry nahlásí sem a my ji zkusíme doplnit z jiného zdroje: stejná
    // donor discovery jako PageGapFiller (CrossSourceSearch seeds + shoda čísla
    // kapitoly), donor page na stejném indexu se splícne do _pages/_webtoonSegments
    // a Coil ji načte se správným refererem donorova zdroje (LazyPageUrl marker).

    // donor lookup je drahý (cross-source sweep) - jednou na kapitolu, výsledek
    // (i negativní) se kešuje. Mutex serializuje souběžná selhání více stránek.
    private val donorMutex = kotlinx.coroutines.sync.Mutex()
    private val donorCache = HashMap<String, Pair<String, List<Page>>?>()

    /** Stránka definitivně selhala (retries vyčerpány) - zkusit ji doplnit z jiného zdroje. */
    fun onPagePermanentlyFailed(chapterId: String, pageIndex: Int) {
        val chapter = allChapters.firstOrNull { it.id == chapterId } ?: return
        if (chapter.sourceId == "comick") return
        viewModelScope.launch {
            val (donorSourceId, donorPages) = donorFor(chapter) ?: return@launch
            val donorPage = donorPages.getOrNull(pageIndex) ?: return@launch
            spliceDonorPage(chapterId, pageIndex, donorPage, donorSourceId)
        }
    }

    private suspend fun donorFor(chapter: ChapterEntity): Pair<String, List<Page>>? =
        donorMutex.withLock {
            if (donorCache.containsKey(chapter.id)) return donorCache[chapter.id]
            val manga = repository.getManga(chapter.mangaId)
            val source = sourceManager.getById(chapter.sourceId)
            val result = if (manga != null && source != null) {
                pageGapFiller.donorPagesFor(source, chapter.url, manga.url)
                    ?.let { (src, pages) -> src.id to pages }
            } else null
            donorCache[chapter.id] = result
            result
        }

    /** Přepíše URL stránky všude, kde žije - _pages (klasický/aktivní) i segment webtoon/infinite režimu.
     *  Donor URL se VŽDY zabalí do LazyPageUrl markeru donorova zdroje - fetcher je pak
     *  resolvuje a stahuje se SPRÁVNÝM refererem donorova zdroje (host referer by na
     *  hotlink CDN skončil 403). Výjimka: URL nesoucí fragment (scrambled) - dvojitý
     *  marker by fragment rozbil, tak se vezme raw (scrambled zdroje referer typicky
     *  nepotřebují). */
    private fun spliceDonorPage(chapterId: String, pageIndex: Int, donorPage: Page, donorSourceId: String) {
        val raw = donorPage.imageUrl?.takeIf { it.isNotBlank() } ?: donorPage.url
        if (raw.isBlank()) return
        // Fragment (scrambled i jiny marker) nese payload, co by dvojity '#' rozbil -
        // takovou URL spliznout raw. '#' zachyti oboji bez android.net.Uri (unit testy).
        val replacement =
            if (raw.contains('#')) raw
            else LazyPageUrl.encode(donorSourceId, donorPage.index, raw)
        if (com.haise.jiyu.BuildConfig.DEBUG) Log.i(TAG,
            "donor splice: $chapterId page $pageIndex -> $donorSourceId")
        if (_currentChapterId.value == chapterId && pageIndex in _pages.value.indices) {
            _pages.value = _pages.value.toMutableList().also { it[pageIndex] = replacement }
        }
        _webtoonSegments.value = _webtoonSegments.value.map { seg ->
            if (seg.chapterId != chapterId || pageIndex !in seg.pages.indices) seg
            else seg.copy(pages = seg.pages.toMutableList().also { it[pageIndex] = replacement })
        }
    }

    /** Sdílená logika stažení stránek jedné kapitoly (offline i online) - viz stejné větvení v loadChapter. */
    private suspend fun fetchChapterPagesForSegment(chapter: ChapterEntity): List<String>? {
        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
            return ChapterStorage.listPageUrls(context, chapter.localPath).takeIf { it.isNotEmpty() }
        }
        val manga = repository.getManga(chapter.mangaId) ?: return null
        val rawPages = repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
        return rawPages.map { pageDisplayUrl(it, chapter.sourceId) }.filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Přepne "aktivní" kapitolu ([currentChapter] + `_pages` + titulka/nav/prefetch)
     * na segment [chapterId], pokud už jiná není aktivní - sdílené jádro pro oba
     * nekonečné režimy (webtoon scroll i plochý paged pager).
     * [prefetchCenter] = lokální index stránky v segmentu, od kterého se restartuje
     * prefetch. @return true = kapitola se opravdu přepnula.
     */
    private fun switchActiveSegment(chapterId: String, prefetchCenter: Int): Boolean {
        if (chapterId == _currentChapterId.value) return false
        val chapter = allChapters.firstOrNull { it.id == chapterId } ?: return false
        val segment = _webtoonSegments.value.firstOrNull { it.chapterId == chapterId } ?: return false
        // Jiné joby patří PŘEDCHOZÍ kapitole a ruší se: detekce dvoustran by jinak
        // přepsala _spreadPageIndices nové a načtené komentáře by zůstaly viset -
        // jejich guard `isNotEmpty()` v loadChapterComments by pak u nové kapitoly
        // odmítl načíst její.
        commentsJob?.cancel()
        commentsJob = null
        _chapterComments.value = emptyList()
        _commentsLoading.value = false
        spreadDetectJob?.cancel()
        spreadDetectJob = null
        currentChapter = chapter
        _currentChapterId.value = chapter.id
        // Stejny zapis jako v loadChapter - nekonecne rezimy meni aktivni kapitolu
        // pres segmenty, ne pres loadChapter; bez tohohle by process-death obnova
        // ve webtoonu skocila zpet na kapitolu, se kterou se ctecka otevirala.
        savedStateHandle["chapterId"] = chapter.id
        _chapterTitle.value = chapter.name
        _chapterIndex.value = chapterIndexOrTrace(chapter.id, "switchActiveSegment").coerceAtLeast(0)
        updateNavState()
        // Fallback zdroj muze mit jinou domovku - referer MUSI prijit pred _pages,
        // jinak prvni image requesty nove kapitoly leti se starym a CDN vrati 403.
        _pageReferer.value = repository.sourceHomepage(chapter.sourceId)
        _pages.value = segment.pages
        // suspend funkce - tahle metoda se volá z UI synchronně, takže přes launch.
        viewModelScope.launch {
            _commentsSupported.value = repository.sourceSupportsChapterComments(chapter.sourceId)
            // Nova kapitola v nekonecnem scrollu = novy seznam stranek - bez restartu
            // by sekvencni prefetch dal stahoval stranky PREDCHOZI kapitoly a tyhle
            // by se nikdy nepredstahly (audit). Reset fronty + restart pro tenhle
            // segment; centerIndex = prefetchCenter (pozice v novem segmentu).
            prefetchedPageIndices.clear()
            chapterPrefetchJob?.cancel()
            chapterPrefetchJob = null
            if (shouldLimitPrefetch()) prefetchNextPage(prefetchCenter)
            else startChapterPrefetch(prefetchCenter)
        }
        _isOfflineChapter.value = chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null
        // Indexy dvoustran patri KAPITOLE, pro kterou detekce bezela - bez vymazani by se
        // v paged nekonecnem rezimu promitly na segment jine kapitoly (skupiny [i,i+1]
        // by parovaly cizi stranky). U stazene kapitoly se pro ni detekce rovnou
        // rozběhne znovu (online stranky detekci nikdy nemely - je to jen lokalni
        // BitmapFactory nad soubory).
        _spreadPageIndices.value = emptySet()
        if (_isOfflineChapter.value) detectSpreadPages(segment.pages)
        // Stejna podminka jako v loadChapter - bez clearu by hlaska z fallback kapitoly
        // previsela i na normalnich segmentech (audit).
        _fallbackNotice.value = if (chapter.isFallbackSource) {
            context.getString(R.string.reader_fallback_source_notice)
        } else null
        return true
    }

    /**
     * Volá [WebtoonReader], jakmile se v souvislém "Nekonečném čtení" scrollu viditelná pozice
     * posune do JINÉ kapitoly, než je aktuálně sledovaná ([currentChapter]) - `localIndex`/
     * `localOffset` jsou pozice PŘEPOČÍTANÉ na tenhle konkrétní segment (ne globální index přes
     * všechny segmenty). Přepne "aktivní" kapitolu (název v horní liště, ukládání postupu,
     * spouštěč přednačítání) přes [switchActiveSegment] a znovu použije existující
     * [onPageChanged]/[saveWebtoonScrollOffset] - ty už samy o sobě ukládají postup a spouští
     * [preloadNextChapter] správně, jen potřebují mít [currentChapter]/`_pages` nastavené na
     * TUHLE kapitolu.
     *
     * Vědomě NEMAŽE starší segmenty z [_webtoonSegments] (i když se čtenář o pár kapitol
     * dostane dál) - LazyColumn je virtualizovaný (dávno odscrollované položky se nedrží
     * složené) a přehled URL adres je zanedbatelně malý, takže by mazání jen riskovalo bug
     * (viz git historie - dřívější verze mazala první segment a tím měnila jeho identitu,
     * což omylem znovu spustilo obnovu pozice ve WebtoonReaderu a způsobilo skok scrollu).
     * Totéž platí pro paged pager - `HorizontalPager` virtualizuje taky (beyondViewportPageCount).
     */
    fun onWebtoonVisibleChapterChanged(chapterId: String, localIndex: Int, localOffset: Int) {
        val chapterSwitched = switchActiveSegment(chapterId, localIndex)
        // onPageChanged JEN kdyz se skutecne zmenil index stranky (nebo kapitola) - snapshotFlow
        // ve WebtoonReaderu emituje na kazdy PIXEL scrollu a driv se tu na kazdy emitovalo
        // PageProgressEvent do UNLIMITED channelu: ~4 DB zapisy (updateReadProgress,
        // propagate, updateLastReadChapter, history) na pixel = DB psala nonstop
        // behem celeho scrollovani (audit).
        if (chapterSwitched || localIndex != _currentPage.value) onPageChanged(localIndex)
        saveWebtoonScrollOffset(localOffset)
    }

    /**
     * "Nekonečné čtení" ve STRÁNKOVANÝCH režimech (pager i curl) - `MangaReader`/
     * `MangaPageCurlReader` dostanou seskládanou listu stránek přes všechny napojené
     * segmenty a hlásí PLOCHÝ index (přes všechny kapitoly). Tady se převede na
     * (segment, lokální index), přepne aktivní kapitolu stejně jako ve webtoonu a
     * u konce posledního segmentu se rozběhne [appendNextWebtoonSegment].
     */
    fun onPagedFlatPageChanged(flatIndex: Int) {
        val segments = _webtoonSegments.value
        var offset = 0
        for ((i, segment) in segments.withIndex()) {
            val local = flatIndex - offset
            if (local !in 0 until segment.pages.size) {
                offset += segment.pages.size
                continue
            }
            val switched = switchActiveSegment(segment.chapterId, local)
            if (switched || local != _currentPage.value) onPageChanged(local)
            // Docteni posledniho znameho segmentu -> dolnatahni dalsi kapitolu.
            // Bezne no-op pocity (vypnute nastaveni / neni dalsi / append uz leti)
            // resi appendNextWebtoonSegment sam. Prah ~4 stranky pred koncem - fetch
            // na pomalem zdroji trva desitky sekund.
            if (i == segments.lastIndex && local >= segment.pages.size - PAGED_APPEND_PREFETCH_DISTANCE) {
                appendNextWebtoonSegment()
            }
            // Symetricky zpet: blizko ZACATKU prvniho segmentu se dolnatahne
            // PREDCHOZI kapitola - po prependu swipe/tah zpet pokracuje plynule
            // do ni misto narazeni na hrany proudu.
            if (i == 0 && local <= PAGED_PREPEND_PREFETCH_DISTANCE) {
                prependPreviousWebtoonSegment()
            }
            return
        }
    }

    /**
     * Progress z light-novel ctecky (audit RD-6): NovelContent hlasi index odstavce,
     * pixel offset polozky a celkovy pocet odstavcu. Jde pres stejny sekveneni kanal
     * jako strankovani mange (pageProgressEvents -> processPageProgress), takze
     * novela se konecne oznaci prectenou na poslednim odstavci, dostane historii,
     * tracker sync i auto-delete registraci. `index` = lastPageRead, `itemOffset`
     * = lastScrollOffset -> oboje se pri otevreni kapitoly obnovi jako pozice.
     */
    fun onNovelProgressChanged(index: Int, itemOffset: Int, totalItems: Int) {
        // Stejny guard jako ve webtoonu - snapshotFlow hlasi i offset (pixel) zmeny,
        // takze event se posila jen pri zmene indexu odstavce.
        if (index != _currentPage.value) {
            _currentPage.value = index
            val now = System.currentTimeMillis()
            val deltaMs = if (lastPageChangeMs > 0) minOf(now - lastPageChangeMs, 3 * 60_000L) else 0L
            lastPageChangeMs = now
            val chapter = currentChapter ?: return
            pageProgressEvents.trySend(
                PageProgressEvent(
                    index = index,
                    pageCount = totalItems,
                    chapter = chapter,
                    manga = currentManga,
                    incognito = _incognitoMode.value,
                    now = now,
                    deltaMs = deltaMs,
                ),
            )
        }
        // updateScrollOffset je debounce-ovany (600 ms) uz uvnitr - viz
        // saveWebtoonScrollOffset; nazev je historicky, funkce je obecna
        // (chapterId + offset), pro novelu pouzita taky.
        saveWebtoonScrollOffset(itemOffset)
    }

    fun onPageChanged(index: Int) {
        _currentPage.value = index
        // Normalni rezim: sekvencni prefetch kapitoly bezi sam od loadChapter, otočení
        // stranky netreba reagovat. V omezenem rezimu (usporny / zpoplatnena sit - viz
        // shouldLimitPrefetch) se misto toho predstahuje +1.
        if (shouldLimitPrefetch()) prefetchNextPage(index)

        val total = _pages.value.size
        if (total > 0 && index >= total - 3 && _hasNextChapter.value) preloadNextChapter()
        // Zpetna symetrie - blizko zacatku predstahnout predchozi kapitolu, aby navrat
        // netrhal na sitovy fetch (viz preloadPrevChapter).
        if (total > 0 && index <= 2 && _hasPrevChapter.value) preloadPrevChapter()

        // Čas od poslední stránky — max 3 min (filtrace idle)
        val now = System.currentTimeMillis()
        val deltaMs = if (lastPageChangeMs > 0) minOf(now - lastPageChangeMs, 3 * 60_000L) else 0L
        lastPageChangeMs = now

        val chapter = currentChapter ?: return
        // Udalosti se zpracovavaji POSTUPNE jednim konzumentem (viz init) - drive kazde otoceni
        // stranky spustilo vlastni coroutine a jejich poradi nebylo zaruceno, takze starsi index
        // mohl dobehnout posledni a vratit postup zpet (audit nalez JIYU-UI-3).
        pageProgressEvents.trySend(
            PageProgressEvent(
                index = index,
                pageCount = _pages.value.size,
                chapter = chapter,
                manga = currentManga,
                incognito = _incognitoMode.value,
                now = now,
                deltaMs = deltaMs,
            ),
        )
    }

    private class PageProgressEvent(
        val index: Int,
        val pageCount: Int,
        val chapter: ChapterEntity,
        val manga: MangaEntity?,
        val incognito: Boolean,
        val now: Long,
        val deltaMs: Long,
    )

    private suspend fun processPageProgress(event: PageProgressEvent) {
        val chapter = event.chapter
        val chapterId = chapter.id
        val manga = event.manga
        val incognito = event.incognito
        val reachedEnd = event.index >= event.pageCount - 1

        // Inkognito nezapisuje NIC. Dřív vynechávalo jen historii a trackery, ale postup
        // čtení, "naposledy čteno" a čas čtení se ukládaly dál - kapitola se tedy
        // po anonymním přečtení tvářila jako přečtená a čas naskočil do Statistik.
        // Název "Číst anonymně" tím sliboval víc, než dělal.
        var firstTimeRead = false
        // Comick katalogove rodice objeveni propagaci - pushneme je az pri dokonceni
        // kapitoly (firstTimeRead), pod inkognitem se nezapisuje nic, takze prazdne.
        var comickTargets: List<com.haise.jiyu.data.repository.ComickProgressTarget> = emptyList()
        if (!incognito) {
            firstTimeRead = reachedEnd && markedReadChapterIds.add(chapterId)
            // `read` se v ramci relace nikdy nesnizuje: dřív krok zpět z posledni stranky zapsal
            // read = false a dočtená kapitola se tvářila jako nepřečtená.
            val read = reachedEnd || chapterId in markedReadChapterIds || chapter.read
            repository.updateReadProgress(chapterId, read = read, lastPageRead = event.index, lastReadAt = event.now, pageCount = event.pageCount)
            // ComicK metadatova kapitola, ktera na tuhle (realnou resolved) kapitolu
            // presmerovava, dostane stejny postup - jinak by se po docteni resolved
            // kapitoly "precteno" na ComicK titulu nikdy neprojevilo (a naopak driv
            // se oznacilo prectenou uz pri vyberu zdroje, tj. hned po otevreni).
            comickTargets = repository.propagateReadProgressToLinkedChapters(chapterId, read = read, lastPageRead = event.index, lastReadAt = event.now, pageCount = event.pageCount)
            repository.updateLastReadChapter(chapter.mangaId, chapterId)
            if (event.deltaMs > 0) {
                settings.addReadingTime(event.deltaMs)
                repository.addMangaReadingTime(chapter.mangaId, event.deltaMs)
            }
            if (manga != null) {
                historyRepository.record(
                    ReadHistoryEntity(
                        chapterId = chapterId,
                        mangaId = chapter.mangaId,
                        mangaTitle = manga.title,
                        coverUrl = manga.coverUrl ?: _comickParent.value?.coverUrl,
                        chapterName = chapter.name,
                        readAt = System.currentTimeMillis(),
                    )
                )
            }
        }

        // Jen poprve za relaci (a nikdy pod inkognitem: kapitola se neoznacila prectenou, takze by
        // automaticke mazani sahalo na stazene soubory kvuli necemu, co se "nestalo"). Dřív se pri
        // kazdem navratu na posledni stranku znovu volaly vsechny ctyri trackery.
        if (firstTimeRead) {
            registerAutoDelete(chapter)
            if (manga != null) {
                viewModelScope.launch { trackerSyncCoordinator.syncReadProgress(manga, chapter) }
            }
            // Resolved kapitoly patri realnemu zdroji bez comickHid - remote progress
            // na Comick katalog pushuje tato vetev (jinak by "two-way" Comick sync
            // progress nikdy neposlal).
            if (comickTargets.isNotEmpty()) {
                viewModelScope.launch { trackerSyncCoordinator.syncComickParentProgress(comickTargets) }
            }
        }
    }

    // ── Přepínač zdrojů (agregovaný ComicK režim) ─────────────────────────
    //
    // Uzivatelsky pozadavek: v horni liste ctecky tlacitko, co otevre seznam vsech
    // zdroju, kde se kapitoly titulu nasly, a rucne prepne na jiny. Kandidati se
    // ctou ze source_resolution cache (zapisuje ji resolver po sweepu) - prepinac
    // tak vetsinou netreba sit vubec; pri starem/chybejicim zaznamu se sweep
    // pusti na pozadi a radky se doplni prubezne (viz rescanSourceCandidates).

    /** Jeden radek v prepinaci zdroju - viz [openSourceSwitcher]. */
    data class SourceSwitchRow(
        val sourceId: String,
        val sourceName: String,
        val mangaUrl: String,
        val mangaTitle: String,
        val coverUrl: String?,
        val minChapter: Float?,
        val maxChapter: Float?,
        val chapterCount: Int,
        /** Aktualne cteny zdroj - radka je oznacena a kliknuti se ignoruje. */
        val isCurrent: Boolean,
        /** Aktualni cislo kapitoly lezi v zaznamenanem rozsahu zdroje (odhad - diry uvnitr rozsahu se z cache nepoznaji). */
        val hasCurrentChapter: Boolean,
    )

    /** Otevreni sheetu prepinace: cache do radku hned, pri starem/chybejicim zaznamu sweep na pozadi. */
    fun openSourceSwitcher() {
        val parent = _comickParent.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val currentNumber = currentChapter?.chapterNumber
            val resolution = repository.getSourceResolution(parent.id)
            val cached = com.haise.jiyu.data.repository.SourceResolutionCodec
                .decode(resolution?.candidatesJson ?: "[]")
            _sourceSwitchRows.value = cached.map { it.toRow(currentNumber) }
            // Sweep jen kdyz: zadny zaznam, cache vyprsela (RESOLUTION_FRESH_MS),
            // zadni zapamatovani kandidati, NEBO ComicK pribyl o kapitolu oproti
            // dobe resolvovani (comicKLastChapter) - nove kapitoly = nove sance, ze
            // se titul objevil i na jinych zdrojich. Jiny pripad = radky z cache
            // staci a sit se necha v klidu (uzivatelsky pozadavek "ne od znova").
            val comicKMax = repository.getAllChapters(parent.id)
                .englishOnlyChapters()
                .maxOfOrNull { it.chapterNumber }
            val comicKGrew = resolution != null &&
                comicKMax != null && comicKMax > resolution.comicKLastChapter + 0.01f
            // "" = resoluce vznikla fast-pathem a kandidatsky sweep jeste nikdy
            // nedobehl -> jednou doskenovat. EMPTY_RESULT ("none") = sweep probehl
            // a nic nenasel -> vysledek drzet. "[]"/jine prazdne = starsi
            // dvojznacny zaznam z doby pred sentinelem -> jednou doskenovat a
            // prepsat bud skutecnymi kandidaty, nebo EMPTY_RESULT (uzivatelsky
            // pozadavek "ne od znova" - po doskenovani se uz sweep neopakuje).
            val json = resolution?.candidatesJson.orEmpty()
            val needsScan = resolution != null &&
                (json.isBlank() ||
                    (cached.isEmpty() && json != com.haise.jiyu.data.repository.SourceResolutionCodec.EMPTY_RESULT))
            val stale = resolution == null ||
                System.currentTimeMillis() - resolution.resolvedAt >= RESOLUTION_FRESH_MS
            if (stale || needsScan || comicKGrew) rescanSourceCandidates(parent)
        }
    }

    /** Full sweep zdroju na pozadi - radky se do _sourceSwitchRows pridavaji prubezne. */
    private fun rescanSourceCandidates(parent: com.haise.jiyu.data.db.entity.MangaEntity) {
        if (rescanJob?.isActive == true) return
        rescanJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _sourceScanning.value = true
            try {
                val currentNumber = currentChapter?.chapterNumber
                val allComicKChapters = repository.getAllChapters(parent.id).englishOnlyChapters()
                val signals = deriveGroupSignals(
                    allComicKChapters,
                    ::normalizeGroupToken,
                    ::isGenericGroupToken,
                )
                val found = mutableListOf<com.haise.jiyu.source.comick.ResolvedCandidate>()
                comicKResolver.findCandidatesFlow(
                    comicKMangaId = parent.id,
                    comicKMangaUrl = parent.url,
                    comicKTitle = parent.title,
                    comicKContentType = parent.contentType,
                    requestedChapterNumber = currentNumber,
                    priorityGroupTokens = signals.preferredTokens,
                ).collect { candidate ->
                    found += candidate
                    // Prubezne radit podle stejneho klice jako resolver screen -
                    // sheet se vyplnuje zive, jak zdroje odpovidaji.
                    val total = allComicKChapters.map { kotlin.math.floor(it.chapterNumber).toInt() }.distinct().size
                    val range = allComicKChapters.map { it.chapterNumber }
                        .let { if (it.isEmpty()) null else ChapterRange(it.min(), it.max()) }
                    _sourceSwitchRows.value = rankCandidates(
                        found.toList(), total,
                        isPreferredGroup = { c -> signals.preferredTokens.any { t -> sourceMatchesToken(sourceNameOf(c.source.id), t) } },
                        comicKRange = range,
                    ).map { it.toRow(currentNumber) }
                }
                // Vysledky sweepu zapsat do source_resolution - dalsi otevreni
                // sheetu i otevirani kapitol zase jedou z cache bez site.
                // Zapisuje se VZDY - i prazdny vysledek ("[]") a marker bez
                // resolved zdroje. Bez toho by titul bez nalezenych zdroju pri
                // kazdem otevreni sheetu spoustel cely sweep dokola, protoze
                // "sken doběhl, nic nenasel" by slo poznat jen ze zaznamu.
                val existing = repository.getSourceResolution(parent.id)
                repository.upsertSourceResolution(
                    com.haise.jiyu.data.db.entity.SourceResolutionEntity(
                        comickMangaId = parent.id,
                        resolvedMangaId = existing?.resolvedMangaId
                            ?: found.firstOrNull()
                                ?.let { repository.mangaId(it.source.id, it.manga.url) }
                                ?: "",
                        candidatesJson = if (found.isEmpty()) {
                            com.haise.jiyu.data.repository.SourceResolutionCodec.EMPTY_RESULT
                        } else {
                            com.haise.jiyu.data.repository.SourceResolutionCodec.encode(
                                found.map { with(com.haise.jiyu.data.repository.SourceResolutionCodec) { it.toCached() } }
                            )
                        },
                        comicKLastChapter = allComicKChapters.maxOfOrNull { it.chapterNumber } ?: 0f,
                        resolvedLastChapter = found.firstOrNull()?.maxChapterNumber
                            ?: existing?.resolvedLastChapter ?: 0f,
                        resolvedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("reader:sourceRescan")
            } finally {
                _sourceScanning.value = false
            }
        }
    }

    private fun sourceNameOf(sourceId: String): String =
        sourceManager.getByIdSync(sourceId)?.name ?: sourceId

    /** Stejna fuzzy shoda jako SourceResolverViewModel.sourceNameMatchesPreferredGroup - top-level verze. */
    private fun sourceMatchesToken(sourceName: String, token: String): Boolean {
        val normalized = normalizeGroupToken(sourceName)
        return normalized.isNotEmpty() && (normalized.contains(token) || token.contains(normalized))
    }

    private fun com.haise.jiyu.data.repository.CachedSourceCandidate.toRow(currentNumber: Float?) =
        SourceSwitchRow(
            sourceId = sourceId,
            sourceName = sourceNameOf(sourceId),
            mangaUrl = mangaUrl,
            mangaTitle = mangaTitle,
            coverUrl = coverUrl,
            minChapter = minChapter,
            maxChapter = maxChapter,
            chapterCount = chapterCount,
            isCurrent = repository.mangaId(sourceId, mangaUrl) == currentChapter?.mangaId,
            hasCurrentChapter = currentNumber == null ||
                (minChapter != null && maxChapter != null &&
                    currentNumber >= minChapter - 0.01f && currentNumber <= maxChapter + 0.01f),
        )

    private fun com.haise.jiyu.source.comick.ResolvedCandidate.toRow(currentNumber: Float?) =
        SourceSwitchRow(
            sourceId = source.id,
            sourceName = source.name,
            mangaUrl = manga.url,
            mangaTitle = manga.title,
            coverUrl = manga.coverUrl,
            minChapter = minChapterNumber,
            maxChapter = maxChapterNumber,
            chapterCount = matchedChapterCount,
            isCurrent = repository.mangaId(source.id, manga.url) == currentChapter?.mangaId,
            hasCurrentChapter = currentNumber == null || hasRequestedChapter,
        )

    /** Rucni prepnuti na jiny zdroj - najde stejne cislo kapitoly a nacte ji. */
    fun switchSource(row: SourceSwitchRow) {
        val parent = _comickParent.value ?: return
        val targetNumber = currentChapter?.chapterNumber ?: return
        if (row.isCurrent || _sourceSwitching.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _sourceSwitching.value = true
            try {
                val smanga = com.haise.jiyu.source.SManga(
                    sourceId = row.sourceId,
                    url = row.mangaUrl,
                    title = row.mangaTitle,
                    coverUrl = row.coverUrl,
                )
                val mangaId = repository.openPreview(smanga)
                val chapters = repository.getAllChapters(mangaId).englishOnlyChapters()
                // PRESNA shoda jako resolver (abs < 0.01), zadny minByOrNull -
                // "nejblizsi" by skocila na jinou kapitolu a link/setFallback pod
                // tim by ji navic natrvalo nalinkoval na ComicK radek cile
                // (stejny bug jako "kap.62 -> kap.61" v resolveru).
                val best = chapters.firstOrNull { kotlin.math.abs(it.chapterNumber - targetNumber) < 0.01f }
                if (best == null) {
                    _fallbackNotice.value = context.getString(R.string.resolver_chapter_missing_after_select)
                    return@launch
                }
                // Pamet volby: resolvedMangaId na ComicK entite + resolution zaznam
                // (dalsi otevreni kapitol i prepinace vidi novy zdroj hned).
                repository.setResolvedManga(parent.id, mangaId)
                val existing = repository.getSourceResolution(parent.id)
                if (existing != null) {
                    repository.upsertSourceResolution(
                        existing.copy(
                            resolvedMangaId = mangaId,
                            resolvedLastChapter = chapters.maxOfOrNull { it.chapterNumber }
                                ?: existing.resolvedLastChapter,
                            resolvedAt = System.currentTimeMillis(),
                        )
                    )
                } else {
                    // Aktualni radky sheetu persistovat jako kandidaty - jsou to
                    // realne vysledky sweepu, takze dalsi otevreni sheetu nemusi
                    // hledat znovu ("[]" by se totiz cetlo jako dvojznacny zaznam
                    // a spustilo zbytecny rescan).
                    repository.upsertSourceResolution(
                        com.haise.jiyu.data.db.entity.SourceResolutionEntity(
                            comickMangaId = parent.id,
                            resolvedMangaId = mangaId,
                            candidatesJson = com.haise.jiyu.data.repository.SourceResolutionCodec.encode(
                                _sourceSwitchRows.value.map {
                                    com.haise.jiyu.data.repository.CachedSourceCandidate(
                                        sourceId = it.sourceId,
                                        mangaUrl = it.mangaUrl,
                                        mangaTitle = it.mangaTitle,
                                        coverUrl = it.coverUrl,
                                        minChapter = it.minChapter,
                                        maxChapter = it.maxChapter,
                                        chapterCount = it.chapterCount,
                                    )
                                }
                            ),
                            // Skutecne max ComicK titulu, ne 0f - s nulou by
                            // openSourceSwitcher vzdy vycislil "ComicK pribyla kapitola"
                            // a freshness gate pro rucne prepnuty titul netiskla
                            // (rescan pri kazdem otevreni sheetu, audit #4).
                            comicKLastChapter = repository.getAllChapters(parent.id)
                                .filter { it.isEnglishChapter() }
                                .maxOfOrNull { it.chapterNumber } ?: 0f,
                            resolvedLastChapter = chapters.maxOfOrNull { it.chapterNumber } ?: 0f,
                            resolvedAt = System.currentTimeMillis(),
                        )
                    )
                }
                // Nalinkovat ComicK kapitolu se stejnym cislem na novy cil (fallback
                // link + "Pokracovat" ukotveni, stejne jako resolver selectCandidate).
                repository.getAllChapters(parent.id)
                    .firstOrNull { kotlin.math.abs(it.chapterNumber - targetNumber) < 0.01f && it.isEnglishChapter() }
                    ?.let {
                        repository.setChapterFallbackTarget(it.id, best.id)
                        repository.updateLastReadChapter(parent.id, it.id)
                    }
                _sourceSwitchRows.value = _sourceSwitchRows.value.map {
                    it.copy(isCurrent = it.sourceId == row.sourceId && it.mangaUrl == row.mangaUrl)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    jumpToChapter(best.id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("reader:switchSource")
                _fallbackNotice.value = e.toFriendlyMessage()
            } finally {
                _sourceSwitching.value = false
            }
        }
    }

    fun toggleShowOriginal() {
        _showOriginal.value = !_showOriginal.value
    }

    // ── Feature C: Smart offline deletion ───────────────────────────────────

    private suspend fun registerAutoDelete(chapter: ChapterEntity) {
        if (!settings.autoDeleteRead.first()) return
        val delayDays = settings.autoDeleteDelayDays.first()
        if (delayDays > 0) {
            // Plánuj přes WorkManager — viewModelScope se zruší při opuštění čtečky
            AutoDeleteWorker.schedule(context, chapter.id, delayDays.toLong())
        } else {
            chaptersPendingAutoDelete += chapter.id
        }
    }

    /** Okamžité smazání čekajících kapitol přes WorkManager (přežije zrušení viewModelScope); worker znovu ověří `read && DOWNLOADED`. */
    private fun flushPendingAutoDelete() {
        if (chaptersPendingAutoDelete.isEmpty()) return
        chaptersPendingAutoDelete.forEach { AutoDeleteWorker.schedule(context, it, 0L) }
        chaptersPendingAutoDelete.clear()
    }

    override fun onCleared() {
        // Čtečka skončila (ne rotace - ta ViewModel nečistí): odpočet už nemá koho ukončit.
        sleepTimerManager.cancel()
        flushPendingAutoDelete()
        // Flush debounce-ovaneho scroll offsetu - viewModelScope uz je zruseny, proto
        // primo (mimo viewModelScope). Bez toho se posledni ~600 ms scroll pozice
        // ztratila pri zavreni ctecky/killu (audit #27).
        pendingScrollPersist?.let { (chapterId, offset) ->
            scrollPersistJob?.cancel()
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                runCatching { repository.updateScrollOffset(chapterId, offset, System.currentTimeMillis()) }
            }
            pendingScrollPersist = null
        }
        pageProgressEvents.close()
        super.onCleared()
    }
}

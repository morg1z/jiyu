package com.haise.jiyu.ui.reader

import com.haise.jiyu.translate.GlossaryRepository
import com.haise.jiyu.data.tracking.TrackerSyncCoordinator
import com.haise.jiyu.data.repository.HistoryRepository
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import coil.Coil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.GlossaryEntity
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
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.translate.normalizeOriginal
import com.haise.jiyu.translate.TranslatedBlock
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toErrorAction

data class TranslationProgress(val done: Int, val total: Int)

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

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: MangaRepository,
    private val translateRepository: TranslateRepository,
    private val settings: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val trackerSyncCoordinator: TrackerSyncCoordinator,
    private val glossaryRepository: GlossaryRepository,
    private val sleepTimerManager: SleepTimerManager,
    private val networkMonitor: com.haise.jiyu.util.NetworkMonitor,
    private val errorActionHandler: com.haise.jiyu.source.ErrorActionHandler,
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])

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
        lateinit var job: Job
        job = viewModelScope.launch {
            _commentsLoading.value = true
            try {
                _chapterComments.value = repository.getChapterComments(chapter.sourceId, chapter.url)
            } catch (e: Exception) {
                e.report("reader:loadChapterComments")
            } finally {
                if (commentsJob === job) _commentsLoading.value = false
            }
        }
        commentsJob = job
    }

    // Jednorazova hlaska "tahle kapitola byla dotazena z jineho zdroje" - viz
    // SourceResolverViewModel.resolveCompleteChapter a ChapterEntity.isFallbackSource.
    private val _fallbackNotice = MutableStateFlow<String?>(null)
    val fallbackNotice: StateFlow<String?> = _fallbackNotice.asStateFlow()
    fun clearFallbackNotice() { _fallbackNotice.value = null }

    /** true pokud je nastavený Supabase/Groq/Gemini klíč - jinak překlad jede jen přes on-device ML Kit. */
    val isApiKeyConfigured = translateRepository.isApiKeyConfigured

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

    val tapZoneLeftFraction: StateFlow<Float> = settings.tapZoneLeftFraction
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.3f)

    val tapZoneRightFraction: StateFlow<Float> = settings.tapZoneRightFraction
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.3f)

    val tapZoneGrid: StateFlow<TapZoneGrid> = settings.tapZoneGrid
        .map { TapZoneGrid.deserialize(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TapZoneGrid())

    fun setTapZoneGrid(grid: TapZoneGrid) {
        viewModelScope.launch { settings.setTapZoneGrid(grid.serialize()) }
    }

    val webtoonScrollSpeed: StateFlow<Float> = settings.webtoonScrollSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val readerTextScale: StateFlow<Float> = settings.readerTextScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

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

    fun setReaderOrientation(orientation: String) { viewModelScope.launch { settings.setReaderOrientation(orientation) } }

    // ── Přednačítání další kapitoly ──────────────────────────────────────────
    // ConcurrentHashMap - pise se z IO (preloadNextChapter, appendNextWebtoonSegment) a cte/maze z Main
    // (loadChapter); obycejna mapa dovolovala ConcurrentModificationException (audit nalez JIYU-UI-5).
    private val nextChapterCache = ConcurrentHashMap<String, List<String>>()
    private var preloadJob: Job? = null
    private var spreadDetectJob: Job? = null

    // Jediné probíhající načtení kapitoly - rychlé přepínání (další/předchozí, skok) by jinak
    // nechalo běžet víc loadChapter najednou, které si mezi suspend body přepisují stejné StateFlow.
    private var loadJob: Job? = null

    private fun launchLoadChapter(id: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadChapter(id) }
    }
    private var novelPreloadJob: Job? = null
    private var mangaTranslatePreloadJob: Job? = null

    /** Indexy stránek AKTUÁLNÍ kapitoly, pro které už proběhl [prefetchPagesFrom] - viz reset v [loadChapter]. */
    private val prefetchedPageIndices = mutableSetOf<Int>()

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
    private var appendingSegmentJob: Job? = null

    // Scroll ve webtoon rezimu emituje pozici na kazdy pixel behem flingu - zapis do DB
    // na kazdou zmenu by appku zbytecne zatezoval. Misto toho se pri kazde zmene zrusi
    // predchozi cekajici zapis a naplanuje novy o 600 ms pozdeji, takze se skutecne
    // zapise az kdyz se scrollovani na chvili zastavi (presne tam, kde uzivatel realne
    // skoncil cteni), ne prubezne behem pohybu.
    private var scrollPersistJob: Job? = null

    fun saveWebtoonScrollOffset(offset: Int) {
        val chapterId = currentChapter?.id ?: return
        if (_incognitoMode.value) return
        scrollPersistJob?.cancel()
        scrollPersistJob = viewModelScope.launch {
            delay(600L)
            repository.updateScrollOffset(chapterId, offset, System.currentTimeMillis())
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

    // ── Překlad ──────────────────────────────────────────────────────────────
    private val _translateMode = MutableStateFlow(false)
    val translateMode: StateFlow<Boolean> = _translateMode.asStateFlow()

    private val _translationProgress = MutableStateFlow<TranslationProgress?>(null)
    val translationProgress: StateFlow<TranslationProgress?> = _translationProgress.asStateFlow()

    private val _translatedPages = MutableStateFlow<Map<Int, List<TranslatedBlock>>>(emptyMap())
    val translatedPages: StateFlow<Map<Int, List<TranslatedBlock>>> = _translatedPages.asStateFlow()

    /**
     * PERZISTENTNÍ obdoba [_translatedPages] klíčovaná i podle kapitoly - jen pro
     * [WebtoonReader]. `_translatedPages` je plochá mapa jen podle indexu stránky V RÁMCI
     * AKTUÁLNÍ kapitoly - v běžném (stránkovaném) čtení je vždy jen jedna "aktuální"
     * kapitola, takže to stačí. Ve "Nekonečném čtení" ale LazyColumn drží (a prefetchuje)
     * stránky VÍCE kapitol současně, a lokální index se v každé kapitole čísluje znovu od 0 -
     * sdílená plochá mapa tak mohla ukázat bubliny JEDNÉ kapitoly na stránkách JINÉ (nahlášeno
     * v auditu, "cizí bubliny na cizích stránkách"). Tahle mapa se navíc (na rozdíl od
     * [_translatedPages]) NIKDY neresetuje při přechodu mezi segmenty - jako vedlejší efekt to
     * řeší i dřívější nález "scroll přes hranici kapitoly zahodí už hotový překlad".
     */
    private val _translatedPagesByChapter = MutableStateFlow<Map<String, Map<Int, List<TranslatedBlock>>>>(emptyMap())
    val translatedPagesByChapter: StateFlow<Map<String, Map<Int, List<TranslatedBlock>>>> = _translatedPagesByChapter.asStateFlow()

    /** Zapíše do OBOU map najednou - viz komentář u [_translatedPagesByChapter]. */
    private fun putTranslatedPage(chapterId: String, pageIndex: Int, blocks: List<TranslatedBlock>) {
        _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
        val forChapter = (_translatedPagesByChapter.value[chapterId] ?: emptyMap()) + (pageIndex to blocks)
        _translatedPagesByChapter.value = _translatedPagesByChapter.value + (chapterId to forChapter)
    }

    // Stejná výchozí hodnota jako v SettingsRepository - než se nastavení načte, nesmí tu
    // chvíli platit jiný jazyk, než jaký uživatel uvidí ve čtečce.
    private val _sourceLanguage = MutableStateFlow("Auto")
    val sourceLanguage: StateFlow<String> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow("Czech")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    private val _translationError = MutableStateFlow<String?>(null)
    val translationError: StateFlow<String?> = _translationError.asStateFlow()

    private val _batchTranslating = MutableStateFlow(false)
    val batchTranslating: StateFlow<Boolean> = _batchTranslating.asStateFlow()

    // ── Překlad light novel (prostý text) ────────────────────────────────────
    private val _novelTranslateMode = MutableStateFlow(false)
    val novelTranslateMode: StateFlow<Boolean> = _novelTranslateMode.asStateFlow()

    private val _novelTranslatedText = MutableStateFlow<String?>(null)
    val novelTranslatedText: StateFlow<String?> = _novelTranslatedText.asStateFlow()

    private val _novelTranslating = MutableStateFlow(false)
    val novelTranslating: StateFlow<Boolean> = _novelTranslating.asStateFlow()

    private var novelTranslationJob: Job? = null

    // ── Slovník AI překladu (rychlý přístup z čtečky) ────────────────────────
    private val _currentMangaId = MutableStateFlow<String?>(null)
    val mangaId: StateFlow<String?> = _currentMangaId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val glossary: StateFlow<List<GlossaryEntity>> = kotlinx.coroutines.flow.combine(
        _currentMangaId,
        _targetLanguage,
    ) { mangaId, lang -> mangaId to lang }
        .flatMapLatest { (mangaId, lang) ->
            if (mangaId == null) flowOf(emptyList())
            else glossaryRepository.observeForManga(mangaId).map { list -> list.filter { it.targetLanguage == lang } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addGlossaryEntry(sourceTerm: String, targetTerm: String, protectExact: Boolean = false) {
        val source = sourceTerm.trim()
        val target = targetTerm.trim()
        val mangaId = currentManga?.id ?: currentChapter?.mangaId ?: return
        if (source.isBlank() || target.isBlank()) return
        val lang = _targetLanguage.value
        viewModelScope.launch { glossaryRepository.addManual(mangaId, source, target, lang, protectExact) }
    }

    /** Přepne [GlossaryEntity.protectExact] na existujícím záznamu - viz [GlossaryBottomSheet]. */
    fun toggleGlossaryProtectExact(entry: GlossaryEntity) {
        viewModelScope.launch { glossaryRepository.setProtectExact(entry, !entry.protectExact) }
    }

    fun removeGlossaryEntry(entry: GlossaryEntity) = viewModelScope.launch { glossaryRepository.delete(entry) }

    fun toggleNovelTranslate() {
        if (_novelTranslateMode.value) {
            _novelTranslateMode.value = false
            novelTranslationJob?.cancel()
            _novelTranslating.value = false
            return
        }
        if (!translateRepository.isApiKeyConfigured) {
            _translationError.value = context.getString(R.string.reader_error_missing_supabase_url)
            return
        }
        _novelTranslateMode.value = true
        val chapterId = currentChapter?.id ?: return
        val text = _novelText.value
        if (text.isBlank()) return

        novelTranslationJob = viewModelScope.launch {
            val cached = translateRepository.getCachedNovel(chapterId, _targetLanguage.value, _sourceLanguage.value)
            if (cached != null) {
                _novelTranslatedText.value = cached
                preloadNextNovelChapter()
                return@launch
            }
            _novelTranslating.value = true
            try {
                val result = translateRepository.translateNovelChapter(
                    chapterId = chapterId,
                    mangaId = currentManga?.id ?: currentChapter?.mangaId ?: return@launch,
                    text = text,
                    targetLanguage = _targetLanguage.value,
                    sourceLanguage = _sourceLanguage.value,
                )
                if (result != null) {
                    _novelTranslatedText.value = result
                    preloadNextNovelChapter()
                } else {
                    _translationError.value = if (!translateRepository.isApiKeyConfigured &&
                        !translateRepository.onDeviceSupportsLanguage(_targetLanguage.value)
                    ) {
                        context.getString(R.string.reader_error_language_unsupported_offline)
                    } else {
                        context.getString(R.string.reader_error_translation_failed)
                    }
                    _novelTranslateMode.value = false
                }
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
                _novelTranslateMode.value = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _translationError.value = context.getString(R.string.reader_error_translation_failed)
                _novelTranslateMode.value = false
            } finally {
                _novelTranslating.value = false
            }
        }
    }

    /**
     * Zahodí cache a přeloží CELOU kapitolu novely znovu (viz [TranslateRepository.translateNovelChapter]'s
     * `forceRefresh`) - stejný důvod jako [retranslatePage] u manga stránek, jen novely tuhle
     * možnost dřív vůbec neměly (jediná cesta ven z jednou vadného překladu byla smazat data
     * appky). Spouští se jen v [novelTranslateMode] - přeložit znovu něco, co se ještě
     * nepřekládá, nedává smysl.
     */
    fun retranslateNovelChapter() {
        if (!_novelTranslateMode.value) return
        val chapterId = currentChapter?.id ?: return
        val mangaId = currentManga?.id ?: currentChapter?.mangaId ?: return
        val text = _novelText.value
        if (text.isBlank()) return
        novelTranslationJob?.cancel()
        novelTranslationJob = viewModelScope.launch {
            _novelTranslating.value = true
            try {
                val result = translateRepository.translateNovelChapter(
                    chapterId = chapterId,
                    mangaId = mangaId,
                    text = text,
                    targetLanguage = _targetLanguage.value,
                    sourceLanguage = _sourceLanguage.value,
                    forceRefresh = true,
                )
                if (result != null) {
                    _novelTranslatedText.value = result
                } else {
                    _translationError.value = context.getString(R.string.reader_error_translation_failed)
                }
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _translationError.value = context.getString(R.string.reader_error_translation_failed)
            } finally {
                _novelTranslating.value = false
            }
        }
    }

    /**
     * Ruční oprava JEDNOHO přeloženého odstavce novely - stejný důvod jako [saveBubbleEdit] u
     * manga bublin, jen novely tuhle možnost dřív vůbec neměly (jediná cesta ven ze špatného
     * odstavce byla přeložit celou kapitolu znovu, viz [retranslateNovelChapter]). Identita
     * (uložený originalText) se dohledá pozičně z NEPŘELOŽENÉHO textu kapitoly - UI zná jen
     * index zobrazeného (přeloženého) odstavce, ne jeho originál.
     */
    fun saveNovelParagraphEdit(paragraphIndex: Int, newText: String) {
        val chapterId = currentChapter?.id ?: return
        val originalParagraphs = _novelText.value.split("\n").filter { it.isNotBlank() }
        val originalText = originalParagraphs.getOrNull(paragraphIndex) ?: return
        viewModelScope.launch {
            translateRepository.saveNovelParagraphEdit(chapterId, originalText, newText)
            val trimmed = newText.trim()
            if (trimmed.isBlank()) return@launch
            val current = _novelTranslatedText.value ?: return@launch
            val translatedParagraphs = current.split("\n").toMutableList()
            if (paragraphIndex !in translatedParagraphs.indices) return@launch
            translatedParagraphs[paragraphIndex] = trimmed
            _novelTranslatedText.value = translatedParagraphs.joinToString("\n")
        }
    }

    // ── Sleep timer (#42) ────────────────────────────────────────────────────
    val sleepTimerRemaining: StateFlow<Int?> = sleepTimerManager.remainingSeconds

    // ── Panel mode (#38) ─────────────────────────────────────────────────────
    private val _panelMode = MutableStateFlow(false)
    val panelMode: StateFlow<Boolean> = _panelMode.asStateFlow()

    private val _panelRects = MutableStateFlow<List<android.graphics.Rect>>(emptyList())
    val panelRects: StateFlow<List<android.graphics.Rect>> = _panelRects.asStateFlow()

    private val _currentPanel = MutableStateFlow(0)
    val currentPanel: StateFlow<Int> = _currentPanel.asStateFlow()

    private val _batchProgress = MutableStateFlow<TranslationProgress?>(null)
    val batchProgress: StateFlow<TranslationProgress?> = _batchProgress.asStateFlow()

    private val _showOriginal = MutableStateFlow(false)
    val showOriginal: StateFlow<Boolean> = _showOriginal.asStateFlow()

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
        if (_controlsVisible.value && !advancedSheetOpen) {
            controlsHideJob = viewModelScope.launch {
                delay(5_000L)
                _controlsVisible.value = false
            }
        }
    }

    private var advancedSheetOpen = false

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

    // ── Tap-to-flip (bublina <-> originál) ───────────────────────────────────
    // Klíč "$pageIndex:$bubbleIndex" je stabilní jen v rámci JEDNÉ kapitoly (viz reset
    // v loadChapter výše) - bubbleIndex je pozice bubliny v cachovaném/deserializovaném
    // seznamu TranslatedBlock pro danou stránku, což je deterministické, dokud se stránka
    // znovu nepřeloží (jiný počet/pořadí bublin by pak ukazovalo špatnou bublinu jako
    // "otočenou" - přijatelné riziko, protože retranslate stejné stránky je vzácný).
    private val _flippedBubbles = MutableStateFlow<Set<String>>(emptySet())
    val flippedBubbles: StateFlow<Set<String>> = _flippedBubbles.asStateFlow()

    fun toggleBubbleFlip(pageIndex: Int, bubbleIndex: Int) {
        val key = "$pageIndex:$bubbleIndex"
        _flippedBubbles.value = _flippedBubbles.value.let { current ->
            if (key in current) current - key else current + key
        }
    }

    /**
     * Uloží ruční opravu textu jedné bubliny a rovnou ji promitne do zobrazených bloků.
     *
     * Do stavu se zápis nepromitá znovu-načtením z databáze - stránka je už v paměti a opětovný
     * dotaz by jen zablikal. Mění se přesně ten jeden blok, podle stejné identity, jakou používá
     * napařování po přepočtu (původní text, viz [manualEditId]).
     *
     * Prázdný text opravu zruší, ale strojový překlad se vrátí až po znovunačtení stránky -
     * původní strojový text už v paměti není a tahat ho z cache kvůli tomu zvlášť nestojí za to.
     */
    fun saveBubbleEdit(pageIndex: Int, originalText: String, text: String, offsetXDp: Float? = null, offsetYDp: Float? = null) {
        val chapterId = currentChapter?.id ?: return
        viewModelScope.launch {
            translateRepository.saveManualEdit(chapterId, pageIndex, originalText, text, offsetXDp, offsetYDp)
            val blocks = _translatedPages.value[pageIndex] ?: return@launch
            val trimmed = text.trim()
            if (trimmed.isBlank()) return@launch
            val updated = blocks.map { block ->
                if (normalizeOriginal(block.originalText) == normalizeOriginal(originalText)) {
                    block.copy(
                        translatedText = trimmed,
                        displayText = trimmed,
                        isUntranslated = false,
                        offsetXDp = offsetXDp ?: block.offsetXDp,
                        offsetYDp = offsetYDp ?: block.offsetYDp,
                    )
                } else {
                    block
                }
            }
            putTranslatedPage(chapterId, pageIndex, updated)
        }
    }

    /**
     * Zahodí cache a přeloží CELOU stránku znovu (viz [TranslateRepository.translatePage]'s
     * `forceRefresh`) - spouští se z [BubbleEditDialog] tlačítkem "Přeložit stránku znovu",
     * pro případ, kdy jde o víc bublin naráz (zacyklení, špatný jazyk...), ne jen jednu, kterou
     * by šlo opravit ručně přes [saveBubbleEdit]. Ruční opravy na téhle stránce se aplikují
     * zpátky automaticky (viz [TranslateRepository.translatePage]'s `withManualEdits` na konci) -
     * podle originálního OCR textu bubliny, takže PŘEŽIJÍ jen pokud OCR znovu rozpozná stejný
     * text; pokud se OCR výstup mezitím liší, oprava zůstane uložená, ale na tenhle nový
     * strojový překlad se nenapaří (identita se neshoduje) - stejné omezení, jaké
     * [manualEditId] má odjakživa.
     */
    fun retranslatePage(pageIndex: Int) {
        val chapterId = currentChapter?.id ?: return
        val mangaId = currentManga?.id ?: currentChapter?.mangaId ?: return
        val pageUrl = _pages.value.getOrNull(pageIndex) ?: return
        viewModelScope.launch {
            val blocks = translateRepository.translatePage(
                pageUrl = pageUrl,
                chapterId = chapterId,
                mangaId = mangaId,
                pageIndex = pageIndex,
                targetLanguage = _targetLanguage.value,
                sourceLanguage = _sourceLanguage.value,
                forceRefresh = true,
            )
            if (blocks.isNotEmpty()) putTranslatedPage(chapterId, pageIndex, blocks)
        }
    }

    fun clearTranslationError() { _translationError.value = null }

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

        // Single getPixels() call instead of w/4 * h individual JNI calls
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val horizontalCuts = mutableListOf(0)
        for (y in 0 until h) {
            var darkCount = 0
            var x = 0
            while (x < w) {
                val pixel = pixels[y * w + x]
                val brightness = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                if (brightness < threshold) darkCount++
                x += 4
            }
            if (darkCount > w / 8 && (horizontalCuts.last() == 0 || y - horizontalCuts.last() > minPanelHeight)) {
                horizontalCuts.add(y)
            }
        }
        horizontalCuts.add(h)

        return (0 until horizontalCuts.lastIndex).map { i ->
            android.graphics.Rect(0, horizontalCuts[i], w, horizontalCuts[i + 1])
        }.filter { it.height() > minPanelHeight }
    }

    private var translationJob: Job? = null
    private var batchJob: Job? = null
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
        viewModelScope.launch {
            _sourceLanguage.value = settings.sourceLanguage.first()
            _targetLanguage.value = settings.targetLanguage.first()
        }
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
        _translatedPages.value = emptyMap()
        _chapterComments.value = emptyList()
        _commentsSupported.value = false
        commentsJob?.cancel()
        commentsJob = null
        // Klíč je "$pageIndex:$bubbleIndex" bez chapterId - stránkování se v každé kapitole
        // čísluje znovu od 0, takže bez resetu by "otočená" bublina 3:2 z minulé kapitoly
        // zůstala otočená i na stránce 3 v nové kapitole, i když jde o úplně jinou bublinu.
        _flippedBubbles.value = emptySet()
        _translateMode.value = false
        translationJob?.cancel()
        translationJob = null
        _translationProgress.value = null
        // Bez tohohle by rozjeté "Přeložit vše" z PŘEDCHOZÍ kapitoly dál běželo ve stejném
        // viewModelScope a jeho onPageReady zapisoval bloky staré kapitoly do
        // _translatedPages, které si teď čte UI nové kapitoly - špatný překlad na špatné
        // stránce. _batchTranslating navíc zůstávalo true a blokovalo nové "Přeložit vše"
        // na nové kapitole, dokud starý job nedoběhl sám.
        cancelBatchTranslation()
        _novelTranslateMode.value = false
        _novelTranslatedText.value = null
        novelTranslationJob?.cancel()
        novelTranslationJob = null
        _novelTranslating.value = false

        val chapter = repository.getChapter(id) ?: run { _loading.value = false; return }
        currentChapter = chapter
        _commentsSupported.value = repository.sourceSupportsChapterComments(chapter.sourceId)
        if (chapter.isFallbackSource) {
            _fallbackNotice.value = context.getString(R.string.reader_fallback_source_notice)
        }
        _chapterTitle.value = chapter.name
        _currentChapterId.value = chapter.id
        // Presna pozice (stranka + scroll) se pamatuje jen POSITION_FRESHNESS_MS od posledniho
        // cteni - starsi otevreme rovnou od zacatku kapitoly, viz [POSITION_FRESHNESS_MS].
        val positionIsFresh = System.currentTimeMillis() - chapter.lastReadAt <= POSITION_FRESHNESS_MS
        _initialPage.value = if (positionIsFresh) chapter.lastPageRead.coerceAtLeast(0) else 0
        _currentPage.value = _initialPage.value
        _webtoonScrollOffset.value = if (positionIsFresh) chapter.lastScrollOffset else 0

        allChapters = repository.getAllChapters(chapter.mangaId)
        _allChaptersFlow.value = allChapters
        _chapterCount.value = allChapters.size
        _chapterIndex.value = allChapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
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
            _pages.value = emptyList()
            _isOfflineChapter.value = false
            _isNovelSource.value = false
            _spreadPageIndices.value = emptySet()
            _comickUnavailable.value = true
        } else if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
            _comickUnavailable.value = false
            val pageUrls = withContext(kotlinx.coroutines.Dispatchers.IO) { ChapterStorage.listPageUrls(context, chapter.localPath) }
            _pages.value = pageUrls
            _isOfflineChapter.value = true
            // Detect landscape pages for smart spread grouping - mimo Main (BitmapFactory + u SAF
            // ContentResolver blokuje kazdou stranku), audit nalez JIYU-UI-2.
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
        } else {
            _comickUnavailable.value = false
            _isOfflineChapter.value = false
            _spreadPageIndices.value = emptySet()
            val cached = nextChapterCache.remove(chapter.id)
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
                    _pageReferer.value = repository.sourceHomepage(chapter.sourceId)
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
                            _pages.value = rawPages.map { it.imageUrl ?: it.url }
                        }
                    }
                }
            }
        }
        prefetchPagesFrom(_initialPage.value)
        lastPageChangeMs = System.currentTimeMillis()
        _loading.value = false
        // Kazde plne nacteni kapitoly (jumpToChapter/navigateNext/navigatePrev/pocatecni otevreni)
        // zacina cerstvym jednosegmentovym seznamem - i pri zapnutem "Nekonecnem cteni" se dalsi
        // segmenty pridavaji az prubezne za cteni (viz appendNextWebtoonSegment), ne predem.
        appendingSegmentJob?.cancel()
        _webtoonSegments.value = listOf(WebtoonSegment(chapter.id, chapter.name, _pages.value))
    }

    private fun updateNavState() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        // allChapters je DESC (nejnovější první)
        // prev = starší = vyšší index; next = novější = nižší index
        _hasPrevChapter.value = idx < allChapters.lastIndex
        _hasNextChapter.value = idx > 0
    }

    fun jumpToChapter(chapterId: String) {
        launchLoadChapter(chapterId)
    }

    fun navigateNext() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return
        val target = if (skipReadChapters.value) {
            (idx - 1 downTo 0).firstOrNull { !allChapters[it].read } ?: (idx - 1)
        } else {
            idx - 1
        }
        launchLoadChapter(allChapters[target].id)
    }

    fun navigatePrev() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx >= allChapters.lastIndex) return
        val target = if (skipReadChapters.value) {
            (idx + 1..allChapters.lastIndex).firstOrNull { !allChapters[it].read } ?: (idx + 1)
        } else {
            idx + 1
        }
        launchLoadChapter(allChapters[target].id)
    }

    // ── Čtení ────────────────────────────────────────────────────────────────

    private fun preloadNextChapter() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return
        val nextChapter = allChapters[idx - 1]
        if (nextChapterCache.containsKey(nextChapter.id)) return
        if (nextChapter.downloadStatus == DownloadStatus.DOWNLOADED) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val manga = repository.getManga(nextChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(nextChapter.sourceId, nextChapter.url, manga.url)
                val urls = rawPages.mapNotNull { it.imageUrl?.takeIf { u -> u.isNotBlank() } ?: it.url.takeIf { u -> u.isNotBlank() } }
                if (urls.isNotEmpty()) nextChapterCache[nextChapter.id] = urls
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterPages")
            }
        }
    }

    /**
     * Předstáhne obrázky nadcházejících stránek AKTUÁLNÍ kapitoly (viz [computePrefetchIndices])
     * do Coil cache, dřív než na ně dojde řada v čtečce - řeší "kapitola se dlouho dokresluje
     * po stránkách". Volá se po dokončení [loadChapter] (od initialPage) a při každé změně
     * stránky (viz [onPageChanged], od `index + 1`). Fire-and-forget - selhání jednotlivého
     * požadavku se tiše zahodí, skutečné zobrazení stránky pak proběhne normální cestou přes
     * [RetryableAsyncImage] s vlastním retry UI.
     */
    private fun prefetchPagesFrom(fromIndex: Int) {
        val pages = _pages.value
        // Sirsi okno na zpoplatnenem/mobilnim pripojeni - na pomale/vysoke-latenci lince
        // ctenar frontu 4 predstazenych stranek pri normalnim tempu cteni dojede a pak
        // ceka stranku po strance; na WiFi 4 staci s rezervou.
        val count = prefetchWindowFor(networkMonitor.isUnmetered, com.haise.jiyu.util.DeviceResourcePolicy.isSavingResources(context))
        val indices = computePrefetchIndices(fromIndex, pages.size, prefetchedPageIndices, count)
        if (indices.isEmpty()) return
        val referer = _pageReferer.value
        val imageLoader = Coil.imageLoader(context)
        for (index in indices) {
            val url = pages[index]
            if (url.isBlank()) continue
            prefetchedPageIndices += index
            // buildPageImageRequest (ne rucne stavany request) - musi sedet se skutecnym
            // zobrazovacim requestem (viz ReaderImage.buildPageImageRequest), jinak si Coil
            // spocita jiny cache klic (transformace jsou jeho soucasti) a predstazeni je
            // k nicemu: stranka se pri zobrazeni stahne/dekoduje znovu (nahlaseno v auditu).
            val request = buildPageImageRequest(context, url, referer, cropBorders.value)
            prefetchDisposables += imageLoader.enqueue(request)
        }
        // Dokončené požadavky nedržíme (jinak by seznam za dlouhé čtení rostl).
        prefetchDisposables.removeAll { it.isDisposed }
    }

    /**
     * "Nekonečné čtení" (viz [infiniteScrollEnabled]) - přilepí DALŠÍ kapitolu (tu, co následuje
     * za POSLEDNÍM aktuálně přidaným segmentem, ne nutně za `currentChapter` - viz
     * [onWebtoonVisibleChapterChanged]) na konec [_webtoonSegments], takže [WebtoonReader]
     * scrolluje plynule dál bez viditelného přepnutí. Zavolá [WebtoonReader] sám, jakmile
     * uživatel dočte skoro na konec posledního segmentu.
     */
    fun appendNextWebtoonSegment() {
        if (!infiniteScrollEnabled.value) return
        if (appendingSegmentJob?.isActive == true) return
        val segments = _webtoonSegments.value
        val lastChapterId = segments.lastOrNull()?.chapterId ?: return
        val lastIdx = allChapters.indexOfFirst { it.id == lastChapterId }
        // allChapters je DESC (nejnovější první) - dalsi/novejsi kapitola je NIZSI index.
        if (lastIdx <= 0) return
        val nextChapter = allChapters[lastIdx - 1]
        if (segments.any { it.chapterId == nextChapter.id }) return
        appendingSegmentJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Strop stejny jako u hlavniho nacitani kapitoly (CHAPTER_LOAD_TIMEOUT_MS) -
                // bez nej by zaseknuty pokus drzel appendingSegmentJob "aktivni" donekonecna a
                // znemoznil dalsi pokus (viz podminka na zacatku funkce).
                val pages = nextChapterCache.remove(nextChapter.id)
                    ?: kotlinx.coroutines.withTimeoutOrNull(CHAPTER_LOAD_TIMEOUT_MS) { fetchChapterPagesForSegment(nextChapter) }
                if (pages.isNullOrEmpty()) return@launch
                val newSegment = WebtoonSegment(nextChapter.id, nextChapter.name, pages)
                _webtoonSegments.value = _webtoonSegments.value + newSegment
            } catch (e: Exception) {
                e.report("reader:infiniteScroll:appendNextSegment")
            }
        }
    }

    /** Sdílená logika stažení stránek jedné kapitoly (offline i online) - viz stejné větvení v loadChapter. */
    private suspend fun fetchChapterPagesForSegment(chapter: ChapterEntity): List<String>? {
        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
            return ChapterStorage.listPageUrls(context, chapter.localPath).takeIf { it.isNotEmpty() }
        }
        val manga = repository.getManga(chapter.mangaId) ?: return null
        val rawPages = repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
        return rawPages.mapNotNull { it.imageUrl?.takeIf { u -> u.isNotBlank() } ?: it.url.takeIf { u -> u.isNotBlank() } }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Volá [WebtoonReader], jakmile se v souvislém "Nekonečném čtení" scrollu viditelná pozice
     * posune do JINÉ kapitoly, než je aktuálně sledovaná ([currentChapter]) - `localIndex`/
     * `localOffset` jsou pozice PŘEPOČÍTANÉ na tenhle konkrétní segment (ne globální index přes
     * všechny segmenty). Přepne "aktivní" kapitolu (název v horní liště, ukládání postupu,
     * spouštěč přednačítání) a znovu použije existující [onPageChanged]/[saveWebtoonScrollOffset]
     * - ty už samy o sobě ukládají postup a spouští [preloadNextChapter] správně, jen potřebují
     * mít [currentChapter]/`_pages` nastavené na TUHLE kapitolu.
     *
     * Vědomě NEMAŽE starší segmenty z [_webtoonSegments] (i když se čtenář o pár kapitol
     * dostane dál) - LazyColumn je virtualizovaný (dávno odscrollované položky se nedrží
     * složené) a přehled URL adres je zanedbatelně malý, takže by mazání jen riskovalo bug
     * (viz git historie - dřívější verze mazala první segment a tím měnila jeho identitu,
     * což omylem znovu spustilo obnovu pozice ve WebtoonReaderu a způsobilo skok scrollu).
     */
    fun onWebtoonVisibleChapterChanged(chapterId: String, localIndex: Int, localOffset: Int) {
        if (chapterId != _currentChapterId.value) {
            val chapter = allChapters.firstOrNull { it.id == chapterId } ?: return
            val segment = _webtoonSegments.value.firstOrNull { it.chapterId == chapterId } ?: return
            currentChapter = chapter
            _currentChapterId.value = chapter.id
            _chapterTitle.value = chapter.name
            _chapterIndex.value = allChapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
            updateNavState()
            _pages.value = segment.pages
            _translatedPages.value = emptyMap()
            _flippedBubbles.value = emptySet()
            _translateMode.value = false
        }
        onPageChanged(localIndex)
        saveWebtoonScrollOffset(localOffset)
    }

    /**
     * Warmuje Room cache (TranslatedNovelEntity, viz TranslateRepository.getCachedNovel)
     * překladem DALŠÍ kapitoly light novel na pozadí, jakmile se dokončí překlad AKTUÁLNÍ
     * kapitoly (viz volání v toggleNovelTranslate) - když uživatel přejde na další kapitolu
     * a zapne překlad, najde ho hotový okamžitě. Nezasahuje do _novelTranslatedText/
     * _novelTranslating (ty patří AKTUÁLNÍ kapitole) - jde jen o zápis do cache, žádný
     * viditelný UI stav pro tuhle kapitolu. Řízeno nastavením (SettingsRepository.
     * preloadNextNovelChapter), výchozí zapnuto. Zrušeno (viz loadChapter), jakmile
     * uživatel odejde jinam, než přednačítání stihne doběhnout.
     */
    private fun preloadNextNovelChapter() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return
        val nextChapter = allChapters[idx - 1]
        val targetLanguage = _targetLanguage.value
        val sourceLanguage = _sourceLanguage.value

        novelPreloadJob?.cancel()
        novelPreloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!settings.preloadNextNovelChapter.first()) return@launch
            if (translateRepository.getCachedNovel(nextChapter.id, targetLanguage, sourceLanguage) != null) return@launch
            try {
                val manga = repository.getManga(nextChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(nextChapter.sourceId, nextChapter.url, manga.url)
                if (rawPages.none { it.imageUrl == "novel://text" }) return@launch
                val text = rawPages.firstOrNull()?.url?.takeIf { it.isNotBlank() } ?: return@launch
                translateRepository.translateNovelChapter(
                    chapterId = nextChapter.id,
                    mangaId = nextChapter.mangaId,
                    text = text,
                    targetLanguage = targetLanguage,
                    sourceLanguage = sourceLanguage,
                )
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterNovelTranslation")
            }
        }
    }

    /**
     * Stejná myšlenka jako [preloadNextNovelChapter], ale pro manga/manhwa/manhua - warmuje
     * Room cache ([TranslatedPageDao] přes [TranslateRepository.translateChapter]) překladem
     * DALŠÍ kapitoly na pozadí, jakmile dokončí překlad AKTUÁLNÍ kapitoly.
     *
     * Na rozdíl od novely (jen text) tohle stáhne CELOU další kapitolu obrázků + spustí OCR
     * na zařízení pro každou stránku - výrazně dražší na data i baterii, proto:
     * - samostatný přepínač (SettingsRepository.preloadNextChapterManga), ne sdílený s novelou
     * - respektuje [SettingsRepository.preloadNextChapterWifiOnly] (výchozí zapnuto) - na
     *   mobilních datech se nespustí, dokud si to uživatel vědomě nezapne v nastavení.
     */
    private fun preloadNextChapterMangaTranslation() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx <= 0) return
        val nextChapter = allChapters[idx - 1]
        val targetLanguage = _targetLanguage.value
        val sourceLanguage = _sourceLanguage.value

        mangaTranslatePreloadJob?.cancel()
        mangaTranslatePreloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!settings.preloadNextChapterManga.first()) return@launch
            if (settings.preloadNextChapterWifiOnly.first() && !networkMonitor.isUnmetered) return@launch
            try {
                val manga = repository.getManga(nextChapter.mangaId) ?: return@launch
                val rawPages = repository.getChapterPages(nextChapter.sourceId, nextChapter.url, manga.url)
                if (rawPages.any { it.imageUrl == "novel://text" }) return@launch // novela - viz preloadNextNovelChapter
                val urls = rawPages.mapNotNull { it.imageUrl?.takeIf { u -> u.isNotBlank() } ?: it.url.takeIf { u -> u.isNotBlank() } }
                if (urls.isEmpty()) return@launch
                translateRepository.translateChapter(
                    pages = urls,
                    chapterId = nextChapter.id,
                    mangaId = nextChapter.mangaId,
                    targetLanguage = targetLanguage,
                    sourceLanguage = sourceLanguage,
                ) { _, _ -> } // jen zápis do cache, žádný viditelný UI stav pro tuhle kapitolu
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterTranslation")
            }
        }
    }

    fun onPageChanged(index: Int) {
        _currentPage.value = index
        prefetchPagesFrom(index + 1)

        val total = _pages.value.size
        if (total > 0 && index >= total - 3 && _hasNextChapter.value) preloadNextChapter()

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
        // čtení, "naposledy čteno", čas i počet stránek se ukládaly dál - kapitola se tedy
        // po anonymním přečtení tvářila jako přečtená a čas naskočil do Statistik.
        // Název "Číst anonymně" tím sliboval víc, než dělal.
        var firstTimeRead = false
        if (!incognito) {
            firstTimeRead = reachedEnd && markedReadChapterIds.add(chapterId)
            // `read` se v ramci relace nikdy nesnizuje: dřív krok zpět z posledni stranky zapsal
            // read = false a dočtená kapitola se tvářila jako nepřečtená.
            val read = reachedEnd || chapterId in markedReadChapterIds || chapter.read
            repository.updateReadProgress(chapterId, read = read, lastPageRead = event.index, lastReadAt = event.now)
            repository.updateLastReadChapter(chapter.mangaId, chapterId)
            if (event.deltaMs > 0) {
                settings.addReadingTime(event.deltaMs)
                repository.addMangaReadingTime(chapter.mangaId, event.deltaMs)
            }
            settings.addPagesRead(1)
            if (manga != null) {
                historyRepository.record(
                    ReadHistoryEntity(
                        chapterId = chapterId,
                        mangaId = chapter.mangaId,
                        mangaTitle = manga.title,
                        coverUrl = manga.coverUrl,
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
        }
    }

    // ── Překlad ──────────────────────────────────────────────────────────────

    fun setSourceLanguage(lang: String) {
        _sourceLanguage.value = lang
        viewModelScope.launch { settings.setSourceLanguage(lang) }
        _translatedPages.value = emptyMap()
        // I perzistentní by-chapter mapa - jinak by kapitola navštívená PŘED změnou jazyka
        // (a tedy ve WebtoonReaderu dál "živá" v paměti) ukazovala překlad ve starém jazyce.
        _translatedPagesByChapter.value = emptyMap()
        _translateMode.value = false
    }

    fun setTargetLanguage(lang: String) {
        _targetLanguage.value = lang
        viewModelScope.launch { settings.setTargetLanguage(lang) }
        _translatedPages.value = emptyMap()
        _translatedPagesByChapter.value = emptyMap()
        _translateMode.value = false
    }

    fun toggleTranslate() {
        when {
            translationJob?.isActive == true -> {
                translationJob?.cancel()
                translationJob = null
                _translationProgress.value = null
            }
            !_translateMode.value -> {
                _translateMode.value = true
                _translationError.value = null
                startChapterTranslation()
            }
            else -> _translateMode.value = false
        }
    }

    private fun startChapterTranslation() {
        translationJob = viewModelScope.launch {
            val pages = _pages.value
            val lang = _targetLanguage.value
            val chapterId = currentChapter?.id ?: return@launch
            val mangaId = currentManga?.id ?: currentChapter?.mangaId ?: ""

            var done = 0
            _translationProgress.value = TranslationProgress(done, pages.size)
            try {
                // translateChapter dávkuje víc stránek do jednoho API volání (viz
                // TranslateRepository.translateChapter) - onPageReady se ale volá pro
                // KAŽDOU stránku zvlášť, takže postupné zobrazování zůstává stejné jako
                // dřív, jen s méně požadavky a bez umělé prodlevy mezi každou stránkou.
                translateRepository.translateChapter(
                    pages = pages,
                    chapterId = chapterId,
                    mangaId = mangaId,
                    targetLanguage = lang,
                    sourceLanguage = _sourceLanguage.value,
                ) { pageIndex, blocks ->
                    putTranslatedPage(chapterId, pageIndex, blocks)
                    done++
                    _translationProgress.value = TranslationProgress(done, pages.size)
                }
                preloadNextChapterMangaTranslation()
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Bez tohohle výjimka utekla z viewModelScope.launch a shodila appku.
                e.report("reader:translateChapter")
                _translationError.value = context.getString(R.string.reader_error_translation_failed)
            } finally {
                _translationProgress.value = null
            }
        }
    }

    // ── Hromadný překlad všech stránek + přepínač originál/překlad ──────────

    fun translateAllPages() {
        if (_batchTranslating.value) return
        _batchTranslating.value = true
        _showOriginal.value = false
        // Vycistit predchozi hlasku - jinak by ji nize v `finally` mohla omylem "prezit" i
        // stara/nesouvisejici chyba a potlacit spravnou hlasku pro TENHLE pokus.
        _translationError.value = null
        batchJob = viewModelScope.launch {
            val pages = _pages.value
            val lang = _targetLanguage.value
            val chapterId = currentChapter?.id ?: run { _batchTranslating.value = false; return@launch }
            val mangaId = currentManga?.id ?: currentChapter?.mangaId ?: ""

            var done = 0
            _batchProgress.value = TranslationProgress(done, pages.size)
            try {
                // translateChapter si samo ověří Room cache per stránku (viz
                // TranslateRepository.translateChapter) - stránka s dřívějším neúspěšným
                // pokusem (prázdný seznam v paměti, ale nic v cache) se tak automaticky
                // zkusí znovu, stejně jako dřívější isNullOrEmpty() kontrola zajišťovala.
                translateRepository.translateChapter(
                    pages = pages,
                    chapterId = chapterId,
                    mangaId = mangaId,
                    targetLanguage = lang,
                    sourceLanguage = _sourceLanguage.value,
                ) { pageIndex, blocks ->
                    putTranslatedPage(chapterId, pageIndex, blocks)
                    done++
                    _batchProgress.value = TranslationProgress(done, pages.size)
                }
                preloadNextChapterMangaTranslation()
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                // Dalsi pokusy by stejne selhaly na stejnem limitu - nema smysl
                // prohanet zbytek davky, jen ukazat srozumitelnou hlasku.
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("reader:translateAllPages")
            } finally {
                _batchProgress.value = null
                _batchTranslating.value = false
                // translateMode=true prepina UI z tlacitka "Prelozit vse" na prepinac
                // Original/Preklad (viz ReaderControls - tlacitko se renderuje jen
                // "else if (!translateMode)") - jakmile jednou zustane true bez skutecneho
                // prekladu, uzivatel nema ZADNOU cestu zpet ke spusteni prekladu znovu.
                //
                // Drivejsi kod ho tady nastavoval BEZPODMINECNE, i po RateLimitedException
                // nebo po davce, kde vsechny stranky skoncily s prazdnym vysledkem (napr.
                // vypadek/omezeni site na pozadi, kdyz appka byla minimalizovana - viz
                // uzivatelska zpetna vazba "vybehnu na Instagram, dole to ukaze uz
                // prelozene, ale nikde nic neni prelozeno a nemuzu dat prelozit znovu").
                // Kontrola podle skutecneho obsahu _translatedPages misto slepe duvery
                // v to, ze smycka dobehla - true jen kdyz aspon JEDNA stranka opravdu ma
                // neprazdny (ne-SFX) preklad, jinak zustane tlacitko k dispozici a uzivatel
                // dostane konkretni hlasku misto tiseho "hotovo" bez obsahu.
                val hasAnyTranslation = _translatedPages.value.values.any { blocks -> blocks.any { !it.isSfx } }
                if (hasAnyTranslation) {
                    _translateMode.value = true
                } else if (_translationError.value == null) {
                    _translationError.value = if (!translateRepository.isApiKeyConfigured &&
                        !translateRepository.onDeviceSupportsLanguage(_targetLanguage.value)
                    ) {
                        context.getString(R.string.reader_error_language_unsupported_offline)
                    } else {
                        context.getString(R.string.reader_error_translation_failed)
                    }
                }
            }
        }
    }

    fun cancelBatchTranslation() {
        batchJob?.cancel()
        batchJob = null
        _batchTranslating.value = false
        _batchProgress.value = null
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

    // Rozběhnuté přednačítací požadavky - po odchodu ze čtečky se ruší, aby dál nedržely sloty hostitele
    // a nezdržovaly obálky/loga ve výpisu zdroje (Coil je jinak dotáhne i bez čtečky).
    private val prefetchDisposables = java.util.Collections.synchronizedList(mutableListOf<coil.request.Disposable>())

    override fun onCleared() {
        synchronized(prefetchDisposables) { prefetchDisposables.forEach { it.dispose() } }
        prefetchDisposables.clear()
        // Čtečka skončila (ne rotace - ta ViewModel nečistí): odpočet už nemá koho ukončit.
        sleepTimerManager.cancel()
        flushPendingAutoDelete()
        pageProgressEvents.close()
        super.onCleared()
    }
}

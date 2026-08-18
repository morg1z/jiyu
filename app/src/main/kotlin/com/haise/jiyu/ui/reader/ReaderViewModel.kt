package com.haise.jiyu.ui.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.delay
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

data class TranslationProgress(val done: Int, val total: Int)

/** Jak dlouho po poslednim cteni jeste ma smysl obnovovat presnou stranku/scroll - viz [ReaderViewModel.loadChapter]. */
private const val POSITION_FRESHNESS_MS = 10L * 24 * 60 * 60 * 1000

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: MangaRepository,
    private val translateRepository: TranslateRepository,
    private val settings: SettingsRepository,
    private val historyDao: ReadHistoryDao,
    private val aniListRepository: AniListRepository,
    private val malRepository: MalRepository,
    private val kitsuRepository: KitsuRepository,
    private val muRepository: MangaUpdatesRepository,
    private val glossaryDao: GlossaryDao,
    private val sleepTimerManager: SleepTimerManager,
    private val networkMonitor: com.haise.jiyu.util.NetworkMonitor,
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])
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

    private val _comickUnavailable = MutableStateFlow(false)
    val comickUnavailable: StateFlow<Boolean> = _comickUnavailable.asStateFlow()

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

    val pageScale: StateFlow<String> = settings.pageScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fit_width")

    val autoNextChapter: StateFlow<Boolean> = settings.autoNextChapter
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
    private val nextChapterCache = mutableMapOf<String, List<String>>()
    private var preloadJob: Job? = null
    private var novelPreloadJob: Job? = null
    private var mangaTranslatePreloadJob: Job? = null

    private val _webtoonScrollOffset = MutableStateFlow(0)
    val webtoonScrollOffset: StateFlow<Int> = _webtoonScrollOffset.asStateFlow()

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
            else glossaryDao.observeForManga(mangaId).map { list -> list.filter { it.targetLanguage == lang } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addGlossaryEntry(sourceTerm: String, targetTerm: String) {
        val source = sourceTerm.trim()
        val target = targetTerm.trim()
        val mangaId = currentManga?.id ?: currentChapter?.mangaId ?: return
        if (source.isBlank() || target.isBlank()) return
        val lang = _targetLanguage.value
        viewModelScope.launch {
            glossaryDao.upsert(
                GlossaryEntity(
                    id = "$mangaId::${source.lowercase()}::$lang",
                    mangaId = mangaId,
                    sourceTerm = source,
                    targetTerm = target,
                    targetLanguage = lang,
                )
            )
        }
    }

    fun removeGlossaryEntry(entry: GlossaryEntity) = viewModelScope.launch { glossaryDao.delete(entry) }

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
                    _translationError.value = context.getString(R.string.reader_error_translation_failed)
                    _novelTranslateMode.value = false
                }
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
                _novelTranslateMode.value = false
            } catch (_: Exception) {
                _translationError.value = context.getString(R.string.reader_error_translation_failed)
                _novelTranslateMode.value = false
            } finally {
                _novelTranslating.value = false
            }
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
    fun saveBubbleEdit(pageIndex: Int, originalText: String, text: String) {
        val chapterId = currentChapter?.id ?: return
        viewModelScope.launch {
            translateRepository.saveManualEdit(chapterId, pageIndex, originalText, text)
            val blocks = _translatedPages.value[pageIndex] ?: return@launch
            val trimmed = text.trim()
            if (trimmed.isBlank()) return@launch
            _translatedPages.value = _translatedPages.value + (
                pageIndex to blocks.map { block ->
                    if (normalizeOriginal(block.originalText) == normalizeOriginal(originalText)) {
                        block.copy(translatedText = trimmed, displayText = trimmed, isUntranslated = false)
                    } else {
                        block
                    }
                }
                )
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

    init {
        scheduleControlsAutoHide()
        viewModelScope.launch { loadChapter(chapterEntityId) }
        viewModelScope.launch {
            _sourceLanguage.value = settings.sourceLanguage.first()
            _targetLanguage.value = settings.targetLanguage.first()
        }
        // Série čtení je taky zapsaná stopa, takže ji anonymní čtení nezvedá. Rozhoduje stav
        // při otevření kapitoly - přepnutí přepínače uprostřed už zpětně nic neubírá.
        if (!startIncognito) viewModelScope.launch { settings.updateReadingStreak() }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _sessionElapsed.value = System.currentTimeMillis() - sessionStartMs
            }
        }
    }

    // ── Načítání kapitoly ────────────────────────────────────────────────────

    private suspend fun loadChapter(id: String) {
        _loading.value = true
        _pages.value = emptyList()
        _translatedPages.value = emptyMap()
        // Klíč je "$pageIndex:$bubbleIndex" bez chapterId - stránkování se v každé kapitole
        // čísluje znovu od 0, takže bez resetu by "otočená" bublina 3:2 z minulé kapitoly
        // zůstala otočená i na stránce 3 v nové kapitole, i když jde o úplně jinou bublinu.
        _flippedBubbles.value = emptySet()
        _translateMode.value = false
        translationJob?.cancel()
        translationJob = null
        _translationProgress.value = null
        _novelTranslateMode.value = false
        _novelTranslatedText.value = null
        novelTranslationJob?.cancel()
        novelTranslationJob = null
        _novelTranslating.value = false

        val chapter = repository.getChapter(id) ?: run { _loading.value = false; return }
        currentChapter = chapter
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
        mangaForDir?.let { manga ->
            if (!manga.inLibrary) {
                repository.addExistingToLibrary(manga.id)
                repository.setReadingStatus(manga.id, "READING")
                settings.defaultCategoryId.first()?.let { repository.addMangaToCategory(manga.id, it) }
            }
        }
        currentManga = mangaForDir
        _currentMangaId.value = mangaForDir?.id
        _mangaTitle.value = mangaForDir?.title ?: ""
        _mangaDirectionOverride.value = mangaForDir?.readerDirectionOverride

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
            val pageUrls = ChapterStorage.listPageUrls(context, chapter.localPath)
            _pages.value = pageUrls
            _isOfflineChapter.value = true
            // Detect landscape pages for smart spread grouping
            viewModelScope.launch {
                val spread = pageUrls.mapIndexedNotNull { idx, url ->
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
                    try {
                        val rawPages = repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
                        val isNovel = rawPages.any { it.imageUrl == "novel://text" }
                        _isNovelSource.value = isNovel
                        if (isNovel) {
                            _novelText.value = rawPages.firstOrNull()?.url ?: ""
                            _pages.value = emptyList()
                        } else {
                            _novelText.value = ""
                            _pages.value = rawPages.map { it.imageUrl ?: it.url }
                        }
                    } catch (_: Exception) {
                        // Zdroj selhal (expirovana/geoblokovana kapitola, sitova chyba...) -
                        // prazdny seznam stranek uz UI zobrazi jako "Kapitolu se nepodařilo načíst."
                        _isNovelSource.value = false
                        _pages.value = emptyList()
                    }
                }
            }
        }
        lastPageChangeMs = System.currentTimeMillis()
        _loading.value = false
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
        viewModelScope.launch { loadChapter(chapterId) }
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
        viewModelScope.launch { loadChapter(allChapters[target].id) }
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
        viewModelScope.launch { loadChapter(allChapters[target].id) }
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
            } catch (e: Exception) {
                e.report("reader:preloadNextChapterTranslation")
            }
        }
    }

    fun onPageChanged(index: Int) {
        _currentPage.value = index

        val total = _pages.value.size
        if (total > 0 && index >= total - 3 && _hasNextChapter.value) preloadNextChapter()

        // Čas od poslední stránky — max 3 min (filtrace idle)
        val now = System.currentTimeMillis()
        val deltaMs = if (lastPageChangeMs > 0) minOf(now - lastPageChangeMs, 3 * 60_000L) else 0L
        lastPageChangeMs = now

        viewModelScope.launch {
            val pageCount = _pages.value.size
            val isRead = index >= pageCount - 1
            val chapter = currentChapter ?: return@launch
            val chapterId = chapter.id
            val incognito = _incognitoMode.value

            // Inkognito nezapisuje NIC. Dřív vynechávalo jen historii a trackery, ale postup
            // čtení, "naposledy čteno", čas i počet stránek se ukládaly dál - kapitola se tedy
            // po anonymním přečtení tvářila jako přečtená a čas naskočil do Statistik.
            // Název "Číst anonymně" tím sliboval víc, než dělal.
            if (!incognito) {
                repository.updateReadProgress(chapterId, read = isRead, lastPageRead = index, lastReadAt = now)
                repository.updateLastReadChapter(chapter.mangaId, chapterId)
                if (deltaMs > 0) {
                    settings.addReadingTime(deltaMs)
                    repository.addMangaReadingTime(chapter.mangaId, deltaMs)
                }
                settings.addPagesRead(1)
            }

            val manga = currentManga
            if (!incognito && manga != null) {
                historyDao.record(
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
            if (isRead) {
                // Taky pod inkognitem: kapitola se neoznačila přečtenou, takže by automatické
                // mazání sahalo na stažené soubory kvůli něčemu, co se "nestalo".
                if (!incognito) maybeAutoDelete()
                if (!incognito && manga != null) {
                    viewModelScope.launch {
                        try { aniListRepository.updateProgress(chapter.mangaId, manga.title, chapter.chapterNumber) } catch (e: Exception) { e.report("reader:anilist:updateProgress") }
                    }
                    manga.malId?.let { malId ->
                        viewModelScope.launch {
                            try {
                                malRepository.updateMangaStatus(
                                    malId = malId,
                                    status = "reading",
                                    numChaptersRead = chapter.chapterNumber.toInt(),
                                )
                            } catch (e: Exception) {
                                e.report("reader:mal:updateMangaStatus")
                            }
                        }
                    }
                    manga.kitsuId?.let { kitsuId ->
                        viewModelScope.launch {
                            try { kitsuRepository.updateProgress(kitsuId, chapter.chapterNumber.toInt()) } catch (e: Exception) { e.report("reader:kitsu:updateProgress") }
                        }
                    }
                    manga.mangaUpdatesId?.let { seriesId ->
                        viewModelScope.launch {
                            try { muRepository.updateProgress(seriesId, chapter.chapterNumber.toInt()) } catch (e: Exception) { e.report("reader:mangaupdates:updateProgress") }
                        }
                    }
                }
            }
        }
    }

    // ── Překlad ──────────────────────────────────────────────────────────────

    fun setSourceLanguage(lang: String) {
        _sourceLanguage.value = lang
        viewModelScope.launch { settings.setSourceLanguage(lang) }
        _translatedPages.value = emptyMap()
        _translateMode.value = false
    }

    fun setTargetLanguage(lang: String) {
        _targetLanguage.value = lang
        viewModelScope.launch { settings.setTargetLanguage(lang) }
        _translatedPages.value = emptyMap()
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
                    _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
                    done++
                    _translationProgress.value = TranslationProgress(done, pages.size)
                }
                preloadNextChapterMangaTranslation()
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
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
                    _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
                    done++
                    _batchProgress.value = TranslationProgress(done, pages.size)
                }
                preloadNextChapterMangaTranslation()
            } catch (_: com.haise.jiyu.translate.RateLimitedException) {
                // Dalsi pokusy by stejne selhaly na stejnem limitu - nema smysl
                // prohanet zbytek davky, jen ukazat srozumitelnou hlasku.
                _translationError.value = context.getString(R.string.reader_error_rate_limited)
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
                    _translationError.value = context.getString(R.string.reader_error_translation_failed)
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

    private fun deleteChapterFiles(chapter: ChapterEntity) {
        chapter.localPath?.let { path -> ChapterStorage.deleteRecursively(context, path) }
        viewModelScope.launch { repository.resetDownloadForChapter(chapter.id) }
    }

    fun maybeAutoDelete() {
        viewModelScope.launch {
            val enabled = settings.autoDeleteRead.first()
            if (!enabled) return@launch
            val chapter = currentChapter ?: return@launch
            // chapter.read je stale in-memory entita; spolehni se na volajícího (onPageChanged isRead)
            val delayDays = settings.autoDeleteDelayDays.first()
            if (delayDays > 0) {
                // Plánuj přes WorkManager — viewModelScope se zruší při opuštění čtečky
                AutoDeleteWorker.schedule(context, chapter.id, delayDays.toLong())
            } else {
                val fresh = repository.getChapter(chapter.id) ?: return@launch
                if (fresh.read && fresh.downloadStatus == DownloadStatus.DOWNLOADED) {
                    deleteChapterFiles(fresh)
                }
            }
        }
    }
}

package com.haise.jiyu.ui.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.tracking.KitsuRepository
import com.haise.jiyu.data.tracking.MalRepository
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.groq.GroqRepository
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
import com.haise.jiyu.translate.TranslatedBlock
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

data class TranslationProgress(val done: Int, val total: Int)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
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
    private val groqRepository: GroqRepository,
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

    // ── Webtoon scroll memory (in-memory, per chapter, max 50 entries LRU) ──
    private val webtoonPositions = mutableMapOf<String, Int>()

    // ── Přednačítání další kapitoly ──────────────────────────────────────────
    private val nextChapterCache = mutableMapOf<String, List<String>>()
    private var preloadJob: Job? = null

    private val _webtoonScrollOffset = MutableStateFlow(0)
    val webtoonScrollOffset: StateFlow<Int> = _webtoonScrollOffset.asStateFlow()

    fun saveWebtoonScrollOffset(offset: Int) {
        val id = currentChapter?.id ?: return
        webtoonPositions[id] = offset
        if (webtoonPositions.size > 50) webtoonPositions.remove(webtoonPositions.keys.first())
    }

    // ── AI shrnutí kapitoly ──────────────────────────────────────────────────
    private val _chapterSummary = MutableStateFlow<String?>(null)
    val chapterSummary: StateFlow<String?> = _chapterSummary.asStateFlow()

    private val _summaryLoading = MutableStateFlow(false)
    val summaryLoading: StateFlow<Boolean> = _summaryLoading.asStateFlow()

    private val summaryCache = mutableMapOf<String, String>()
    private var lastSummaryRequestMs = 0L

    fun loadChapterSummary() {
        val chapter = currentChapter ?: return
        if (_summaryLoading.value) return
        summaryCache[chapter.id]?.let { cached ->
            _chapterSummary.value = cached
            return
        }
        if (System.currentTimeMillis() - lastSummaryRequestMs < 10_000L) return
        lastSummaryRequestMs = System.currentTimeMillis()
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        val prevChapter = allChapters.getOrNull(idx + 1) // DESC → starší kapitola
        viewModelScope.launch {
            _summaryLoading.value = true
            _chapterSummary.value = null
            val mangaTitle = repository.getManga(chapter.mangaId)?.title ?: ""
            val result = groqRepository.getChapterSummary(mangaTitle, chapter.name, prevChapter?.name)
            if (result != null) summaryCache[chapter.id] = result
            _chapterSummary.value = result
            _summaryLoading.value = false
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

    private val _sourceLanguage = MutableStateFlow("English")
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
            _translationError.value = "Chybí Groq API klíč - přidej GROQ_API_KEY do local.properties"
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
                } else {
                    _translationError.value = "Překlad kapitoly selhal, zkus to znovu"
                    _novelTranslateMode.value = false
                }
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

    fun clearTranslationError() { _translationError.value = null }

    fun startSleepTimer(minutes: Int, onFinish: () -> Unit) =
        sleepTimerManager.start(minutes, onFinish)

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
        viewModelScope.launch { loadChapter(chapterEntityId) }
        viewModelScope.launch {
            _sourceLanguage.value = settings.sourceLanguage.first()
            _targetLanguage.value = settings.targetLanguage.first()
        }
        viewModelScope.launch { settings.updateReadingStreak() }
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
        _chapterSummary.value = null
        _summaryLoading.value = false
        _translatedPages.value = emptyMap()
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
        _initialPage.value = chapter.lastPageRead.coerceAtLeast(0)
        _currentPage.value = _initialPage.value
        _webtoonScrollOffset.value = webtoonPositions[id] ?: 0

        allChapters = repository.getAllChapters(chapter.mangaId)
        _allChaptersFlow.value = allChapters
        _chapterCount.value = allChapters.size
        _chapterIndex.value = allChapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
        updateNavState()

        val mangaForDir = repository.getManga(chapter.mangaId)
        currentManga = mangaForDir
        _currentMangaId.value = mangaForDir?.id
        _mangaDirectionOverride.value = mangaForDir?.readerDirectionOverride

        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
            val dir = File(chapter.localPath)
            val sortedFiles = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            _pages.value = sortedFiles.map { "file://${it.absolutePath}" }
            _isOfflineChapter.value = true
            // Detect landscape pages for smart spread grouping
            viewModelScope.launch {
                val spread = sortedFiles.mapIndexedNotNull { idx, file ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                    if (opts.outWidth > 0 && opts.outWidth > opts.outHeight * 1.2f) idx else null
                }.toSet()
                _spreadPageIndices.value = spread
            }
        } else {
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
            } catch (_: Exception) {}
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
            repository.updateReadProgress(chapterId, read = isRead, lastPageRead = index)
            repository.updateLastReadChapter(chapter.mangaId, chapterId)
            if (deltaMs > 0) {
                settings.addReadingTime(deltaMs)
                repository.addMangaReadingTime(chapter.mangaId, deltaMs)
            }
            settings.addPagesRead(1)

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
                maybeAutoDelete()
                if (!incognito && manga != null) {
                    viewModelScope.launch {
                        try { aniListRepository.updateProgress(chapter.mangaId, manga.title, chapter.chapterNumber) } catch (_: Exception) {}
                    }
                    manga.malId?.let { malId ->
                        viewModelScope.launch {
                            try {
                                malRepository.updateMangaStatus(
                                    malId = malId,
                                    status = "reading",
                                    numChaptersRead = chapter.chapterNumber.toInt(),
                                )
                            } catch (_: Exception) {}
                        }
                    }
                    manga.kitsuId?.let { kitsuId ->
                        viewModelScope.launch {
                            try { kitsuRepository.updateProgress(kitsuId, chapter.chapterNumber.toInt()) } catch (_: Exception) {}
                        }
                    }
                    manga.mangaUpdatesId?.let { seriesId ->
                        viewModelScope.launch {
                            try { muRepository.updateProgress(seriesId, chapter.chapterNumber.toInt()) } catch (_: Exception) {}
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
                if (!translateRepository.isApiKeyConfigured) {
                    _translationError.value = "Chybí Groq API klíč - přidej GROQ_API_KEY do local.properties"
                    return
                }
                _translateMode.value = true
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

            pages.forEachIndexed { index, _ ->
                if (_translatedPages.value[index] == null) {
                    val cached = translateRepository.getCachedPage(chapterId, index, lang, _sourceLanguage.value)
                    if (cached != null) {
                        _translatedPages.value = _translatedPages.value + (index to cached)
                    }
                }
            }

            val uncached = pages.indices.filter { _translatedPages.value[it] == null }
            if (uncached.isEmpty()) { _translationProgress.value = null; return@launch }

            var done = pages.size - uncached.size
            _translationProgress.value = TranslationProgress(done, pages.size)

            try {
                uncached.forEachIndexed { i, pageIndex ->
                    if (i > 0) delay(2_000L)
                    try {
                        val blocks = translateRepository.translatePage(
                            pageUrl = pages[pageIndex],
                            chapterId = chapterId,
                            mangaId = currentManga?.id ?: currentChapter?.mangaId ?: "",
                            pageIndex = pageIndex,
                            targetLanguage = lang,
                            sourceLanguage = _sourceLanguage.value,
                        )
                        _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
                    } catch (_: Exception) { /* stránka selhala, pokračuj dál */ }
                    done++
                    _translationProgress.value = TranslationProgress(done, pages.size)
                }
            } finally {
                _translationProgress.value = null
            }
        }
    }

    // ── Hromadný překlad všech stránek + přepínač originál/překlad ──────────

    fun translateAllPages() {
        if (_batchTranslating.value) return
        if (!translateRepository.isApiKeyConfigured) {
            _translationError.value = "Chybí Groq API klíč - přidej GROQ_API_KEY do local.properties"
            return
        }
        _batchTranslating.value = true
        _showOriginal.value = false
        batchJob = viewModelScope.launch {
            val pages = _pages.value
            val lang = _targetLanguage.value
            val chapterId = currentChapter?.id ?: run { _batchTranslating.value = false; return@launch }

            pages.forEachIndexed { index, _ ->
                if (_translatedPages.value[index] == null) {
                    val cached = translateRepository.getCachedPage(chapterId, index, lang, _sourceLanguage.value)
                    if (cached != null) {
                        _translatedPages.value = _translatedPages.value + (index to cached)
                    }
                }
            }

            val uncached = pages.indices.filter { _translatedPages.value[it] == null }
            var done = pages.size - uncached.size
            _batchProgress.value = TranslationProgress(done, pages.size)

            uncached.forEachIndexed { i, pageIndex ->
                if (i > 0) delay(2_000L)
                try {
                    val blocks = translateRepository.translatePage(
                        pageUrl = pages[pageIndex],
                        chapterId = chapterId,
                        mangaId = currentManga?.id ?: currentChapter?.mangaId ?: "",
                        pageIndex = pageIndex,
                        targetLanguage = lang,
                        sourceLanguage = _sourceLanguage.value,
                    )
                    _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
                } catch (_: Exception) { /* stránka selhala, pokračuj dál */ }
                done++
                _batchProgress.value = TranslationProgress(done, pages.size)
            }

            _batchProgress.value = null
            _batchTranslating.value = false
            _translateMode.value = true
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
        chapter.localPath?.let { path ->
            try { File(path).deleteRecursively() } catch (_: Exception) {}
        }
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

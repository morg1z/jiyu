package com.haise.jiyu.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.util.SleepTimerManager
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.translate.TranslatedBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class TranslationProgress(val done: Int, val total: Int)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val translateRepository: TranslateRepository,
    private val settings: SettingsRepository,
    private val historyDao: ReadHistoryDao,
    private val aniListRepository: AniListRepository,
    private val sleepTimerManager: SleepTimerManager,
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])
    private var currentChapter: ChapterEntity? = null
    private var allChapters: List<ChapterEntity> = emptyList()

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

    // ── Incognito mode ───────────────────────────────────────────────────────
    private val _incognitoMode = MutableStateFlow(false)
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

        val horizontalCuts = mutableListOf(0)
        for (y in 0 until h) {
            var darkCount = 0
            for (x in 0 until w step 4) {
                val pixel = bmp.getPixel(x, y)
                val brightness = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
                if (brightness < threshold) darkCount++
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
        _translatedPages.value = emptyMap()
        _translateMode.value = false
        translationJob?.cancel()
        translationJob = null
        _translationProgress.value = null

        val chapter = repository.getChapter(id) ?: run { _loading.value = false; return }
        currentChapter = chapter
        _chapterTitle.value = chapter.name
        _initialPage.value = chapter.lastPageRead.coerceAtLeast(0)
        _currentPage.value = _initialPage.value

        allChapters = repository.getAllChapters(chapter.mangaId)
        _chapterCount.value = allChapters.size
        _chapterIndex.value = allChapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
        updateNavState()

        val mangaForDir = repository.getManga(chapter.mangaId)
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

    fun navigateNext() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx > 0) viewModelScope.launch { loadChapter(allChapters[idx - 1].id) }
    }

    fun navigatePrev() {
        val chapter = currentChapter ?: return
        val idx = allChapters.indexOfFirst { it.id == chapter.id }
        if (idx < allChapters.lastIndex) viewModelScope.launch { loadChapter(allChapters[idx + 1].id) }
    }

    // ── Čtení ────────────────────────────────────────────────────────────────

    fun onPageChanged(index: Int) {
        _currentPage.value = index

        // Čas od poslední stránky — max 3 min (filtrace idle)
        val now = System.currentTimeMillis()
        val deltaMs = if (lastPageChangeMs > 0) minOf(now - lastPageChangeMs, 3 * 60_000L) else 0L
        lastPageChangeMs = now

        viewModelScope.launch {
            val total = _pages.value.size
            val isRead = index >= total - 1
            val chapter = currentChapter ?: return@launch
            val chapterId = chapter.id
            val incognito = _incognitoMode.value
            repository.updateReadProgress(chapterId, read = isRead, lastPageRead = index)
            repository.updateLastReadChapter(chapter.mangaId, chapterId)
            if (deltaMs > 0) settings.addReadingTime(deltaMs)
            settings.addPagesRead(1)

            val manga = repository.getManga(chapter.mangaId)
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

            uncached.forEachIndexed { i, pageIndex ->
                if (i > 0) delay(2_000L)
                val blocks = translateRepository.translatePage(
                    pageUrl = pages[pageIndex],
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    targetLanguage = lang,
                    sourceLanguage = _sourceLanguage.value,
                )
                _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
                done++
                _translationProgress.value = TranslationProgress(done, pages.size)
            }
            _translationProgress.value = null
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
            if (!chapter.read) return@launch
            val delayDays = settings.autoDeleteDelayDays.first()
            if (delayDays > 0) {
                delay(delayDays * 24 * 60 * 60 * 1000L)
            }
            val fresh = repository.getChapter(chapter.id) ?: return@launch
            if (fresh.read && fresh.downloadStatus == DownloadStatus.DOWNLOADED) {
                deleteChapterFiles(fresh)
            }
        }
    }
}

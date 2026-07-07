package com.haise.jiyu.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.ReadHistoryDao
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

    val readerTextScale: StateFlow<Float> = settings.readerTextScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    val doublePageSpread: StateFlow<Boolean> = settings.doublePageSpread
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    private val _batchProgress = MutableStateFlow<TranslationProgress?>(null)
    val batchProgress: StateFlow<TranslationProgress?> = _batchProgress.asStateFlow()

    private val _showOriginal = MutableStateFlow(false)
    val showOriginal: StateFlow<Boolean> = _showOriginal.asStateFlow()

    fun clearTranslationError() { _translationError.value = null }

    private var translationJob: Job? = null
    private var batchJob: Job? = null
    private var lastPageChangeMs = 0L

    init {
        viewModelScope.launch { loadChapter(chapterEntityId) }
        viewModelScope.launch {
            _sourceLanguage.value = settings.sourceLanguage.first()
            _targetLanguage.value = settings.targetLanguage.first()
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
        updateNavState()

        val mangaForDir = repository.getManga(chapter.mangaId)
        _mangaDirectionOverride.value = mangaForDir?.readerDirectionOverride

        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
            val dir = File(chapter.localPath)
            _pages.value = dir.listFiles()
                ?.sortedBy { it.name }
                ?.map { "file://${it.absolutePath}" }
                ?: emptyList()
        } else {
            val manga = repository.getManga(chapter.mangaId)
            if (manga != null) {
                _pages.value = try {
                    repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
                        .map { it.imageUrl ?: it.url }
                } catch (_: Exception) {
                    // Zdroj selhal (expirovana/geoblokovana kapitola, sitova chyba...) -
                    // prazdny seznam stranek uz UI zobrazi jako "Kapitolu se nepodařilo načíst."
                    emptyList()
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
            repository.updateReadProgress(chapterId, read = isRead, lastPageRead = index)
            repository.updateLastReadChapter(chapter.mangaId, chapterId)
            if (deltaMs > 0) settings.addReadingTime(deltaMs)
            settings.addPagesRead(1)

            val manga = repository.getManga(chapter.mangaId)
            if (manga != null) {
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
                if (manga != null) {
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

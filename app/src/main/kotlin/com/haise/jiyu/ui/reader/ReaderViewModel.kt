package com.haise.jiyu.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.ReadingDirection
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Null = překlad neběží; (done, total) = průběh */
data class TranslationProgress(val done: Int, val total: Int)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val translateRepository: TranslateRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])

    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ── Nastavení čtení ──────────────────────────────────────────────────────
    val reverseLayout: StateFlow<Boolean> = settings.readingDirection
        .map { it == ReadingDirection.RTL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Překlad ──────────────────────────────────────────────────────────────

    /** true = overlaye jsou viditelné */
    private val _translateMode = MutableStateFlow(false)
    val translateMode: StateFlow<Boolean> = _translateMode.asStateFlow()

    /** Průběh překladu kapitoly; null = neprobíhá */
    private val _translationProgress = MutableStateFlow<TranslationProgress?>(null)
    val translationProgress: StateFlow<TranslationProgress?> = _translationProgress.asStateFlow()

    /** Přeložené bloky: pageIndex → bloky */
    private val _translatedPages = MutableStateFlow<Map<Int, List<TranslatedBlock>>>(emptyMap())
    val translatedPages: StateFlow<Map<Int, List<TranslatedBlock>>> = _translatedPages.asStateFlow()

    private val _targetLanguage = settings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Czech")

    private var currentPageIndex = 0
    private var translationJob: Job? = null

    init {
        viewModelScope.launch {
            val chapter = repository.getChapter(chapterEntityId)
            if (chapter == null) { _loading.value = false; return@launch }

            if (chapter.downloadStatus == DownloadStatus.DOWNLOADED && chapter.localPath != null) {
                val dir = File(chapter.localPath)
                _pages.value = dir.listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()
            } else {
                val manga = repository.getManga(chapter.mangaId)
                if (manga != null) {
                    val remotePages = repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
                    _pages.value = remotePages.map { it.imageUrl ?: it.url }
                }
            }
            _loading.value = false
        }
    }

    fun onPageChanged(index: Int) {
        currentPageIndex = index
        viewModelScope.launch {
            val total = _pages.value.size
            repository.updateReadProgress(chapterEntityId, read = index >= total - 1, lastPageRead = index)
        }
    }

    /**
     * Přepínač překladu:
     * - Pokud překlad neběžel → spustí překlad celé kapitoly + zapne overlay
     * - Pokud překlad právě běží → zastaví ho
     * - Pokud je hotovo → přepne viditelnost overlaye
     */
    fun toggleTranslate() {
        when {
            translationJob?.isActive == true -> {
                // Zastavit probíhající překlad
                translationJob?.cancel()
                translationJob = null
                _translationProgress.value = null
            }
            !_translateMode.value -> {
                _translateMode.value = true
                startChapterTranslation()
            }
            else -> {
                _translateMode.value = false
            }
        }
    }

    private fun startChapterTranslation() {
        translationJob = viewModelScope.launch {
            val pages = _pages.value
            val lang = _targetLanguage.value

            // 1. Načti okamžitě vše co je v cache (bez API volání, bez čekání)
            pages.forEachIndexed { index, _ ->
                if (_translatedPages.value[index] == null) {
                    val cached = translateRepository.getCachedPage(chapterEntityId, index, lang)
                    if (cached != null) {
                        _translatedPages.value = _translatedPages.value + (index to cached)
                    }
                }
            }

            // 2. Stránky, které ještě nejsou přeloženy → volat API se zpožděním
            val uncachedIndices = pages.indices.filter { _translatedPages.value[it] == null }
            if (uncachedIndices.isEmpty()) {
                _translationProgress.value = null
                return@launch
            }

            var done = pages.size - uncachedIndices.size
            _translationProgress.value = TranslationProgress(done, pages.size)

            uncachedIndices.forEachIndexed { i, pageIndex ->
                // Rate limit: 2s mezi API voláními (Groq free tier ~30 req/min)
                if (i > 0) delay(2_000L)

                val blocks = translateRepository.translatePage(
                    pageUrl = pages[pageIndex],
                    chapterId = chapterEntityId,
                    pageIndex = pageIndex,
                    targetLanguage = lang,
                )
                _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
                done++
                _translationProgress.value = TranslationProgress(done, pages.size)
            }

            _translationProgress.value = null
        }
    }
}

package com.haise.jiyu.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.translate.TranslatedBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val translateRepository: TranslateRepository,
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])

    /** Buď lokální cesty (offline), nebo vzdálené URL (streamed). */
    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ── Překlad ──────────────────────────────────────────────────────────────

    /** true = overlay překladu je zapnutý */
    private val _translateMode = MutableStateFlow(false)
    val translateMode: StateFlow<Boolean> = _translateMode.asStateFlow()

    /** Index stránky, která se právě překládá (null = žádná) */
    private val _translatingPage = MutableStateFlow<Int?>(null)
    val translatingPage: StateFlow<Int?> = _translatingPage.asStateFlow()

    /** Přeložené bloky pro každou stránku: pageIndex → bloky */
    private val _translatedPages = MutableStateFlow<Map<Int, List<TranslatedBlock>>>(emptyMap())
    val translatedPages: StateFlow<Map<Int, List<TranslatedBlock>>> = _translatedPages.asStateFlow()

    private var currentPageIndex = 0

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
        // Pokud je zapnutý překlad a stránka ještě není přeložena, přeložíme ji
        if (_translateMode.value && _translatedPages.value[index] == null) {
            translatePage(index)
        }
    }

    fun toggleTranslate() {
        val newMode = !_translateMode.value
        _translateMode.value = newMode
        if (newMode && _translatedPages.value[currentPageIndex] == null) {
            translatePage(currentPageIndex)
        }
    }

    private fun translatePage(pageIndex: Int) {
        val pageUrl = _pages.value.getOrNull(pageIndex) ?: return
        if (_translatingPage.value == pageIndex) return

        viewModelScope.launch {
            _translatingPage.value = pageIndex
            try {
                val blocks = translateRepository.translatePage(
                    pageUrl = pageUrl,
                    chapterId = chapterEntityId,
                    pageIndex = pageIndex,
                )
                _translatedPages.value = _translatedPages.value + (pageIndex to blocks)
            } finally {
                _translatingPage.value = null
            }
        }
    }
}

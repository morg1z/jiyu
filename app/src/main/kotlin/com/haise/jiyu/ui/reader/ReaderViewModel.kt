package com.haise.jiyu.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
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
) : ViewModel() {

    private val chapterEntityId: String = checkNotNull(savedStateHandle["chapterId"])

    /** Buď lokální cesty (offline), nebo vzdálené URL (streamed) - UI to nemusí rozlišovat. */
    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            val chapter = repository.getChapter(chapterEntityId)
            if (chapter == null) {
                _loading.value = false
                return@launch
            }

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
        viewModelScope.launch {
            val total = _pages.value.size
            repository.updateReadProgress(chapterEntityId, read = index >= total - 1, lastPageRead = index)
        }
    }
}

package com.haise.jiyu.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import com.haise.jiyu.source.SManga
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MangaDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {

    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])

    private val _manga = MutableStateFlow<MangaEntity?>(null)
    val manga: StateFlow<MangaEntity?> = _manga.asStateFlow()

    val chapters: StateFlow<List<ChapterEntity>> = repository.observeChapters(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _manga.value = repository.getManga(mangaId)
        }
    }

    fun refreshChapters() {
        val current = _manga.value ?: return
        viewModelScope.launch {
            val sManga = SManga(current.sourceId, current.url, current.title, current.coverUrl, current.description, current.status)
            repository.refreshChapters(mangaId, sManga)
        }
    }

    fun downloadChapter(chapter: ChapterEntity) {
        val mangaUrl = _manga.value?.url ?: return
        downloadQueue.enqueue(chapter, mangaUrl)
    }
}

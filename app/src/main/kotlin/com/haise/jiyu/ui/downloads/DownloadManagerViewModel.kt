package com.haise.jiyu.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadGroup(val manga: MangaEntity, val chapters: List<ChapterEntity>)

@HiltViewModel
class DownloadManagerViewModel @Inject constructor(
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {

    val downloadGroups: StateFlow<List<DownloadGroup>> = combine(
        repository.observeNonEmptyDownloads(),
        repository.observeLibrary(),
    ) { chapters, allManga ->
        chapters.groupBy { it.mangaId }
            .mapNotNull { (mangaId, chs) ->
                val manga = allManga.find { it.id == mangaId } ?: return@mapNotNull null
                DownloadGroup(manga, chs.sortedByDescending { it.chapterNumber })
            }
            .sortedBy { it.manga.title }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteChapter(chapter: ChapterEntity) {
        viewModelScope.launch {
            chapter.localPath?.let { path ->
                try { java.io.File(path).deleteRecursively() } catch (_: Exception) {}
            }
            repository.resetDownloadForChapter(chapter.id)
        }
    }

    fun deleteManga(chapters: List<ChapterEntity>) {
        viewModelScope.launch {
            chapters.forEach { chapter ->
                chapter.localPath?.let { path ->
                    try { java.io.File(path).deleteRecursively() } catch (_: Exception) {}
                }
                repository.resetDownloadForChapter(chapter.id)
            }
        }
    }

    fun cancelChapter(chapter: ChapterEntity) {
        viewModelScope.launch {
            downloadQueue.cancel(chapter.id)
            repository.resetDownloadForChapter(chapter.id)
        }
    }

    fun cancelAll(chapters: List<ChapterEntity>) {
        viewModelScope.launch {
            downloadQueue.cancelAll()
            chapters.forEach { repository.resetDownloadForChapter(it.id) }
        }
    }
}

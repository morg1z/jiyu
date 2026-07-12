package com.haise.jiyu.ui.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.ChapterDownloadWorker
import com.haise.jiyu.download.DownloadQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DownloadGroup(val manga: MangaEntity, val chapters: List<ChapterEntity>)

@HiltViewModel
class DownloadManagerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {

    /** chapterId → progress 0..1 for currently running downloads */
    val downloadProgress: StateFlow<Map<String, Float>> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow("jiyu_download")
            .map { infos ->
                infos.filter { it.state == WorkInfo.State.RUNNING }
                    .associate { info ->
                        val chapterId = info.progress.getString(ChapterDownloadWorker.KEY_CHAPTER_ENTITY_ID) ?: ""
                        val progress  = info.progress.getFloat(ChapterDownloadWorker.KEY_PROGRESS, 0f)
                        chapterId to progress
                    }
                    .filterKeys { it.isNotEmpty() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    fun pauseAll() {
        downloadQueue.pauseAll()
        _isPaused.value = true
    }

    fun resumeAll() {
        _isPaused.value = false
        val groups = downloadGroups.value
        viewModelScope.launch {
            groups.forEach { group ->
                group.chapters
                    .filter {
                        it.downloadStatus == DownloadStatus.NOT_DOWNLOADED ||
                        it.downloadStatus == DownloadStatus.ERROR ||
                        it.downloadStatus == DownloadStatus.QUEUED ||
                        it.downloadStatus == DownloadStatus.DOWNLOADING
                    }
                    .forEach { chapter ->
                        repository.setDownloadStatus(chapter.id, DownloadStatus.QUEUED)
                        downloadQueue.enqueue(chapter, group.manga.url)
                    }
            }
        }
    }

    val totalStorageBytes: StateFlow<Long> = downloadGroups.map { groups ->
        groups.sumOf { group ->
            group.chapters.sumOf { chapter ->
                chapter.localPath?.let { path ->
                    try { File(path).walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
                } ?: 0L
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun deleteReadChapters() {
        viewModelScope.launch {
            val allChapters = downloadGroups.value.flatMap { it.chapters }
            allChapters.filter { it.read && it.downloadStatus == DownloadStatus.DOWNLOADED }.forEach { chapter ->
                chapter.localPath?.let { path ->
                    try { File(path).deleteRecursively() } catch (_: Exception) {}
                }
                repository.resetDownloadForChapter(chapter.id)
            }
        }
    }
}

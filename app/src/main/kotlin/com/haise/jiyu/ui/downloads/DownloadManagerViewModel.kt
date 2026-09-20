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
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadGroup(val manga: MangaEntity, val chapters: List<ChapterEntity>)

@HiltViewModel
class DownloadManagerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
        val libraryById = allManga.associateBy { it.id }
        chapters.groupBy { it.mangaId }
            .mapNotNull { (mangaId, chs) ->
                // Stažené kapitoly titulu, který už není v knihovně, se dřív z přehledu ztratily
                // (a zabíraly místo bez možnosti smazání) - dohledá se řádek mimo knihovnu.
                val manga = libraryById[mangaId] ?: repository.getManga(mangaId) ?: return@mapNotNull null
                DownloadGroup(manga, chs.sortedByDescending { it.chapterNumber })
            }
            .sortedBy { it.manga.title }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Nejdřív DB, potom soubory (na IO): když spadne zápis do DB, kapitola zůstane stažená i s
     * daty; opačné pořadí by nechalo "staženou" kapitolu bez souborů. Smazání souborů (u SAF IPC)
     * navíc nesmí běžet na Main.
     */
    private suspend fun resetAndDeleteFiles(chapters: List<ChapterEntity>) {
        chapters.forEach { repository.resetDownloadForChapter(it.id) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            chapters.forEach { chapter ->
                chapter.localPath?.let { path -> ChapterStorage.deleteRecursively(context, path) }
            }
        }
    }

    fun deleteChapter(chapter: ChapterEntity) {
        viewModelScope.launch { resetAndDeleteFiles(listOf(chapter)) }
    }

    fun deleteManga(chapters: List<ChapterEntity>) {
        viewModelScope.launch { resetAndDeleteFiles(chapters) }
    }

    fun cancelChapter(chapter: ChapterEntity) {
        viewModelScope.launch {
            // cancelAndAwait pocka na skutecne zastaveni workeru - bez tohohle mohl worker
            // dokoncit stazeni PO resetu a svym markDownloaded() ho prepsat zpatky (nahlaseny
            // "cancel-vs-success race").
            downloadQueue.cancelAndAwait(chapter.id)
            repository.resetDownloadForChapter(chapter.id)
        }
    }

    fun cancelAll(chapters: List<ChapterEntity>) {
        viewModelScope.launch {
            downloadQueue.cancelAllAndAwait()
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
                chapter.localPath?.let { path -> ChapterStorage.sizeBytes(context, path) } ?: 0L
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO) // rekurzivni pruchod adresari (u SAF IPC) nesmi bezet na Main - audit nalez JIYU-UI-1
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun deleteReadChapters() {
        viewModelScope.launch {
            val allChapters = downloadGroups.value.flatMap { it.chapters }
            resetAndDeleteFiles(allChapters.filter { it.read && it.downloadStatus == DownloadStatus.DOWNLOADED })
        }
    }
}

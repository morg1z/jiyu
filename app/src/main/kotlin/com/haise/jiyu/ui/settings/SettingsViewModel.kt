package com.haise.jiyu.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.haise.jiyu.backup.BackupManager
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.work.ChapterUpdateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ReadingStats(
    val chaptersRead: Int  = 0,
    val pagesRead: Long    = 0L,
    val readingTimeMs: Long = 0L,
)

sealed interface BackupUiState {
    data object Idle    : BackupUiState
    data object Working : BackupUiState
    data class  Success(val message: String) : BackupUiState
    data class  Error(val message: String)   : BackupUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val translatedPageDao: TranslatedPageDao,
    private val repository: MangaRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    val targetLanguage: StateFlow<String> = settings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Czech")

    val theme: StateFlow<String> = settings.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val readingDirection: StateFlow<String> = settings.readingDirection
        .stateIn(viewModelScope, SharingStarted.Eagerly, "ltr")

    val readingMode: StateFlow<String> = settings.readingMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "manga")

    val updateIntervalHours: StateFlow<Long> = settings.updateIntervalHours
        .stateIn(viewModelScope, SharingStarted.Eagerly, 12L)

    private val _cacheCount = MutableStateFlow(0)
    val cacheCount: StateFlow<Int> = _cacheCount.asStateFlow()

    // ── Stažené kapitoly ───��──────────────────────────────────────────────────
    val downloadedCount: StateFlow<Int> = repository.observeDownloadedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ── Statistiky ────────────────────────────────────────────────────────────
    val readingStats: StateFlow<ReadingStats> = combine(
        settings.totalReadingTimeMs,
        settings.totalPagesRead,
    ) { timeMs, pages ->
        val chaptersRead = repository.countReadChapters()
        ReadingStats(chaptersRead, pages, timeMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingStats())

    // ── Záloha ────────────────────────────────────────────────────────────────
    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    // ── Vlastní zdroje (Madara) ────────────────────────────────────────────────
    val customSources: StateFlow<List<CustomSourceEntity>> = repository.observeCustomSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { refreshCacheCount() }

    fun setTargetLanguage(lang: String)  = viewModelScope.launch { settings.setTargetLanguage(lang) }
    fun setTheme(t: String)              = viewModelScope.launch { settings.setTheme(t) }
    fun setReadingDirection(dir: String) = viewModelScope.launch { settings.setReadingDirection(dir) }
    fun setReadingMode(mode: String)     = viewModelScope.launch { settings.setReadingMode(mode) }

    fun setUpdateInterval(hours: Long) = viewModelScope.launch {
        settings.setUpdateIntervalHours(hours)
        val request = PeriodicWorkRequestBuilder<ChapterUpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "chapter_update",
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }

    fun clearTranslationCache() = viewModelScope.launch {
        translatedPageDao.deleteAll()
        _cacheCount.value = 0
    }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        _backupState.value = BackupUiState.Working
        backupManager.exportToUri(uri)
            .onSuccess { _backupState.value = BackupUiState.Success("Záloha exportována") }
            .onFailure { _backupState.value = BackupUiState.Error(it.message ?: "Chyba exportu") }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        _backupState.value = BackupUiState.Working
        backupManager.importFromUri(uri)
            .onSuccess { stats ->
                _backupState.value = BackupUiState.Success(
                    "Obnoveno: ${stats.mangaCount} manga, ${stats.chapterCount} kapitol"
                )
            }
            .onFailure { _backupState.value = BackupUiState.Error(it.message ?: "Chyba importu") }
    }

    fun clearBackupState() { _backupState.value = BackupUiState.Idle }

    fun deleteAllDownloads() = viewModelScope.launch {
        val chapters = repository.getAllLibraryChapters()
        chapters.filter { it.downloadStatus == DownloadStatus.DOWNLOADED }.forEach { chapter ->
            chapter.localPath?.let { path ->
                try { java.io.File(path).deleteRecursively() } catch (_: Exception) {}
            }
        }
        repository.clearAllDownloaded()
    }

    private fun refreshCacheCount() = viewModelScope.launch {
        _cacheCount.value = translatedPageDao.count()
    }

    fun addCustomSource(
        name: String,
        baseUrl: String,
        listItemSelector: String? = null,
        titleLinkSelector: String? = null,
        descriptionSelector: String? = null,
        statusSelector: String? = null,
        chapterListSelector: String? = null,
        pageImageSelector: String? = null,
    ) = viewModelScope.launch {
        repository.addCustomSource(
            name, baseUrl,
            listItemSelector, titleLinkSelector, descriptionSelector,
            statusSelector, chapterListSelector, pageImageSelector,
        )
    }

    fun deleteCustomSource(source: CustomSourceEntity) = viewModelScope.launch {
        repository.deleteCustomSource(source)
    }
}

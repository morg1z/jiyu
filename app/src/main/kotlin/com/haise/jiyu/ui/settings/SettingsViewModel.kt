package com.haise.jiyu.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.haise.jiyu.data.backup.TachiyomiBackupImporter
import com.haise.jiyu.data.backup.TachiyomiImportResult
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.data.tracking.MalAuthManager
import com.haise.jiyu.data.tracking.MalRepository
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
import com.haise.jiyu.source.madara.MadaraSelectors
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.catalog.CatalogSource
import com.haise.jiyu.source.catalog.SourceCatalogManager
import com.haise.jiyu.util.toFriendlyMessage
import com.haise.jiyu.work.ChapterUpdateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

sealed interface SourceTestState {
    data object Idle    : SourceTestState
    data object Testing : SourceTestState
    data class  Success(val count: Int) : SourceTestState
    data class  Failure(val message: String) : SourceTestState
}

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
    private val okHttpClient: OkHttpClient,
    private val catalogManager: SourceCatalogManager,
    private val tachiyomiBackupImporter: TachiyomiBackupImporter,
    private val malAuthManager: MalAuthManager,
    private val malRepository: MalRepository,
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

    private val _cacheCount = MutableStateFlow(0)
    val cacheCount: StateFlow<Int> = _cacheCount.asStateFlow()

    // ── Stažené kapitoly ───��──────────────────────────────────────────────────
    val downloadedCount: StateFlow<Int> = repository.observeDownloadedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ── Statistiky ────────────────────────────────────────────────────────────
    val readingStats: StateFlow<ReadingStats> = combine(
        settings.totalReadingTimeMs,
        settings.totalPagesRead,
        repository.observeReadChaptersCount(),
    ) { timeMs, pages, chaptersRead ->
        ReadingStats(chaptersRead, pages, timeMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingStats())

    // ── Záloha ────────────────────────────────────────────────────────────────
    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    // ── Vlastní zdroje (Madara) ────────────────────────────────────────────────
    val customSources: StateFlow<List<CustomSourceEntity>> = repository.observeCustomSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sourceTestState = MutableStateFlow<SourceTestState>(SourceTestState.Idle)
    val sourceTestState: StateFlow<SourceTestState> = _sourceTestState.asStateFlow()

    // ── MAL OAuth ────────────────────────────────────────────────────────────
    val malIsLoggedIn: StateFlow<Boolean> = malAuthManager.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _malUsername = MutableStateFlow("")
    val malUsername: StateFlow<String> = _malUsername.asStateFlow()

    init {
        refreshCacheCount()
        viewModelScope.launch {
            malAuthManager.isLoggedIn.collect { loggedIn ->
                if (loggedIn) {
                    val profile = malRepository.getUserProfile()
                    _malUsername.value = profile?.optString("name") ?: ""
                } else {
                    _malUsername.value = ""
                }
            }
        }
    }

    fun setTargetLanguage(lang: String)  = viewModelScope.launch { settings.setTargetLanguage(lang) }
    fun setTheme(t: String)              = viewModelScope.launch { settings.setTheme(t) }
    fun setReadingDirection(dir: String) = viewModelScope.launch { settings.setReadingDirection(dir) }
    fun setReadingMode(mode: String)     = viewModelScope.launch { settings.setReadingMode(mode) }
    fun setTapZonesEnabled(enabled: Boolean)      = viewModelScope.launch { settings.setTapZonesEnabled(enabled) }
    fun setTapZoneLeftFraction(fraction: Float)   = viewModelScope.launch { settings.setTapZoneLeftFraction(fraction) }
    fun setTapZoneRightFraction(fraction: Float)  = viewModelScope.launch { settings.setTapZoneRightFraction(fraction) }
    fun setWebtoonScrollSpeed(speed: Float)       = viewModelScope.launch { settings.setWebtoonScrollSpeed(speed) }
    fun setReaderTextScale(scale: Float)          = viewModelScope.launch { settings.setReaderTextScale(scale) }
    fun setDoublePageSpread(enabled: Boolean) = viewModelScope.launch { settings.setDoublePageSpread(enabled) }

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

    // ── Tachiyomi/Mihon import ────────────────────────────────────────────────
    private val _tachyImportResult = MutableStateFlow<TachiyomiImportResult?>(null)
    val tachyImportResult: StateFlow<TachiyomiImportResult?> = _tachyImportResult.asStateFlow()

    private val _tachyImportInProgress = MutableStateFlow(false)
    val tachyImportInProgress: StateFlow<Boolean> = _tachyImportInProgress.asStateFlow()

    fun importTachiyomiBackup(uri: Uri) {
        viewModelScope.launch {
            _tachyImportInProgress.value = true
            _tachyImportResult.value = tachiyomiBackupImporter.importFromUri(context, uri)
            _tachyImportInProgress.value = false
        }
    }

    fun clearTachyImportResult() { _tachyImportResult.value = null }

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
        contentType: String = "MANGA",
    ) = viewModelScope.launch {
        repository.addCustomSource(
            name, baseUrl,
            listItemSelector, titleLinkSelector, descriptionSelector,
            statusSelector, chapterListSelector, pageImageSelector,
            contentType,
        )
    }

    fun deleteCustomSource(source: CustomSourceEntity) = viewModelScope.launch {
        repository.deleteCustomSource(source)
    }

    /** Zkusí načíst "populární" seznam ze zadané URL/selektorů bez uložení zdroje - ověří, že selektory na webu vůbec něco najdou. */
    fun testCustomSource(
        baseUrl: String,
        listItemSelector: String?,
        titleLinkSelector: String?,
        descriptionSelector: String?,
        statusSelector: String?,
        chapterListSelector: String?,
        pageImageSelector: String?,
    ) = viewModelScope.launch {
        _sourceTestState.value = SourceTestState.Testing
        try {
            val defaults = MadaraSelectors.DEFAULT
            val selectors = MadaraSelectors(
                listItem = listItemSelector?.ifBlank { null } ?: defaults.listItem,
                titleLink = titleLinkSelector?.ifBlank { null } ?: defaults.titleLink,
                description = descriptionSelector?.ifBlank { null } ?: defaults.description,
                status = statusSelector?.ifBlank { null } ?: defaults.status,
                chapterList = chapterListSelector?.ifBlank { null } ?: defaults.chapterList,
                pageImage = pageImageSelector?.ifBlank { null } ?: defaults.pageImage,
            )
            val testSource = MadaraSource(id = "test", name = "Test", baseUrl = baseUrl, client = okHttpClient, selectors = selectors)
            val results = testSource.getPopular(1)
            _sourceTestState.value = if (results.isNotEmpty()) {
                SourceTestState.Success(results.size)
            } else {
                SourceTestState.Failure("Nenalezeny žádné položky - zkontroluj adresu nebo selektory")
            }
        } catch (e: Exception) {
            _sourceTestState.value = SourceTestState.Failure(e.toFriendlyMessage())
        }
    }

    fun clearSourceTestState() { _sourceTestState.value = SourceTestState.Idle }

    fun startMalOAuth(openBrowser: (Uri) -> Unit) = viewModelScope.launch {
        val clientId = BuildConfig.MAL_CLIENT_ID.takeIf { it.isNotBlank() } ?: return@launch
        val uri = malAuthManager.startOAuthFlow(clientId)
        openBrowser(uri)
    }

    fun malLogout() = viewModelScope.launch { malAuthManager.logout() }

    // ── Katalog zdrojů ───────────────────────────────────────────────────────
    fun getCatalog(): List<CatalogSource> = catalogManager.catalog

    fun isCatalogSourceInstalled(source: CatalogSource): Boolean =
        customSources.value.any { it.baseUrl.trimEnd('/') == source.baseUrl.trimEnd('/') }

    fun installCatalogSource(source: CatalogSource) = viewModelScope.launch {
        if (!isCatalogSourceInstalled(source)) {
            repository.addCustomSource(
                name = source.name,
                baseUrl = source.baseUrl,
                listItemSelector = null,
                titleLinkSelector = null,
                descriptionSelector = null,
                statusSelector = null,
                chapterListSelector = null,
                pageImageSelector = null,
            )
        }
    }

    // ── Auto-mazání stažených ─────────────────────────────────────────────────
    val autoDeleteRead: StateFlow<Boolean> = settings.autoDeleteRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoDeleteDelayDays: StateFlow<Int> = settings.autoDeleteDelayDays
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setAutoDeleteRead(enabled: Boolean) = viewModelScope.launch { settings.setAutoDeleteRead(enabled) }
    fun setAutoDeleteDelayDays(days: Int)    = viewModelScope.launch { settings.setAutoDeleteDelayDays(days) }

    val downloadOnlyWifi: StateFlow<Boolean> = settings.downloadOnlyWifi
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setDownloadOnlyWifi(enabled: Boolean) = viewModelScope.launch { settings.setDownloadOnlyWifi(enabled) }

    // ── Automatický přechod na další kapitolu ─────────────────────────────────
    val autoNextChapter: StateFlow<Boolean> = settings.autoNextChapter
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoNextChapter(enabled: Boolean) = viewModelScope.launch { settings.setAutoNextChapter(enabled) }

    // ── Složka stahování ─────────────────────────────────────────────────────
    val downloadFolderUri: StateFlow<String?> = settings.downloadFolderUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setDownloadFolderUri(uri: String?) = viewModelScope.launch { settings.setDownloadFolderUri(uri) }

    // ── Jazyk aplikace ────────────────────────────────────────────────────────
    fun setLanguage(tag: String) {
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        )
    }

    // ── Ořez okrajů stránek ───────────────────────────────────────────────────
    val cropBorders: StateFlow<Boolean> = settings.cropBorders
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setCropBorders(enabled: Boolean) = viewModelScope.launch { settings.setCropBorders(enabled) }

    // ── Čtečka — fullscreen & téma ────────────────────────────────────────────
    val fullscreenEnabled: StateFlow<Boolean> = settings.fullscreenEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val readerTheme: StateFlow<String> = settings.readerTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "dark")

    val oledMode: StateFlow<Boolean> = settings.oledMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFullscreenEnabled(enabled: Boolean) = viewModelScope.launch { settings.setFullscreenEnabled(enabled) }
    fun setReaderTheme(theme: String)           = viewModelScope.launch { settings.setReaderTheme(theme) }
    fun setOledMode(enabled: Boolean)           = viewModelScope.launch { settings.setOledMode(enabled) }

    // ── Přiblížení stránek ────────────────────────────────────────────────────
    val pageScale: StateFlow<String> = settings.pageScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fit_width")

    fun setPageScale(scale: String) = viewModelScope.launch { settings.setPageScale(scale) }

    // ── Auto-záloha ───────────────────────────────────────────────────────────
    val autoBackupEnabled: StateFlow<Boolean> = settings.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoBackupEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setAutoBackupEnabled(enabled)
        if (enabled) scheduleAutoBackup() else WorkManager.getInstance(context).cancelUniqueWork("auto_backup")
    }

    private fun scheduleAutoBackup() {
        val request = PeriodicWorkRequestBuilder<com.haise.jiyu.work.AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "auto_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    // ── Uložená vyhledávání ───────────────────────────────────────────────────
    val savedSearches: StateFlow<List<String>> = settings.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addSavedSearch(query: String) = viewModelScope.launch { settings.addSavedSearch(query) }
    fun removeSavedSearch(query: String) = viewModelScope.launch { settings.removeSavedSearch(query) }
}

package com.haise.jiyu.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Stav stahování aktualizace zobrazovaný přímo v appce (viz [ApkUpdateInstaller.observeProgress]). */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    /** [progress] 0-100, nebo -1 dokud DownloadManager nezná celkovou velikost souboru. */
    data class Downloading(val progress: Int) : UpdateDownloadState
    data object ReadyToInstall : UpdateDownloadState
    data object Failed : UpdateDownloadState
}

/**
 * Stažení a instalace aktualizace přes systémový DownloadManager - appka není na Play
 * Storu, takže update musí projít stejnou cestou jako ruční sideload: uživatel musí
 * povolit instalaci z tohoto zdroje a stažené APK potvrdit v systémovém instalátoru.
 *
 * Stav stahování ([downloadState]) žije tady, ne ve SettingsViewModelu - Singleton
 * (ne ViewModel vázaný na jednu obrazovku), aby ho mohl sledovat i globální overlay
 * (viz [com.haise.jiyu.update.UpdateProgressOverlay] v MainActivity) a stahování se
 * dál sledovalo i po odchodu z Nastavení.
 */
@Singleton
class ApkUpdateInstaller @Inject constructor() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var downloadJob: Job? = null

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    /**
     * Nezávislé na [downloadState] - uživatel může celoobrazovkový overlay schovat
     * (viz [dismissOverlay]), i když stahování na pozadí dál běží (DownloadManager na
     * app procesu nezávisí). Nastavení pak dál ukazuje skutečný postup přes [downloadState].
     */
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    /**
     * Zařadí stažení do DownloadManageru, sleduje postup do [downloadState] a po
     * dokončení rovnou otevře systémový instalátor balíčků.
     */
    fun startDownload(context: Context, apkUrl: String, version: String) {
        if (_downloadState.value is UpdateDownloadState.Downloading) return
        downloadJob?.cancel()
        _downloadState.value = UpdateDownloadState.Downloading(0)
        _overlayVisible.value = true
        downloadJob = scope.launch {
            val downloadId = enqueueDownload(context, apkUrl, version)
            observeProgress(context, downloadId).collect { state ->
                _downloadState.value = state
                if (state is UpdateDownloadState.ReadyToInstall) {
                    installDownloaded(context, downloadId)
                }
            }
        }
    }

    /** Skryje overlay bez zrušení stahování (to dál běží v systémovém DownloadManageru). */
    fun dismissOverlay() {
        _overlayVisible.value = false
    }

    /** false = uživatel ještě nepovolil appce instalovat balíčky (Android 8+). */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Otevře systémové nastavení, kde uživatel povolí instalaci z této appky. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
        context.startActivity(intent)
    }

    /** Zařadí stažení APK do systémového DownloadManageru a vrátí jeho ID pro sledování postupu. */
    fun enqueueDownload(context: Context, apkUrl: String, version: String): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Jiyu $version")
            .setDescription("Stahování aktualizace")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "jiyu-update.apk")
        return manager.enqueue(request)
    }

    /**
     * Sleduje postup stahování pollingem DownloadManageru - ten sám žádné Flow/callback
     * API nenabízí, takže se dotazuje jednou za 300 ms, dokud stahování neskončí úspěchem
     * nebo chybou. Umožňuje appce ukázat progress bar přímo v UI místo pouhého spoléhání
     * na systémovou notifikaci.
     */
    fun observeProgress(context: Context, downloadId: Long): Flow<UpdateDownloadState> = flow {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            cursor.use {
                if (!it.moveToFirst()) {
                    emit(UpdateDownloadState.Failed)
                    return@flow
                }
                when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        emit(UpdateDownloadState.ReadyToInstall)
                        return@flow
                    }
                    DownloadManager.STATUS_FAILED -> {
                        emit(UpdateDownloadState.Failed)
                        return@flow
                    }
                    else -> {
                        val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        emit(UpdateDownloadState.Downloading(progress))
                    }
                }
            }
            delay(300)
        }
    }

    /** Otevře systémový instalátor balíčků nad staženým APK - uživatel jen potvrdí instalaci. */
    fun installDownloaded(context: Context, downloadId: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}

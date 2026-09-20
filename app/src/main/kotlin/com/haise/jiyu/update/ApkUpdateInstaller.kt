package com.haise.jiyu.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.report
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Stav stahování aktualizace zobrazovaný přímo v appce (viz [ApkUpdateInstaller.observeProgress]). */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    /** [progress] 0-100, nebo -1 dokud DownloadManager nezná celkovou velikost souboru. */
    data class Downloading(val progress: Int) : UpdateDownloadState
    data object ReadyToInstall : UpdateDownloadState
    /** [reason] je [DownloadManager.COLUMN_REASON] - viz [AboutSettingsScreen] pro mapování
     * na čitelnou hlášku (nedostatek místa/síť), jinak obecné "nepovedlo se". */
    data class Failed(val reason: Int?) : UpdateDownloadState
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
class ApkUpdateInstaller @Inject constructor(
    private val settings: SettingsRepository,
) {

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
     * dokončení - pokud [expectedSha256] sedí (nebo release žádný digest neposkytl,
     * viz [extractSha256FromReleaseNotes]) - otevře systémový instalátor balíčků.
     */
    fun startDownload(context: Context, apkUrl: String, version: String, expectedSha256: String?) {
        if (_downloadState.value is UpdateDownloadState.Downloading) return
        downloadJob?.cancel()
        _downloadState.value = UpdateDownloadState.Downloading(0)
        _overlayVisible.value = true
        downloadJob = scope.launch {
            // Celý blok je záměrně v try/catch: `scope` běží na Dispatchers.Main bez
            // vlastního CoroutineExceptionHandler, takže cokoli neočekávané tady (např.
            // verifyIntegrity narazí na soubor smazaný/přesunutý mezitím jinou appkou -
            // nahlášený pád appky přímo během updatu, reprodukovatelný jen při přechodu
            // ze starší verze, ne na čerstvé instalaci) by jinak spadlo jako neošetřená
            // výjimka na hlavním vlákně a shodilo celou appku, místo aby update prostě
            // skončil jako Failed a šel zopakovat.
            try {
                val downloadId = enqueueDownload(context, apkUrl, version)
                settings.setPendingUpdateDownloadId(downloadId)
                observeProgress(context, downloadId).collect { state ->
                    when (state) {
                        is UpdateDownloadState.ReadyToInstall -> {
                            if (verifyIntegrity(context, expectedSha256)) {
                                settings.setPendingUpdateDownloadId(null)
                                _downloadState.value = state
                                installDownloaded(context, downloadId)
                            } else {
                                deleteDownloadedApk(context)
                                settings.setPendingUpdateDownloadId(null)
                                _downloadState.value = UpdateDownloadState.Failed(reason = REASON_INTEGRITY_CHECK_FAILED)
                            }
                        }
                        is UpdateDownloadState.Failed -> {
                            settings.setPendingUpdateDownloadId(null)
                            _downloadState.value = state
                        }
                        else -> _downloadState.value = state
                    }
                }
            } catch (e: Exception) {
                e.report("update:startDownload")
                settings.setPendingUpdateDownloadId(null)
                _downloadState.value = UpdateDownloadState.Failed(reason = null)
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

    /**
     * Zařadí stažení APK do systémového DownloadManageru a vrátí jeho ID pro sledování postupu.
     *
     * Cílový soubor má vždy stejné jméno ("jiyu-update.apk") - DownloadManager na Androidu
     * ale stahování rovnou odmítne (ERROR_FILE_ALREADY_EXISTS), pokud tam z předchozího
     * pokusu (i úspěšného, co appka po instalaci nikdy neuklidila) už soubor leží. Uživatel
     * pak vidí jen obecné "nepovedlo se" bez zjevného důvodu - proto se starý soubor před
     * každým novým pokusem smaže.
     *
     * Předchozí sledované stahování (viz [SettingsRepository.pendingUpdateDownloadId]) se
     * navíc přes `manager.remove()` uklidí PŘED založením nového - bez tohohle by restart
     * appky uprostřed stahování (proces zabit/OOM) resetoval [downloadState] na `Idle`
     * (jen in-memory), ale původní DownloadManager záznam by zůstal běžet dál. Nový pokus
     * by pod ním smazal cílový soubor výše a založil kolidující druhé stahování nad stejným
     * targetem (nahlášený bug) - a starší dokončená stahování se navíc nikdy neuklízela.
     */
    suspend fun enqueueDownload(context: Context, apkUrl: String, version: String): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        settings.pendingUpdateDownloadId.first()?.let { oldId ->
            try { manager.remove(oldId) } catch (_: Exception) {}
        }

        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("jiyu-update.apk")
            ?.takeIf { it.exists() }
            ?.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Jiyu $version")
            .setDescription("Stahování aktualizace")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "jiyu-update.apk")
        return manager.enqueue(request)
    }

    /**
     * Ověří SHA-256 staženého APK proti [expectedSha256] z release poznámek - appka není na
     * Play Storu, takže tenhle krok je jediná kontrola integrity mezi GitHub Release assetem
     * a systémovým instalátorem (ten sám kontroluje jen shodu podpisu s už nainstalovanou
     * appkou, ne obsah stahovaného souboru). `null` (starší release bez digestu v poznámkách)
     * kontrolu z důvodu zpětné kompatibility přeskočí.
     */
    private suspend fun verifyIntegrity(context: Context, expectedSha256: String?): Boolean {
        if (expectedSha256 == null) return true
        return withContext(Dispatchers.IO) {
            val file = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve("jiyu-update.apk")
            if (file == null || !file.exists()) return@withContext false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedSha256, ignoreCase = true)
        }
    }

    private fun deleteDownloadedApk(context: Context) {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve("jiyu-update.apk")?.delete()
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
                    emit(UpdateDownloadState.Failed(reason = null))
                    return@flow
                }
                when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        emit(UpdateDownloadState.ReadyToInstall)
                        return@flow
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        emit(UpdateDownloadState.Failed(reason = reason))
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
    }.flowOn(Dispatchers.IO)

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

    companion object {
        /** Sentinel pro [UpdateDownloadState.Failed.reason] - neshoda SHA-256. Záporná
         * hodnota nekoliduje se skutečnými `DownloadManager.COLUMN_REASON` kódy (ty jsou
         * všechny kladné zdokumentované konstanty). Viz [AboutSettingsScreen] pro mapování
         * na čitelnou hlášku. */
        const val REASON_INTEGRITY_CHECK_FAILED = -1
    }
}

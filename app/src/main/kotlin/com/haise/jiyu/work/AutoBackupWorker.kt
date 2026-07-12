package com.haise.jiyu.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haise.jiyu.backup.BackupManager
import com.haise.jiyu.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val folderUri = settings.backupFolderUri.first()
            if (folderUri != null) backupToSaf(folderUri) else backupToAppStorage()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** Zálohuje do uživatelem vybrané SAF složky (může být lokálně synchronizovaná cloudová složka). */
    private suspend fun backupToSaf(folderUriString: String) {
        val treeUri = Uri.parse(folderUriString)
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return backupToAppStorage()
        val fileName = "jiyu_auto_${LocalDate.now()}.json"

        // Smaž staré auto-zálohy, ponech 3 nejnovější
        val existing = dir.listFiles()
            .filter { it.name?.startsWith("jiyu_auto_") == true }
            .sortedByDescending { it.lastModified() }
        existing.drop(2).forEach { it.delete() }

        val newFile = dir.createFile("application/json", fileName) ?: return backupToAppStorage()
        backupManager.exportToUri(newFile.uri)
    }

    private suspend fun backupToAppStorage() {
        val dir = File(context.getExternalFilesDir(null), "backups").also { it.mkdirs() }
        val file = File(dir, "jiyu_auto_${LocalDate.now()}.json")
        backupManager.exportToFile(file)
        // Keep only the 3 most recent auto-backups
        dir.listFiles()
            ?.filter { it.name.startsWith("jiyu_auto_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
    }
}

package com.haise.jiyu.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haise.jiyu.backup.BackupManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.time.LocalDate

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dir = File(context.getExternalFilesDir(null), "backups").also { it.mkdirs() }
            val file = File(dir, "jiyu_auto_${LocalDate.now()}.json")
            backupManager.exportToFile(file)
            // Keep only the 3 most recent auto-backups
            dir.listFiles()
                ?.filter { it.name.startsWith("jiyu_auto_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { it.delete() }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

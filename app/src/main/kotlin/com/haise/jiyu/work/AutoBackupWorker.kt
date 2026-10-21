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
            val exported = if (folderUri != null) backupToSaf(folderUri) else backupToAppStorage()
            // exportToUri/exportToFile vraci Result - drive se ignoroval, takze selhany
            // export zanechal prazdny/oklesteny soubor, ktery se pocital jako validni
            // zaloha a rotace smazala starsi funkcni (audit #38).
            if (!exported) {
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            Result.success()
        } catch (e: Exception) {
            // Zruseni nesmi prejit do retry (audit #7).
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Trvalá chyba (např. uživatel odebral oprávnění k SAF složce) by se jinak
            // opakovala navždy při každém periodickém běhu - stejný strop jako SyncWorker.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /** Zálohuje do uživatelem vybrané SAF složky (může být lokálně synchronizovaná cloudová složka).
     *  @return true jen když export dobehl - jinak se rotace vůbec nespustí. */
    private suspend fun backupToSaf(folderUriString: String): Boolean {
        val treeUri = Uri.parse(folderUriString)
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return backupToAppStorage()
        val fileName = "jiyu_auto_${LocalDate.now()}.json"

        // Na rozdíl od obyčejného File (viz backupToAppStorage) SAF createFile() při kolizi
        // jména NEPŘEPÍŠE existující soubor, ale vytvoří rozlišený duplikát ("jiyu_auto_2026-...
        // (1).json") - víc běhů workeru za den by tak bez tohohle smazání nechalo narůstat
        // neomezené množství záloh se stejným dnešním datem.
        dir.findFile(fileName)?.delete()
        val newFile = dir.createFile("application/json", fileName) ?: return backupToAppStorage()
        if (backupManager.exportToUri(newFile.uri).isFailure) {
            // Oklesteny/prazdny soubor po selhanem exportu nesmi zustat jako "zaloha".
            newFile.delete()
            return false
        }

        // Rotace AŽ PO úspěšném zápisu nové zálohy - dřív běžela před vytvořením nového
        // souboru, takže případná chyba exportu (zaplněná složka, ztracené oprávnění) mohla
        // smazat staré zálohy dřív, než bylo jisté, že nová vůbec existuje.
        dir.listFiles()
            .filter { it.name?.startsWith("jiyu_auto_") == true }
            .sortedByDescending { it.lastModified() }
            .drop(3)
            .forEach { it.delete() }
        return true
    }

    private suspend fun backupToAppStorage(): Boolean {
        val dir = File(context.getExternalFilesDir(null), "backups").also { it.mkdirs() }
        val file = File(dir, "jiyu_auto_${LocalDate.now()}.json")
        if (backupManager.exportToFile(file).isFailure) return false
        // Keep only the 3 most recent auto-backups
        dir.listFiles()
            ?.filter { it.name.startsWith("jiyu_auto_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
        return true
    }
}

package com.haise.jiyu.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.haise.jiyu.sync.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sdílené mezi [com.haise.jiyu.ui.account.AccountViewModel] (volá se po úspěšném přihlášení)
 * a [com.haise.jiyu.JiyuApp] (volá se při KAŽDÉM startu appky, pokud je uživatel přihlášený) -
 * `enqueueUniquePeriodicWork` s `KEEP` politikou je bezpečné volat opakovaně, nic nezdvojí.
 * Bez volání z `JiyuApp` by reinstall/vymazání dat appky (smazaná WorkManager DB, ale
 * uživatel zůstal přihlášený přes perzistentní token) navždy ztratilo periodickou
 * synchronizaci, dokud by se uživatel ručně neodhlásil a nepřihlásil znovu - appka nemá
 * žádný BOOT_COMPLETED receiver, spoléhá čistě na WorkManager vlastní přežití rebootu.
 */
object SyncScheduler {
    const val WORK_NAME = "cloud_sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        syncRepository.pushToCloud()
        // Drive tenhle worker dela jen push - druhe zarizeni se tak samo od sebe nikdy
        // nedorovnalo, dokud uzivatel rucne nezmackl "Sync now" (AccountViewModel.syncNow(),
        // ktery uz push+pull dela oba). pullFromCloud() uz ma spravne vyresene LWW
        // (viz SyncRepository.mergeWithRemote) - stejne poradi jako syncNow().
        syncRepository.pullFromCloud()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

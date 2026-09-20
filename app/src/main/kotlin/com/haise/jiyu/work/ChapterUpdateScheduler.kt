package com.haise.jiyu.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Jediné místo, které plánuje periodickou kontrolu nových kapitol. Sdílí ho [com.haise.jiyu.JiyuApp]
 * (start appky, `KEEP` - nezmění už běžící plán) a nastavení (změna intervalu, `CANCEL_AND_REENQUEUE`);
 * dřív měly každý vlastní kopii requestu a název práce jako řetězec.
 */
object ChapterUpdateScheduler {
    const val WORK_NAME = "chapter_update"
    const val DEFAULT_INTERVAL_HOURS = 12L

    fun schedule(
        context: Context,
        intervalHours: Long = DEFAULT_INTERVAL_HOURS,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ) {
        val request = PeriodicWorkRequestBuilder<ChapterUpdateWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }
}

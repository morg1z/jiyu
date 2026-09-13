package com.haise.jiyu.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun enqueue(chapter: ChapterEntity, mangaUrl: String) {
        val wifiOnly = settings.downloadOnlyWifi.first()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val data = Data.Builder()
            .putString(ChapterDownloadWorker.KEY_CHAPTER_ENTITY_ID, chapter.id)
            .putString(ChapterDownloadWorker.KEY_SOURCE_ID, chapter.sourceId)
            .putString(ChapterDownloadWorker.KEY_CHAPTER_URL, chapter.url)
            .putString(ChapterDownloadWorker.KEY_MANGA_URL, mangaUrl)
            .build()

        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build()
            )
            // Explicitni misto spolehnuti na WorkManager default - stejna hodnota jako
            // scheduleChapterUpdates() v JiyuApp.kt, konzistentni napric appkou.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag("download_${chapter.id}")
            .addTag("jiyu_download")
            .build()

        // enqueueUniqueWork + KEEP: bez tohohle by dvojtap na "Stáhnout", souběžný
        // auto-download z ChapterUpdateWorker a ruční stažení ze stejné kapitoly, nebo
        // opětovné zavolání enqueue() po restartu workeru mohly spustit DVA
        // ChapterDownloadWorker souběžně nad stejnou kapitolou - oba by zapisovaly do
        // stejných souborů stránek najednou. KEEP nechá už běžící/frontou čekající
        // stažení v klidu doběhnout a novou práci nezaloží; jakmile skončí (úspěchem i
        // chybou), další enqueue už projde normálně (typicky ruční retry).
        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_${chapter.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(chapterId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("download_$chapterId")
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag("jiyu_download")
    }

    fun pauseAll() = cancelAll()

    /**
     * `cancel()`/`cancelAll()` jen ODESLOU zruseni - WorkManager ho zpracuje asynchronne,
     * nikoliv okamzite. Kdyz volajici hned po nem prepise DB stav (napr. na NOT_DOWNLOADED),
     * muze prave bezici worker mezitim doskocit do uspesneho konce a svym markDownloaded()
     * ten reset prepsat zpatky (nahlaseny "cancel-vs-success race"). Tahle varianta pocka,
     * az WorkInfo pro dany tag skutecne prejde do finalniho stavu, nez volajici zapise DB -
     * bez potreby DB migrace/generation counteru. Bounded timeout jako zachranna sit, kdyby
     * WorkManager stav z nejakeho duvodu nikdy nedorazil (UI akce nesmi viset navzdy).
     */
    private suspend fun cancelTagAndAwait(tag: String, timeoutMs: Long = 5000) {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(tag)
        withTimeoutOrNull(timeoutMs) {
            wm.getWorkInfosByTagFlow(tag).first { infos -> infos.all { it.state.isFinished } }
        }
    }

    suspend fun cancelAndAwait(chapterId: String) = cancelTagAndAwait("download_$chapterId")

    suspend fun cancelAllAndAwait() = cancelTagAndAwait("jiyu_download")
}

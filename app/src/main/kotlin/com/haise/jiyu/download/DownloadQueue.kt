package com.haise.jiyu.download

import android.content.Context
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
}

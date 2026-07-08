package com.haise.jiyu.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.haise.jiyu.data.db.entity.ChapterEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(chapter: ChapterEntity, mangaUrl: String) {
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
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("download_${chapter.id}")
            .addTag("jiyu_download")
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel(chapterId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("download_$chapterId")
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag("jiyu_download")
    }

    /** Pozastaví frontu: zruší WorkManager tasks bez resetování stavu v DB. */
    fun pauseAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag("jiyu_download")
    }
}

package com.haise.jiyu.download

import android.content.Context
import androidx.work.Data
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
            .addTag("download_${chapter.id}")
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}

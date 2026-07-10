package com.haise.jiyu.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class AutoDeleteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return Result.failure()
        val chapter = repository.getChapter(chapterId) ?: return Result.success()
        if (chapter.read && chapter.downloadStatus == DownloadStatus.DOWNLOADED) {
            chapter.localPath?.let { path ->
                try { java.io.File(path).deleteRecursively() } catch (_: Exception) {}
            }
            repository.resetDownloadForChapter(chapterId)
        }
        return Result.success()
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"

        fun schedule(context: Context, chapterId: String, delayDays: Long) {
            val data = Data.Builder().putString(KEY_CHAPTER_ID, chapterId).build()
            val request = OneTimeWorkRequestBuilder<AutoDeleteWorker>()
                .setInputData(data)
                .setInitialDelay(delayDays, TimeUnit.DAYS)
                .addTag("auto_delete")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

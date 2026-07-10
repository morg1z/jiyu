package com.haise.jiyu.download

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.work.CHANNEL_ID
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

const val CHANNEL_DOWNLOADS = "channel_downloads"

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
    private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val chapterEntityId = inputData.getString(KEY_CHAPTER_ENTITY_ID) ?: return@withContext Result.failure()
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return@withContext Result.failure()
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return@withContext Result.failure()
        val mangaUrl = inputData.getString(KEY_MANGA_URL) ?: return@withContext Result.failure()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val progressId = chapterEntityId.hashCode() xor 0x1000

        repository.setDownloadStatus(chapterEntityId, DownloadStatus.DOWNLOADING)

        nm.notify(progressId, NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stahování kapitoly")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build())

        try {
            val pages = repository.getChapterPages(sourceId, chapterUrl, mangaUrl)
            val chapterDir = File(applicationContext.filesDir, "downloads/$chapterEntityId")
            chapterDir.mkdirs()

            pages.forEachIndexed { index, page ->
                val imageUrl = page.imageUrl ?: page.url
                val bytes = downloadBytes(imageUrl)
                val extension = imageUrl.substringAfterLast('.', "jpg").take(4)
                File(chapterDir, "%03d.%s".format(index, extension)).writeBytes(bytes)
                nm.notify(progressId, NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Stahování kapitoly")
                    .setContentText("${index + 1} / ${pages.size} stránek")
                    .setProgress(pages.size, index + 1, false)
                    .setOngoing(true)
                    .build())
            }

            nm.cancel(progressId)
            repository.markDownloaded(chapterEntityId, chapterDir.absolutePath, pages.size)
            notifyDone(chapterEntityId)
            Result.success()
        } catch (e: CancellationException) {
            nm.cancel(progressId)
            repository.setDownloadStatus(chapterEntityId, DownloadStatus.NOT_DOWNLOADED)
            throw e
        } catch (e: Exception) {
            nm.cancel(progressId)
            repository.setDownloadStatus(chapterEntityId, DownloadStatus.ERROR)
            Result.failure()
        }
    }

    private fun notifyDone(chapterId: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Stahování dokončeno")
            .setContentText("Kapitola je připravena pro offline čtení")
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(chapterId.hashCode(), notification)
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Stažení selhalo: $url")
            return response.body?.bytes() ?: throw IllegalStateException("Prázdná odpověď: $url")
        }
    }

    companion object {
        const val KEY_CHAPTER_ENTITY_ID = "chapter_entity_id"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_MANGA_URL = "manga_url"
    }
}

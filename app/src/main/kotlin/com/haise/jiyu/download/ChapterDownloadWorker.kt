package com.haise.jiyu.download

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.work.CHANNEL_ID
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val CHANNEL_DOWNLOADS = "channel_downloads"

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val chapterEntityId = inputData.getString(KEY_CHAPTER_ENTITY_ID) ?: return Result.failure()
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure()
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return Result.failure()
        val mangaUrl = inputData.getString(KEY_MANGA_URL) ?: return Result.failure()

        val parallelLimit = settings.parallelDownloads.first().coerceIn(1, 5)
        val semaphore = getSemaphore(parallelLimit)
        semaphore.acquire()
        try {
            return doDownload(chapterEntityId, sourceId, chapterUrl, mangaUrl)
        } finally {
            semaphore.release()
        }
    }

    private suspend fun doDownload(
        chapterEntityId: String,
        sourceId: String,
        chapterUrl: String,
        mangaUrl: String,
    ): Result {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val progressId = chapterEntityId.hashCode() xor 0x1000

        val progressNotification = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stahování kapitoly")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()

        setForeground(ForegroundInfo(progressId, progressNotification))
        repository.setDownloadStatus(chapterEntityId, DownloadStatus.DOWNLOADING)

        return withContext(Dispatchers.IO) {
            try {
                val pages = repository.getChapterPages(sourceId, chapterUrl, mangaUrl)
                val chapterDir = File(applicationContext.filesDir, "downloads/$chapterEntityId")
                chapterDir.mkdirs()

                pages.forEachIndexed { index, page ->
                    val imageUrl = page.imageUrl ?: page.url
                    val bytes = downloadBytes(imageUrl)
                    val extension = imageUrl.substringBefore('?').substringAfterLast('.', "jpg").take(4)
                    File(chapterDir, "%03d.%s".format(index, extension)).writeBytes(bytes)
                    val fraction = (index + 1).toFloat() / pages.size
                    nm.notify(progressId, NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("Stahování kapitoly")
                        .setContentText("${index + 1} / ${pages.size} stránek")
                        .setProgress(pages.size, index + 1, false)
                        .setOngoing(true)
                        .build())
                    setProgress(workDataOf(
                        KEY_PROGRESS to fraction,
                        KEY_CHAPTER_ENTITY_ID to chapterEntityId,
                    ))
                }

                nm.cancel(progressId)
                repository.markDownloaded(chapterEntityId, chapterDir.absolutePath, pages.size)

                if (settings.saveAsCbz.first()) {
                    createCbz(chapterDir)
                }

                if (settings.notifyDownloads.first()) notifyDone(chapterEntityId)
                Result.success()
            } catch (e: CancellationException) {
                nm.cancel(progressId)
                repository.setDownloadStatus(chapterEntityId, DownloadStatus.NOT_DOWNLOADED)
                throw e
            } catch (e: Exception) {
                nm.cancel(progressId)
                repository.setDownloadStatus(chapterEntityId, DownloadStatus.ERROR)
                if (settings.notifyDownloads.first()) notifyFailed(chapterEntityId, e)
                Result.failure()
            }
        }
    }

    private fun createCbz(chapterDir: File) {
        val cbzFile = File(chapterDir.parent, "${chapterDir.name}.cbz")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(cbzFile))).use { zip ->
            chapterDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
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

    private suspend fun notifyFailed(chapterId: String, error: Exception) {
        val chapterName = repository.getChapter(chapterId)?.name ?: "Kapitola"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Stahování selhalo")
            .setContentText("$chapterName: ${error.message ?: "neznámá chyba"}")
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(chapterId.hashCode() xor 0x2000, notification)
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
        const val KEY_PROGRESS = "progress"

        @Volatile private var currentSemaphore: Semaphore? = null
        @Volatile private var currentPermits: Int = -1

        @Synchronized
        fun getSemaphore(permits: Int): Semaphore {
            if (currentSemaphore == null || currentPermits != permits) {
                currentSemaphore = Semaphore(permits)
                currentPermits = permits
            }
            return currentSemaphore!!
        }
    }
}

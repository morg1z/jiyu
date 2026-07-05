package com.haise.jiyu.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Stáhne všechny stránky jedné kapitoly do interního úložiště appky,
 * aby šly číst offline (bez zdroje / bez internetu).
 *
 * Vstupní data: CHAPTER_ID (id v Room databázi), SOURCE_ID, CHAPTER_URL, MANGA_URL.
 */
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

        repository.setDownloadStatus(chapterEntityId, DownloadStatus.DOWNLOADING)

        try {
            val pages = repository.getChapterPages(sourceId, chapterUrl, mangaUrl)
            val chapterDir = File(applicationContext.filesDir, "downloads/$chapterEntityId")
            chapterDir.mkdirs()

            pages.forEachIndexed { index, page ->
                val imageUrl = page.imageUrl ?: page.url
                val bytes = downloadBytes(imageUrl)
                val extension = imageUrl.substringAfterLast('.', "jpg").take(4)
                File(chapterDir, "%03d.%s".format(index, extension)).writeBytes(bytes)
            }

            repository.markDownloaded(chapterEntityId, chapterDir.absolutePath, pages.size)
            Result.success()
        } catch (e: Exception) {
            repository.setDownloadStatus(chapterEntityId, DownloadStatus.ERROR)
            Result.retry()
        }
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

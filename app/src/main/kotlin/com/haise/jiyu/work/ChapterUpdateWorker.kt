package com.haise.jiyu.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haise.jiyu.MainActivity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.SManga
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

const val CHANNEL_ID = "chapter_updates"

@HiltWorker
class ChapterUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val library = repository.getAllLibraryManga()
            var newCount = 0
            val updatedManga = mutableListOf<Pair<String, Int>>()

            library.forEach { manga ->
                try {
                    val before = repository.countChapters(manga.id)
                    val sManga = SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl)
                    repository.refreshChapters(manga.id, sManga)
                    val after = repository.countChapters(manga.id)
                    val diff = after - before
                    if (diff > 0) {
                        newCount += diff
                        updatedManga.add(manga.title to diff)
                    }
                } catch (_: Exception) {
                    // Jedna manga selže → pokračuj s dalšími
                }
            }

            if (newCount > 0) notify(newCount, updatedManga)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notify(count: Int, updatedManga: List<Pair<String, Int>>) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notifTitle = if (count == 1) "1 nová kapitola" else "$count nových kapitol"
        val preview = updatedManga.first().first
        val bigText = updatedManga.joinToString("\n") { (mangaTitle, n) -> "• $mangaTitle (+$n)" }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notifTitle)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(1, notification)
    }
}

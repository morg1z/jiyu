package com.haise.jiyu.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haise.jiyu.MainActivity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.SManga
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

const val CHANNEL_ID = "chapter_updates"

@HiltWorker
class ChapterUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val library = repository.getAllLibraryManga().filter { !it.excludeFromUpdates }
            val semaphore = Semaphore(5)
            val updatedManga = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Pair<String, Int>>>())

            coroutineScope {
                library.map { manga ->
                    async {
                        semaphore.withPermit {
                            try {
                                val sManga = SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl)
                                val newChapters = repository.refreshChapters(manga.id, sManga)
                                if (newChapters.isNotEmpty()) {
                                    updatedManga.add(manga.title to (manga.id to newChapters.size))
                                }
                                if (manga.autoDownload && newChapters.isNotEmpty()) {
                                    newChapters.forEach { ch ->
                                        repository.setDownloadStatus(ch.id, DownloadStatus.QUEUED)
                                        downloadQueue.enqueue(ch, manga.url)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }.awaitAll()
            }

            if (updatedManga.isNotEmpty()) {
                if (settings.notifyNewChapters.first()) notify(updatedManga)
                settings.addNewChapters(updatedManga.sumOf { it.second.second })
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notify(updated: List<Pair<String, Pair<String, Int>>>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val totalNew = updated.sumOf { it.second.second }

        if (updated.size == 1) {
            val (title, idAndCount) = updated.first()
            val (mangaId, count) = idAndCount
            val encodedId = Uri.encode(mangaId)
            val deepUri = Uri.parse("jiyu://manga?mangaId=$encodedId")
            val intent = Intent(Intent.ACTION_VIEW, deepUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                setClass(context, MainActivity::class.java)
            }
            val pi = PendingIntent.getActivity(context, mangaId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            nm.notify(mangaId.hashCode(), NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (count == 1) "1 nová kapitola" else "$count nových kapitol")
                .setContentText(title)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } else {
            val bigText = updated.joinToString("\n") { (t, ic) -> "• $t (+${ic.second})" }
            val summaryIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val summaryPi = PendingIntent.getActivity(context, 0, summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            updated.forEach { (title, idAndCount) ->
                val (mangaId, count) = idAndCount
                val encodedId = Uri.encode(mangaId)
                val deepUri = Uri.parse("jiyu://manga?mangaId=$encodedId")
                val intent = Intent(Intent.ACTION_VIEW, deepUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    setClass(context, MainActivity::class.java)
                }
                val pi = PendingIntent.getActivity(context, mangaId.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                nm.notify(mangaId.hashCode(), NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (count == 1) "1 nová kapitola" else "$count nových kapitol")
                    .setContentText(title)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setGroup("jiyu_updates")
                    .build())
            }

            nm.notify(0, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$totalNew nových kapitol")
                .setContentText("${updated.size} mang aktualizováno")
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(summaryPi)
                .setAutoCancel(true)
                .setGroup("jiyu_updates")
                .setGroupSummary(true)
                .build())
        }
    }
}

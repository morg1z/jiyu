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
import com.haise.jiyu.util.report

const val CHANNEL_ID = "chapter_updates"

@HiltWorker
class ChapterUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    /** `latestChapterId` - nejnovejsi z nove objevenych kapitol, aby notifikace mohla vest
     * primo do ctecky misto jen na detail mangy (viz [notify]). */
    private data class UpdatedMangaInfo(val title: String, val mangaId: String, val count: Int, val latestChapterId: String?)

    override suspend fun doWork(): Result {
        return try {
            val library = repository.getAllLibraryManga().filter { !it.excludeFromUpdates }
            val semaphore = Semaphore(5)
            val updatedManga = java.util.Collections.synchronizedList(mutableListOf<UpdatedMangaInfo>())

            coroutineScope {
                library.map { manga ->
                    async {
                        semaphore.withPermit {
                            try {
                                val sManga = SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, contentType = manga.contentType)
                                val newChapters = repository.refreshChapters(manga.id, sManga)
                                // Agregovane zdroje (ComicK) umi vydat stejne cislo kapitoly vic
                                // prekladatelskymi skupinami zaraz - kazda je samostatny radek,
                                // bez dedup by tak jedna nova kapitola znamenala 2-3 upozorneni
                                // (nahlaseno uzivatelem).
                                val uniqueNewChapters = newChapters.distinctBy { it.chapterNumber }
                                if (uniqueNewChapters.isNotEmpty()) {
                                    val latest = uniqueNewChapters.maxByOrNull { it.chapterNumber }
                                    updatedManga.add(UpdatedMangaInfo(manga.title, manga.id, uniqueNewChapters.size, latest?.id))
                                }
                                if (manga.autoDownload && uniqueNewChapters.isNotEmpty() && manga.sourceId != "comick") {
                                    uniqueNewChapters.forEach { ch ->
                                        repository.setDownloadStatus(ch.id, DownloadStatus.QUEUED)
                                        downloadQueue.enqueue(ch, manga.url)
                                    }
                                }
                            } catch (e: Exception) {
                                e.report("work:chapterUpdate:manga")
                            }
                        }
                    }
                }.awaitAll()
            }

            if (updatedManga.isNotEmpty()) {
                if (settings.notifyNewChapters.first()) notify(updatedManga)
                settings.addNewChapters(updatedManga.sumOf { it.count })
            }
            Result.success()
        } catch (e: Exception) {
            // Strop pokusů jako u SyncWorker/AutoBackupWorker - trvalá chyba (rozbitá DB
            // dotaz, chybějící oprávnění) by se jinak opakovala navždy při každém
            // periodickém běhu.
            e.report("work:chapterUpdate")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /** Primo do ctecky, kdyz zname konkretni novou kapitolu, jinak fallback na detail mangy
     * (nahlaseno v auditu - notifikace dosud vedly vzdy jen na detail). */
    private fun deepLinkFor(info: UpdatedMangaInfo): Uri =
        if (info.latestChapterId != null) {
            Uri.parse("jiyu://reader?chapterId=${Uri.encode(info.latestChapterId)}")
        } else {
            Uri.parse("jiyu://manga?mangaId=${Uri.encode(info.mangaId)}")
        }

    private fun notify(updated: List<UpdatedMangaInfo>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val totalNew = updated.sumOf { it.count }

        if (updated.size == 1) {
            val info = updated.first()
            val intent = Intent(Intent.ACTION_VIEW, deepLinkFor(info)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                setClass(context, MainActivity::class.java)
            }
            val pi = PendingIntent.getActivity(context, info.mangaId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            nm.notify(info.mangaId.hashCode(), NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (info.count == 1) "1 nová kapitola" else "${info.count} nových kapitol")
                .setContentText(info.title)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } else {
            val bigText = updated.joinToString("\n") { "• ${it.title} (+${it.count})" }
            val summaryIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val summaryPi = PendingIntent.getActivity(context, 0, summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            updated.forEach { info ->
                val intent = Intent(Intent.ACTION_VIEW, deepLinkFor(info)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    setClass(context, MainActivity::class.java)
                }
                val pi = PendingIntent.getActivity(context, info.mangaId.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                nm.notify(info.mangaId.hashCode(), NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (info.count == 1) "1 nová kapitola" else "${info.count} nových kapitol")
                    .setContentText(info.title)
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

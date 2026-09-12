package com.haise.jiyu.download

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.ChapterStorage
import com.haise.jiyu.util.ScrambledImageUrl
import com.haise.jiyu.util.TileScrambleBitmap
import com.haise.jiyu.util.report
import com.haise.jiyu.work.CHANNEL_ID
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

        // Explicitní dataSync typ je od Androidu 14 (targetSdk 34) povinný, jinak setForeground()
        // shodí proces s InvalidForegroundServiceTypeException (viz manifest pro service+permission).
        setForeground(
            ForegroundInfo(progressId, progressNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        )
        repository.setDownloadStatus(chapterEntityId, DownloadStatus.DOWNLOADING)

        return withContext(Dispatchers.IO) {
            try {
                val pages = repository.getChapterPages(sourceId, chapterUrl, mangaUrl)
                val downloadFolderUri = settings.downloadFolderUri.first()
                // Čitelné jméno "Název mangy/0012 - Název kapitoly" místo dřívějšího
                // "sourceId::URL kapitoly" - uživatel si stažené kapitoly kopíruje na PC
                // (viz uživatelský dotaz), kde by opaque URL jméno bylo nepoužitelné/
                // rozbité (Windows zakazuje ':' a '/' ve jméně souboru).
                val chapterEntity = repository.getChapter(chapterEntityId)
                val mangaTitle = repository.getMangaByUrl(mangaUrl)?.title ?: "Manga"
                val chapterFolderName = if (chapterEntity != null) {
                    ChapterStorage.chapterFolderName(chapterEntity.chapterNumber, chapterEntity.name)
                } else {
                    chapterEntityId
                }
                val chapterDirPath = ChapterStorage.createChapterDir(applicationContext, downloadFolderUri, mangaTitle, chapterFolderName)

                // Kanárek PŘED stahováním - selhání se dozvíme hned, ne až po 3 marných
                // pokusech shodně léčených jako obyčejný síťový výpadek (viz níže ENOSPC vetev).
                if (!ChapterStorage.hasEnoughFreeSpace(chapterDirPath)) {
                    throw java.io.IOException("Nedostatek volného místa v úložišti")
                }

                pages.forEachIndexed { index, page ->
                    val imageUrl = page.imageUrl ?: page.url
                    // Příponu/scramble lze určit čistě z URL bez síťového volání - umožňuje
                    // zjistit cílové jméno souboru PŘED stahováním a přeskočit stránky, které
                    // už jsou z předchozího (přerušeného) pokusu na disku hotové.
                    val scramble = ScrambledImageUrl.parse(imageUrl)
                    val extension = if (scramble != null) "jpg" else imageUrl.substringBefore('?').substringAfterLast('.', "jpg").take(4)
                    val fileName = "%03d.%s".format(index, extension)

                    if (!ChapterStorage.pageExists(applicationContext, chapterDirPath, fileName)) {
                        var bytes = downloadBytes(imageUrl)
                        if (scramble != null) {
                            bytes = descrambleToJpeg(bytes, scramble.grid, scramble.seed)
                        }
                        val written = ChapterStorage.writePage(applicationContext, chapterDirPath, fileName, bytes)
                        if (!written) throw java.io.IOException("Nepodařilo se zapsat stránku $fileName")
                    }
                    val fraction = (index + 1).toFloat() / pages.size
                    // Vlastní try/catch: zamítnuté POST_NOTIFICATIONS (Android 13+) by SecurityException
                    // z notify() jinak spadlo do stejného catch níž jako chyba stahování a shodilo by
                    // celou kapitolu kvůli notifikaci, ne kvůli skutečnému problému se stahováním.
                    try {
                        nm.notify(progressId, NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle("Stahování kapitoly")
                            .setContentText("${index + 1} / ${pages.size} stránek")
                            .setProgress(pages.size, index + 1, false)
                            .setOngoing(true)
                            .build())
                    } catch (e: SecurityException) {
                        e.report("download:notify:progress")
                    }
                    setProgress(workDataOf(
                        KEY_PROGRESS to fraction,
                        KEY_CHAPTER_ENTITY_ID to chapterEntityId,
                    ))
                }

                nm.cancel(progressId)
                repository.markDownloaded(chapterEntityId, chapterDirPath, pages.size)

                if (settings.saveAsCbz.first()) {
                    // Vlastni try/catch: stranky uz jsou v tuhle chvili uspesne stazene a
                    // markDownloaded() vyse uz kapitolu oznacil jako DOWNLOADED - kdyby
                    // pripadna chyba pri baleni do CBZ spadla do vnejsiho catch, prepsala by
                    // spravny stav zpatky na ERROR, i kdyz uzivatel ma vsechny stranky skutecne
                    // v poradku na disku, jen bez volitelneho .cbz souboru navic.
                    try {
                        ChapterStorage.createCbz(applicationContext, chapterDirPath, chapterFolderName)
                    } catch (e: Exception) {
                        e.report("download:cbz")
                    }
                }

                if (settings.notifyDownloads.first()) notifyDone(chapterEntityId)
                Result.success()
            } catch (e: CancellationException) {
                nm.cancel(progressId)
                repository.setDownloadStatus(chapterEntityId, DownloadStatus.NOT_DOWNLOADED)
                throw e
            } catch (e: Exception) {
                nm.cancel(progressId)
                // Plný disk se retryem samo nikdy nespraví (na rozdíl od síťového výpadku) -
                // 3 marné pokusy by jen zbytečně stahovaly stránky znovu a skončily se
                // zavádějící "neznámá chyba" hláškou místo skutečné příčiny.
                if (isDiskFullError(e)) {
                    repository.setDownloadStatus(chapterEntityId, DownloadStatus.ERROR)
                    if (settings.notifyDownloads.first()) notifyFailed(chapterEntityId, java.io.IOException("Nedostatek volného místa v úložišti", e))
                    return@withContext Result.failure()
                }
                // Přechodná síťová chyba (výpadek, timeout, DNS) dostane pár automatických
                // pokusů - dřív jakákoli chyba rovnou trvale selhala a uživatel musel
                // vždycky stahování ručně spustit znovu, i u obyčejného zakolísání sítě.
                // Trvalé chyby (rozbitý parser, chybějící stránky) po stropu pokusů skončí
                // stejně jako dřív. Strop 3 sedí se stejným vzorem v SyncWorker.
                if (e is java.io.IOException && runAttemptCount < 3) {
                    repository.setDownloadStatus(chapterEntityId, DownloadStatus.DOWNLOADING)
                    return@withContext Result.retry()
                }
                repository.setDownloadStatus(chapterEntityId, DownloadStatus.ERROR)
                if (settings.notifyDownloads.first()) notifyFailed(chapterEntityId, e)
                Result.failure()
            }
        }
    }

    private fun notifyDone(chapterId: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.download_notification_done_title))
            .setContentText(applicationContext.getString(R.string.download_notification_done_text))
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

    /**
     * Java/Android nemá pro "plný disk" vlastní výjimku - `ENOSPC` se propaguje jako obyčejná
     * [java.io.IOException], rozpoznatelná jen podle textu zprávy (`hasEnoughFreeSpace`
     * předletový kanárek výše chytí většinu případů předem, tohle je záchranná síť pro to,
     * co se zaplní ažpo startu stahování).
     */
    private fun isDiskFullError(e: Exception): Boolean {
        if (e !is java.io.IOException) return false
        val message = e.message ?: return false
        return message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true) ||
            message.contains("Nedostatek volného místa", ignoreCase = true)
    }

    /**
     * `suspendCancellableCoroutine` + `call.cancel()` na zrušení - blokující `execute()` sám
     * o sobě nemá jak reagovat na zrušení korutiny (WorkManager stop workeru), takže by
     * request na jednu stránku doběhl vždycky celý, i když appka/systém stahování už dávno
     * chce přerušit (nahlášený bug). `IOException` místo `IllegalStateException` na
     * `isSuccessful`/prázdné tělo - tyhle případy tak dostanou existující retry mechanismus
     * (`e is IOException && runAttemptCount < 3` v [doDownload]) místo trvalého selhání
     * kapitoly na první přechodné chybě (503, dočasně prázdná odpověď apod.).
     */
    private suspend fun downloadBytes(url: String): ByteArray {
        val call = client.newCall(Request.Builder().url(url).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        cont.resumeWithException(java.io.IOException("Stažení selhalo (${response.code}): $url"))
                        return@use
                    }
                    val bytes = response.body?.bytes()
                    if (bytes == null) {
                        cont.resumeWithException(java.io.IOException("Prázdná odpověď: $url"))
                    } else {
                        cont.resume(bytes)
                    }
                }
            } catch (e: java.io.IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        }
    }

    /**
     * Offline stahování jde přímo přes OkHttp, ne přes Coil - proto tu chybí
     * [com.haise.jiyu.ui.reader.TileDescrambleTransformation] a je potřeba
     * rozskládat dlaždice (viz [com.haise.jiyu.util.TileScramble]) ručně tady,
     * jinak by se na disk uložil nečitelný zpřeházený obrázek.
     */
    private fun descrambleToJpeg(bytes: ByteArray, grid: Int, seed: Long): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Nepodařilo se dekódovat obrázek pro rozskládání dlaždic")
        val descrambled = TileScrambleBitmap.descramble(bitmap, grid, seed)
        val output = ByteArrayOutputStream()
        descrambled.compress(Bitmap.CompressFormat.JPEG, 92, output)
        return output.toByteArray()
    }

    companion object {
        const val KEY_CHAPTER_ENTITY_ID = "chapter_entity_id"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_MANGA_URL = "manga_url"
        const val KEY_PROGRESS = "progress"

        @Volatile private var currentSemaphore: Semaphore? = null
        @Volatile private var currentPermits: Int = -1

        // Sdileny napric VSEMI bezicimi ChapterDownloadWorker instancemi zaroven - k tomu tu
        // je (proces-wide throttle na "kolik kapitol se stahuje soubezne", ne per-worker).
        // Zmena semaphore NA NOVOU INSTANCI, dokud stara drzi vydane permity, by limit
        // porusila (dva ruzne objekty spolu nekomunikuji, takze by na chvili mohlo bezet
        // az soucet obou kapacit soubezne) - proto se kapacita meni jen ve chvili, kdy je
        // stara semaphore prokazatelne VOLNA (availablePermits == currentPermits, nikdo
        // z ni nic nedrzi). Dokud bezi stare stahovani se starym limitem, novy pozadavek
        // na jiny limit pockej az na jeho konec - o nekolik sekund pozdejsi projeveni
        // zmeny nastaveni je nesrovnatelne bezpecnejsi nez docasne uplne vypnuty throttle.
        @Synchronized
        fun getSemaphore(permits: Int): Semaphore {
            val existing = currentSemaphore
            if (existing == null || (currentPermits != permits && existing.availablePermits == currentPermits)) {
                currentSemaphore = Semaphore(permits)
                currentPermits = permits
            }
            return currentSemaphore!!
        }
    }
}

package com.haise.jiyu.download

import com.haise.jiyu.source.interceptor.InteractiveChallengePolicy
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
import com.haise.jiyu.di.ImageHttpClient
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val CHANNEL_DOWNLOADS = "channel_downloads"

/**
 * Kolik stránek jedné kapitoly se stahuje současně - viz [ChapterDownloadWorker.doDownload].
 * OkHttpův `Dispatcher` limituje 5 požadavků na hostitele jen u asynchronního `enqueue()`, kdežto
 * worker volá synchronní `execute()` - tenhle limit se tedy NEUPLATNÍ. Souběh přes více
 * paralelně běžících kapitol proto hlídá [TOTAL_PAGE_DOWNLOADS] (sdílený mezi všemi workery);
 * `imageHttpClient` nemá throttle záměrně, aby stahování nezpomalovalo čtení (Coil).
 */
private const val PAGE_DOWNLOAD_CONCURRENCY = 4

/** Strop souběžných stažení stránek napříč VŠEMI běžícími workery (viz [PAGE_DOWNLOAD_CONCURRENCY]). */
private const val TOTAL_PAGE_DOWNLOADS = 6
private val totalPageDownloadPermits = Semaphore(TOTAL_PAGE_DOWNLOADS)

@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
    // @ImageHttpClient (ne defaultni provideOkHttpClient) - ten ma RetryInterceptor(3x) +
    // ThrottleInterceptor navic urcene pro scraping HTML zdroju. Bez tehle kvalifikace by
    // se kazdy downloadBytes() mohl interne az 3x preopakovat (kazdy pokus az 60s) PRED
    // tim, nez vubec vyhodi vyjimku, a pak by WorkManageruv vlastni retry (runAttemptCount)
    // zopakoval CELOU kapitolu znovu - vrstvene az 3x3 pokusy na jednu spatnou stranku.
    @ImageHttpClient private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

    // Na pozadí se nikdy neukazuje interaktivní výzva Cloudflare (viz InteractiveChallengePolicy).
    override suspend fun doWork(): Result = InteractiveChallengePolicy.suppressed { runDownload() }

    private suspend fun runDownload(): Result {
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
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
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
                // Zdroj nevrátil žádné stránky (web změnil markup, chyba spolknutá ve zdroji, všechny URL
                // vyfiltrované) - dřív se kapitola s 0 stránkami označila jako STAŽENÁ a čtečka pak
                // ukazovala prázdno. Teď jde jako chyba do stejného retry/ERROR toku jako síťový výpadek.
                if (pages.isEmpty()) throw EmptyChapterException()
                val downloadFolderUri = settings.downloadFolderUri.first()
                // Čitelné jméno "Název mangy/0012 - Název kapitoly" místo dřívějšího
                // "sourceId::URL kapitoly" - uživatel si stažené kapitoly kopíruje na PC
                // (viz uživatelský dotaz), kde by opaque URL jméno bylo nepoužitelné/
                // rozbité (Windows zakazuje ':' a '/' ve jméně souboru).
                val chapterEntity = repository.getChapter(chapterEntityId)
                val mangaTitle = chapterEntity?.let { repository.getManga(it.mangaId) }?.title ?: "Manga"
                val chapterFolderName = if (chapterEntity != null) {
                    ChapterStorage.chapterFolderName(chapterEntity.chapterNumber, chapterEntity.name)
                } else {
                    chapterEntityId
                }
                val chapterDirPath = ChapterStorage.createChapterDir(applicationContext, downloadFolderUri, mangaTitle, chapterFolderName)

                // Kanárek PŘED stahováním - selhání se dozvíme hned, ne až po 3 marných
                // pokusech shodně léčených jako obyčejný síťový výpadek (viz níže ENOSPC vetev).
                if (!ChapterStorage.hasEnoughFreeSpace(chapterDirPath)) {
                    throw DiskFullException()
                }

                // Souběžně místo striktně sekvenčně (viz PAGE_DOWNLOAD_CONCURRENCY) - na
                // vysoké latenci bylo stahování jedné stránky za druhou N×RTT navíc. `async`
                // + `coroutineScope` = structured concurrency: první výjimka (síťová chyba,
                // selhání zápisu na disk) zruší zbylé souběžné stránky a probublá ven úplně
                // stejně, jako když forEachIndexed vyhodilo výjimku uprostřed - beze změny
                // chování retry/error logiky níž. Dokončení teď nepřichází v indexovém
                // pořadí, proto AtomicInteger čítač místo `index + 1` v progress hlášce.
                val pageSemaphore = Semaphore(PAGE_DOWNLOAD_CONCURRENCY)
                val completedPages = AtomicInteger(0)
                coroutineScope {
                    pages.mapIndexed { index, page ->
                        async {
                            pageSemaphore.withPermit {
                                val imageUrl = page.imageUrl ?: page.url
                                // Příponu/scramble lze určit čistě z URL bez síťového volání - umožňuje
                                // zjistit cílové jméno souboru PŘED stahováním a přeskočit stránky, které
                                // už jsou z předchozího (přerušeného) pokusu na disku hotové.
                                val scramble = ScrambledImageUrl.parse(imageUrl)
                                val extension = if (scramble != null) "jpg" else imageUrl.substringBefore('?').substringAfterLast('.', "jpg").take(4)
                                val fileName = "%03d.%s".format(index, extension)

                                if (!ChapterStorage.pageExists(applicationContext, chapterDirPath, fileName)) {
                                    var bytes = totalPageDownloadPermits.withPermit { downloadBytes(imageUrl) }
                                    if (scramble != null) {
                                        bytes = descrambleToJpeg(bytes, scramble.grid, scramble.seed)
                                    }
                                    val written = ChapterStorage.writePage(applicationContext, chapterDirPath, fileName, bytes)
                                    if (!written) throw java.io.IOException("Nepodařilo se zapsat stránku $fileName")
                                }
                                val completed = completedPages.incrementAndGet()
                                val fraction = completed.toFloat() / pages.size
                                // Vlastní try/catch: zamítnuté POST_NOTIFICATIONS (Android 13+) by SecurityException
                                // z notify() jinak spadlo do stejného catch níž jako chyba stahování a shodilo by
                                // celou kapitolu kvůli notifikaci, ne kvůli skutečnému problému se stahováním.
                                try {
                                    nm.notify(progressId, NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
                                        .setSmallIcon(android.R.drawable.stat_sys_download)
                                        .setContentTitle(applicationContext.getString(R.string.download_notification_title))
                                        .setContentText(applicationContext.getString(R.string.download_notification_progress, completed, pages.size))
                                        .setProgress(pages.size, completed, false)
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
                        }
                    }.awaitAll()
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
                    if (settings.notifyDownloads.first()) notifyFailed(chapterEntityId, DiskFullException(e))
                    return@withContext Result.failure()
                }
                // Přechodná síťová chyba (výpadek, timeout, DNS) dostane pár automatických
                // pokusů - dřív jakákoli chyba rovnou trvale selhala a uživatel musel
                // vždycky stahování ručně spustit znovu, i u obyčejného zakolísání sítě.
                // Trvalé chyby (rozbitý parser, chybějící stránky) po stropu pokusů skončí
                // stejně jako dřív. Strop 3 sedí se stejným vzorem v SyncWorker.
                // Limit zdroje (429, SourceRateLimitedException - od opravy polykani vyjimek ve zdrojich
                // se sem dostane, dřív zdroj vrátil prázdný seznam stránek) je taky přechodný.
                if ((e is java.io.IOException || e is com.haise.jiyu.source.SourceRateLimitedException) && runAttemptCount < 3) {
                    repository.setDownloadStatus(chapterEntityId, DownloadStatus.DOWNLOADING)
                    // Web řekl, jak dlouho počkat (Retry-After) - vyčká se aspoň tak, ne jen pevných 30 s backoffu
                    // WorkManageru, který by limit znovu narazil. Delší čekání než 5 min se nedrží ve workeru.
                    if (e is com.haise.jiyu.source.SourceRateLimitedException) {
                        val wait = retryAfterWaitMs(e.retryAfterMs)
                        if (wait > 0) kotlinx.coroutines.delay(wait)
                    }
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
        val chapterName = repository.getChapter(chapterId)?.name
            ?: applicationContext.getString(R.string.download_chapter_fallback_name)
        // Text pro uživatele podle jazyka appky; zpráva samotné výjimky je pro vývojáře (logy) a česky.
        val reason = if (error is DiskFullException) {
            applicationContext.getString(R.string.download_error_disk_full)
        } else if (error is EmptyChapterException) {
            applicationContext.getString(R.string.download_error_no_pages)
        } else {
            error.message ?: applicationContext.getString(R.string.download_error_unknown)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(applicationContext.getString(R.string.download_notification_failed_title))
            .setContentText(applicationContext.getString(R.string.download_notification_failed_text, chapterName, reason))
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(chapterId.hashCode() xor 0x2000, notification)
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
        // Stahování kapitol vždy v originální kvalitě, mimo úsporný režim obrázků (viz ImageProxyInterceptor).
        val call = client.newCall(
            Request.Builder().url(url)
                .header(com.haise.jiyu.source.interceptor.ImageProxyInterceptor.HEADER_ORIGINAL, "1")
                .build(),
        )
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

/** Zdroj pro kapitolu nevrátil žádné stránky - viz [ChapterDownloadWorker]. */
class EmptyChapterException : java.io.IOException("Zdroj nevrátil žádné stránky")

/** Plný disk - vlastní typ, aby se rozpoznával podle třídy, ne podle (lokalizovaného) textu zprávy. */
class DiskFullException(cause: Throwable? = null) : java.io.IOException("Nedostatek volného místa v úložišti", cause)

/**
 * Java/Android nemá pro "plný disk" vlastní výjimku - `ENOSPC` se propaguje jako obyčejná
 * [java.io.IOException] rozpoznatelná jen podle textu zprávy od systému. Předletový kanárek
 * (`hasEnoughFreeSpace`) hází [DiskFullException]; tohle je záchranná síť pro to, co se zaplní
 * až po startu stahování.
 */
internal fun isDiskFullError(e: Exception): Boolean {
    if (e is DiskFullException) return true
    if (e !is java.io.IOException) return false
    val message = e.message ?: return false
    return message.contains("ENOSPC", ignoreCase = true) ||
        message.contains("No space left", ignoreCase = true)
}

/** Nejdelší Retry-After, na který se ve workeru skutečně čeká (delší necháme na backoff WorkManageru). */
internal const val MAX_RETRY_AFTER_WAIT_MS = 5L * 60 * 1000

/** Kolik ms počkat podle Retry-After: 0 = nečekat, jinak hodnota omezená stropem [MAX_RETRY_AFTER_WAIT_MS]. */
internal fun retryAfterWaitMs(retryAfterMs: Long): Long = retryAfterMs.coerceIn(0L, MAX_RETRY_AFTER_WAIT_MS)

package com.haise.jiyu.translate

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.haise.jiyu.R
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.CHANNEL_DOWNLOADS
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.report
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Přeloží JEDNU kapitolu na pozadí, nezávisle na tom, jestli je appka otevřená.
 *
 * ## Proč to vzniklo
 * Překlad kapitoly se do teď spouštěl VÝHRADNĚ z [com.haise.jiyu.ui.reader.ReaderViewModel],
 * a to ve `viewModelScope`. Ten je svázaný se životem obrazovky čtečky: odejdi ze čtečky nebo
 * zavři appku a scope se zruší i s rozdělaným překladem. Uživatel to hlásil jako "překládání
 * na pozadí pořád pořádně nefunguje" - a mělo pravdu, žádné totiž neexistovalo. Nedokončená
 * kapitola se navíc tiše ztratila: dokončené stránky sice zůstaly v cache, ale zbytek se
 * musel spustit znovu ručně.
 *
 * Worker běží ve WorkManageru s popředovou notifikací (stejně jako
 * [com.haise.jiyu.download.ChapterDownloadWorker]), takže ho systém nezabije, když uživatel
 * odejde psát maily.
 *
 * ## Pořadí a kvóta
 * Fronta je záměrně SEKVENČNÍ - viz [TranslateQueue]. Překlad není omezený rychlostí sítě jako
 * stahování, ale znakovou kvótou překladového API; pustit pět kapitol najednou by ji vyčerpalo
 * pětkrát rychleji a k ničemu, protože stejně čekají na tentýž upstream.
 *
 * Už přeložené stránky se přeskakují zadarmo - [TranslateRepository.translateChapter] si je
 * napřed vytáhne z cache.
 */
@HiltWorker
class TranslateChapterWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MangaRepository,
    private val translateRepository: TranslateRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return Result.failure()

        val chapter = repository.getChapter(chapterId) ?: return Result.failure()
        val manga = repository.getManga(chapter.mangaId) ?: return Result.failure()

        val targetLanguage = settings.targetLanguage.first()
        val sourceLanguage = settings.sourceLanguage.first()

        val notificationId = chapterId.hashCode() xor TRANSLATE_NOTIFICATION_SALT
        val title = applicationContext.getString(R.string.translate_worker_notification_title, manga.title)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)

        suspend fun showProgress(done: Int, total: Int) {
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(applicationContext.getString(R.string.translate_worker_notification_chapter, chapter.name))
                .setProgress(total, done, total == 0)
                .setOngoing(true)
                .build()
            setForeground(
                ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            )
        }

        showProgress(done = 0, total = 0)

        return try {
            val rawPages = repository.getChapterPages(chapter.sourceId, chapter.url, manga.url)
            // Novely jdou jinou cestou (translateNovelChapter nad textem, ne OCR nad obrázky),
            // takže je tenhle worker vědomě nepodporuje - nemá co předat translateChapter.
            if (rawPages.any { it.imageUrl == NOVEL_MARKER }) return Result.success()

            // Stejné odvození URL jako ve čtečce: imageUrl je vyplněná jen u zdrojů, které
            // adresu obrázku doplňují dodatečně, jinak platí url.
            val pages = rawPages.mapNotNull { page ->
                page.imageUrl?.takeIf { it.isNotBlank() } ?: page.url.takeIf { it.isNotBlank() }
            }
            if (pages.isEmpty()) return Result.failure()

            var done = 0
            translateRepository.translateChapter(
                pages = pages,
                chapterId = chapterId,
                mangaId = manga.id,
                targetLanguage = targetLanguage,
                sourceLanguage = sourceLanguage,
            ) { _, _ ->
                done++
                // Notifikace se překresluje jen po celých desetinách, ne po každé stránce:
                // setForeground je meziprocesové volání a u kapitoly o 200 stránkách webtoonu
                // by jich bylo 200 zbytečně.
                if (done % NOTIFICATION_EVERY_N_PAGES == 0 || done == pages.size) {
                    showProgress(done, pages.size)
                }
            }
            nm?.cancel(notificationId)
            Result.success()
        } catch (e: CancellationException) {
            // Zrušení uživatelem nebo systémem není chyba - hlásit by se nemělo.
            nm?.cancel(notificationId)
            throw e
        } catch (e: Exception) {
            e.report("translate:worker:${chapter.sourceId}")
            nm?.cancel(notificationId)
            // Retry, ne failure: nejčastější důvod je vyčerpaná kvóta nebo výpadek sítě, a obojí
            // přejde. Dokončené stránky zůstávají v cache, takže opakování začne tam, kde skončilo.
            // Strop 3 pokusů jako u SyncWorker/AutoBackupWorker/ChapterUpdateWorker - bez něj by
            // TRVALÁ chyba (rozbitý parser jen na téhle kapitole, permanentně smazaná stránka u
            // zdroje) zkoušela pořád dokola na neurčito, místo aby se jednou ohlásila jako selhání.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"

        /** Aby se ID notifikace nesrazilo se stahováním téže kapitoly (to používá 0x1000). */
        private const val TRANSLATE_NOTIFICATION_SALT = 0x2000
        private const val NOTIFICATION_EVERY_N_PAGES = 10

        /** Značka, kterou zdroje novel označují "tohle není obrázek, ale text kapitoly". */
        private const val NOVEL_MARKER = "novel://text"
    }
}

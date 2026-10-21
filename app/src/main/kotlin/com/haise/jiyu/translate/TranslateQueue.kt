package com.haise.jiyu.translate

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fronta kapitol k překladu na pozadí - obdoba [com.haise.jiyu.download.DownloadQueue],
 * ale sekvenční.
 *
 * ## Proč se kapitoly řadí za sebe a neběží souběžně
 * Stahování má smysl paralelizovat, protože ho brzdí latence sítě. Překlad brzdí něco jiného:
 * ZNAKOVÁ KVÓTA překladového API. Pět kapitol najednou by ji vyčerpalo pětkrát rychleji, stály
 * by ve frontě na tentýž upstream a jediné, co by se tím získalo, je pět souběžných notifikací.
 *
 * Zajišťuje to `ExistingWorkPolicy.APPEND_OR_REPLACE` nad jedním jménem: WorkManager pouští
 * další kapitolu až po dokončení předchozí a pořadí drží i přes restart telefonu. REPLACE ve
 * jméně politiky se uplatní jen tehdy, když je předchozí řetěz v koncovém stavu (zrušený nebo
 * selhaný) - jinak by se nová kapitola nikdy nezařadila za mrtvý řetěz a tiše by se ztratila.
 */
@Singleton
class TranslateQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Zařadí kapitoly v zadaném pořadí. Prázdný seznam nic neudělá.
     *  Jazyky předává volající - zamrazí se při ENQUEUE, ne při exekuci (worker dřív
     *  četl settings až při spuštění a změna nastavení mezitím přeložila dávku do
     *  jiného jazyka, než uživatel tapnul - audit #35). */
    fun enqueue(chapterIds: List<String>, targetLanguage: String, sourceLanguage: String) {
        if (chapterIds.isEmpty()) return

        val requests = chapterIds.map { chapterId ->
            OneTimeWorkRequestBuilder<TranslateChapterWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(TranslateChapterWorker.KEY_CHAPTER_ID, chapterId)
                        .putString(TranslateChapterWorker.KEY_TARGET_LANGUAGE, targetLanguage)
                        .putString(TranslateChapterWorker.KEY_SOURCE_LANGUAGE, sourceLanguage)
                        .build(),
                )
                .setConstraints(
                    // Překlad je čistě síťová operace (OCR běží on-device, ale bez stránek
                    // není co OCR-ovat), takže bez připojení nemá smysl worker vůbec budit.
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(tagFor(chapterId))
                .addTag(TAG_ALL)
                .build()
        }

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, requests)
    }

    fun cancel(chapterId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(chapterId))
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_ALL)
    }

    internal companion object {
        const val UNIQUE_WORK_NAME = "jiyu_translate_queue"
        const val TAG_ALL = "jiyu_translate"
        fun tagFor(chapterId: String) = "translate_$chapterId"
    }
}

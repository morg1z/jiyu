package com.haise.jiyu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.haise.jiyu.data.db.TranslatedNovelDao
import com.haise.jiyu.data.db.deleteBrowsedManga
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.download.CHANNEL_DOWNLOADS
import com.haise.jiyu.source.mangaplus.MangaPlusImageFetcher
import com.haise.jiyu.work.CHANNEL_ID
import com.haise.jiyu.util.report
import com.haise.jiyu.work.ChapterUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class JiyuApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var httpClient: OkHttpClient
    @Inject lateinit var translatedPageDao: TranslatedPageDao
    @Inject lateinit var translatedNovelDao: TranslatedNovelDao

    /** Souhlas s hlášením pádů - viz [initFirebase]. */
    @Inject lateinit var settings: com.haise.jiyu.settings.SettingsRepository

    /** Úklid jen prohlédnuté mangy při startu - viz [evictOldTranslationCache]. */
    @Inject lateinit var database: com.haise.jiyu.data.db.AppDatabase

    /**
     * Vynutit sestavení Supabase klienta TADY, na hlavním vlákně při startu appky.
     *
     * Bez tohohle ho Hilt sestaví líně při prvním použití - a tím prvním použitím byl
     * [com.haise.jiyu.work.SyncWorker] běžící na WorkManager threadu. Auth (GoTrue) plugin
     * si při instalaci registruje pozorovatele ProcessLifecycle, což Android vyžaduje na
     * hlavním vlákně - appka proto opakovaně padala s
     * "Method addObserver must be called on the main thread" pár minut po startu čtení.
     */
    @Inject lateinit var supabaseClient: SupabaseClient

    override fun onCreate() {
        super.onCreate()

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizePercent(0.20)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .okHttpClient(httpClient)
                .crossfade(true)
                .respectCacheHeaders(false)
                .components { add(MangaPlusImageFetcher.Factory(httpClient)) }
                .build()
        )

        createNotificationChannel()
        scheduleChapterUpdates()
        initFirebase()
        evictOldTranslationCache()
    }

    /**
     * Crashlytics + Analytics — jede jen pokud existuje app/google-services.json
     * (BuildConfig.FIREBASE_ENABLED se nastavuje v gradle podle přítomnosti souboru).
     * V debug buildu sbírání crashů vypínáme, ať si nezanášíme dashboard testovacím haraburdím.
     *
     * Od 2026-08-02 navíc rozhoduje SOUHLAS uživatele (viz [SettingsKeys.CRASH_REPORTING]).
     * Do té doby se v každém release buildu sbíralo natvrdo a bez ptaní; ze všeho, co z appky
     * odchází, je tohle jediná věc, kterou si uživatel nevyžádal a nic mu nepřináší.
     *
     * Sbírání se zapíná/vypíná ZA BĚHU podle toho, jak uživatel přepínač v Nastavení mění -
     * proto se stav sleduje, ne čte jednorázově při startu. Výchozí hodnota je false, takže
     * než dorazí první hodnota z DataStore, nic se neodesílá.
     */
    private fun initFirebase() {
        if (!BuildConfig.FIREBASE_ENABLED) return

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            settings.crashReporting.collect { consented ->
                val enabled = consented && !BuildConfig.DEBUG
                Firebase.crashlytics.setCrashlyticsCollectionEnabled(enabled)
                Firebase.analytics.setAnalyticsCollectionEnabled(enabled)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nové kapitoly", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Upozornění na nové kapitoly v knihovně"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Stahování kapitol", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Průběh stahování kapitol"
            }
        )
    }

    private fun evictOldTranslationCache() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            translatedPageDao.deleteOlderThan(cutoff)
            // Novely se dřív neuklízely vůbec - byly jediná část cache, která rostla donekonečna.
            translatedNovelDao.deleteOlderThan(cutoff)
            // Stejný důvod, jiná tabulka: procházení vkládá mangu do databáze a nic ji nikdy
            // nemazalo, takže rostla z každého otevřeného detailu. Viz [deleteBrowsedManga] -
            // maže jen to, co uživatel prokazatelně nechtěl (není v knihovně, nečetl ji,
            // nezařadil, nestáhl).
            runCatching { database.deleteBrowsedManga() }
                .onFailure { it.report("db:evictBrowsedManga") }
        }
    }

    private fun scheduleChapterUpdates() {
        val request = PeriodicWorkRequestBuilder<ChapterUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "chapter_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

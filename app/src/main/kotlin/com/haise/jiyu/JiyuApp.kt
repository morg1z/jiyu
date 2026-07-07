package com.haise.jiyu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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
import com.haise.jiyu.source.mangaplus.MangaPlusImageFetcher
import com.haise.jiyu.work.CHANNEL_ID
import com.haise.jiyu.work.ChapterUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class JiyuApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var httpClient: OkHttpClient

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
    }

    /**
     * Crashlytics + Analytics — jede jen pokud existuje app/google-services.json
     * (BuildConfig.FIREBASE_ENABLED se nastavuje v gradle podle přítomnosti souboru).
     * V debug buildu sbírání crashů vypínáme, ať si nezanášíme dashboard testovacím haraburdím.
     */
    private fun initFirebase() {
        if (!BuildConfig.FIREBASE_ENABLED) return

        Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        Firebase.analytics.setAnalyticsCollectionEnabled(!BuildConfig.DEBUG)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nové kapitoly",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Upozornění na nové kapitoly v knihovně"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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

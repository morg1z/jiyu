package com.haise.jiyu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Configuration
import kotlinx.coroutines.flow.first
import com.haise.jiyu.work.ChapterUpdateScheduler
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
import com.haise.jiyu.di.ImageHttpClient
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
    @Inject @ImageHttpClient lateinit var imageHttpClient: OkHttpClient
    @Inject lateinit var translatedPageDao: TranslatedPageDao
    @Inject lateinit var translatedNovelDao: TranslatedNovelDao

    /** Souhlas s hlášením pádů - viz [initFirebase]. */
    @Inject lateinit var settings: com.haise.jiyu.settings.SettingsRepository

    /** Úklid jen prohlédnuté mangy při startu - viz [evictOldTranslationCache]. */
    @Inject lateinit var database: com.haise.jiyu.data.db.AppDatabase

    /** Odblokování zaseknutých stahování při startu - viz [resetStuckDownloads]. */
    @Inject lateinit var mangaRepository: com.haise.jiyu.data.repository.MangaRepository

    /** Obnovení periodické cloud synchronizace při startu - viz [resumeBackgroundSyncIfSignedIn]. */
    @Inject lateinit var authRepository: com.haise.jiyu.auth.AuthRepository

    /** Volitelná proxy - uložené hodnoty se přenášejí do [com.haise.jiyu.source.interceptor.NetworkProxyConfig]. */
    @Inject lateinit var proxyRepository: com.haise.jiyu.source.interceptor.ProxyRepository

    /** Příznak úsporného režimu obrázků - aktualizuje se z nastavení (viz [onCreate]). */
    @Inject lateinit var imageProxyConfig: com.haise.jiyu.source.interceptor.ImageProxyConfig

    /** Příznak DNS-over-HTTPS - aktualizuje se z nastavení (viz [onCreate]). */
    @Inject lateinit var dohConfig: com.haise.jiyu.di.DnsOverHttpsConfig

    /** Region-decode řezů extrémně vysokých stránek - viz komponenty ImageLoaderu níž. */
    @Inject lateinit var pageSlicer: com.haise.jiyu.util.PageSlicer

    /** Paměťová cache výsledků ze zdrojů - při tlaku na paměť se uvolňuje (viz [onTrimMemory]). */
    @Inject lateinit var sourceContentCache: com.haise.jiyu.data.repository.SourceContentCache

    /** Proaktivni reseni Cloudflare vyzv na pozadi - viz [com.haise.jiyu.source.interceptor.CloudflareWarmup]. */
    @Inject lateinit var cloudflareWarmup: com.haise.jiyu.source.interceptor.CloudflareWarmup

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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::sourceContentCache.isInitialized) return
        when {
            // 15 = RUNNING_CRITICAL, >= 60 = MODERATE/COMPLETE: paměť je kritická, cache pryč.
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> sourceContentCache.clear()
            // 10 = RUNNING_LOW, 40 = BACKGROUND: nech jen nejnovější položky.
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> sourceContentCache.trim(1)
        }
    }

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
                        // 1 GB (driv 512/256): velka webtoon kapitola (~400 hi-res stranek)
                        // muze mit 150-400 MB a prefetch cele kapitoly + par okolnich ji
                        // protaci rychle - vraceni se zpet pak znamenalo znovu-stazeni
                        // ("obrazky maji zustat nactene").
                        .maxSizeBytes(1024L * 1024 * 1024)
                        .build()
                }
                .okHttpClient(imageHttpClient)
                .crossfade(true)
                .respectCacheHeaders(false)
                .components {
                    add(MangaPlusImageFetcher.Factory(imageHttpClient))
                    // Stránky zdrojů s líným resolvováním (MangaHome, FanFox...) -
                    // jejich Page.url není obrázek, resolvuje se přes getImageUrl.
                    add(com.haise.jiyu.source.LazyPageFetcher.Factory(imageHttpClient, mangaRepository))
                    // Řezy extrémně vysokých stránek (>8192 px) - dekódují se přes
                    // BitmapRegionDecoder per-řez, celá bitmapa se nikdy nematerializuje
                    // (OOM + GPU texture limit - viz PageSlicer).
                    add(com.haise.jiyu.source.PageSliceFetcher.Factory(pageSlicer))
                }
                .build()
        )

        proxyRepository.bind(appScope)

        // Úsporný režim obrázků: interceptor čte jen volatile příznak, nastavení ho sem přenáší.
        appScope.launch {
            settings.imageProxyEnabled.collect { imageProxyConfig.enabled = it }
        }

        // DNS-over-HTTPS přepínač - stejný vzor jako imageProxyConfig výš.
        appScope.launch {
            settings.dnsOverHttpsEnabled.collect { dohConfig.enabled = it }
        }

        // User-Agent skutečného WebView (viz CloudflareUserAgent) se zjistí předem na hlavním vlákně, ať ho interceptor
        // na vlákně sítě rovnou najde v paměti.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { com.haise.jiyu.source.interceptor.CloudflareUserAgent.value(this) }
        }

        createNotificationChannel()
        scheduleChapterUpdates()
        initFirebase()
        evictOldTranslationCache()
        resetStuckDownloads()
        resumeBackgroundSyncIfSignedIn()
        // Cloudflare warm-up: na pozadi si pred-resi vyzvy znamych CF webu (detached WebView;
        // Turnstile hosty dobehne pres neviditelny auto-tap, az se objevi UI). Bez nej by prvni
        // request na CF zdroj stal ~5-18 s a hromadne operace (globalni hledani, stahovani)
        // na CF hostech vzdycky selhaly.
        cloudflareWarmup.start()
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
    // Jeden sdileny scope pro vsechny pomocne corutiny aplikace - drive se pro kazdy
    // bind/collect vytvarel zvlastni throwaway CoroutineScope+SupervisorJob (audit).
    // Zije cely proces appky (aktivity v nem nebezi, takze cancel neni potreba).
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun initFirebase() {
        if (!BuildConfig.FIREBASE_ENABLED) return

        appScope.launch {
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
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_chapters_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_chapters_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notification_channel_downloads_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_downloads_desc)
            }
        )
    }

    private fun evictOldTranslationCache() {
        appScope.launch {
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

    /**
     * Pokud appka spadla nebo byla zabita uprostřed stahování, kapitoly zůstanou navždy
     * ve stavu QUEUED/DOWNLOADING - nic je jinak nevrací zpět a uživatel je vidí jako
     * "stahuje se" bez jakéhokoli postupu. Jen resetuje DB stav zpět na NOT_DOWNLOADED;
     * nemaže žádné soubory ani neřeší per-soubor stav (na rozdíl od
     * [com.haise.jiyu.data.db.ChapterDao.resetDownloadForChapter], což je jiný, souborově
     * uvědomělý mechanismus používaný jinde).
     */
    private fun resetStuckDownloads() {
        appScope.launch {
            runCatching { mangaRepository.resetActiveDownloads() }
                .onFailure { it.report("download:resetStuckDownloads") }
        }
    }

    /**
     * `AccountViewModel.scheduleBackgroundSync()` se dřív volalo JEN jednou, hned po
     * úspěšném přihlášení - reinstall appky nebo vymazání dat (smazaná WorkManager DB, ale
     * uživatel zůstal přihlášený přes perzistentní token) tak periodickou synchronizaci
     * navždy ztratilo, dokud by se uživatel ručně neodhlásil a nepřihlásil znovu (appka
     * nemá BOOT_COMPLETED receiver, viz audit nález). `enqueueUniquePeriodicWork` s `KEEP`
     * politikou (uvnitř [SyncScheduler]) je bezpečné volat na každém startu - u už
     * naplánované práce je no-op, stejný vzor jako [scheduleChapterUpdates].
     */
    private fun resumeBackgroundSyncIfSignedIn() {
        if (authRepository.currentUserId() != null) {
            com.haise.jiyu.work.SyncScheduler.schedule(this)
        }
    }

    private fun scheduleChapterUpdates() {
        // KEEP: už naplánovaná práce (s intervalem z nastavení) se nemění; uložený interval se použije
        // jen tam, kde plán chybí (čerstvá instalace, smazaná WorkManager DB) - dřív tam byla natvrdo 12 h.
        appScope.launch {
            val hours = runCatching { settings.updateIntervalHours.first() }.getOrDefault(ChapterUpdateScheduler.DEFAULT_INTERVAL_HOURS)
            ChapterUpdateScheduler.schedule(this@JiyuApp, hours.coerceAtLeast(1L))
        }
    }
}

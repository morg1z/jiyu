package com.haise.jiyu.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.MangaNoteDao
import com.haise.jiyu.data.db.MangaTagDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.TranslatedNovelDao
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.util.SleepTimerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.haise.jiyu.source.SourceRateLimitedException
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.source.mangacloud.MangaCloudSession
import com.haise.jiyu.source.mangacloud.WebViewMangaCloudSession
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Klient jen pro stahování bajtů obrázků (Coil - covery i stránky kapitol), oddělený od
 * [provideOkHttpClient] použitého pro scraping HTML zdrojů. Na rozdíl od něj NEMÁ
 * RetryInterceptor (3× opakování celého řetězce včetně Cloudflare řešení dokázalo natáhnout
 * jeden nenačtený obrázek na přes minutu - appka na to působila "několikanásobně pomaleji"
 * než srovnatelné čtečky) ani vlastní ThrottleInterceptor (OkHttpův vestavěný Dispatcher už
 * defaultně limituje 5 souběžných požadavků na hostitele, takže dělal jen duplicitní práci).
 * HotlinkRefererInterceptor a CloudflareInterceptor zůstávají - obrázkové CDN je taky občas
 * vyžadují/mají za Cloudflare.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageHttpClient

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Jednoduchý retry interceptor — opakuje síťový požadavek při IOException (timeout, DNS, ...). */
private class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var lastError: IOException? = null
        while (attempt < maxRetries) {
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                lastError = e
                attempt++
            }
        }
        throw lastError!!
    }
}

/**
 * Omezuje pocet soubezne rozjetych pozadavku na stejny host - bez toho umi
 * napr. Hitomi.La zdroj poslat 25 paralelnich pozadavku na stejnou domenu
 * najednou (getPopular natahuje 25 galleryblock karet soubezne), coz je
 * presne vzorec, ktery spousti IP-based rate limiting na strane serveru.
 * Semafor drzi permit po celou dobu chain.proceed() vcetne pripadneho
 * Cloudflare WebView reseni (ktere je tim padem taky prirozene serializovane
 * per-host), ale requesty na JINE domeny nijak neomezuje.
 */
private class ThrottleInterceptor(private val maxConcurrentPerHost: Int = 5) : Interceptor {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val semaphore = semaphores.getOrPut(chain.request().url.host) { Semaphore(maxConcurrentPerHost) }
        semaphore.acquire()
        try {
            return chain.proceed(chain.request())
        } finally {
            semaphore.release()
        }
    }
}

/**
 * Některé CDN vyžadují konkrétní Referer, jinak vrací 403 (hotlink protection) -
 * projevuje se jako "obrázek se nikdy nenačte" pro obálky/thumbnaily, protože
 * Coil sdílí tenhle stejný OkHttpClient. Referer se nastaví jen když ho
 * request ještě nemá (aby to nerozbilo zdroje, které si ho nastavují samy).
 */
private val hotlinkReferers = mapOf(
    "webtoon-phinf.pstatic.net" to "https://www.webtoons.com/",
    "comicbookplus.com" to "https://comicbookplus.com/",
    "cdn.readdetectiveconan.com" to "https://mangapill.com/",
    // Audit 2026-07-27 (6d) - dohledane CDN domeny chybejici v mape:
    "cdn.manhwaz.com" to "https://manhwaz.com/",
    "data.tnlycdn.com" to "https://toonily.com/",
    "shadowabyss.com" to "https://kuramanga.com/",
    "cdn.manhuabuddy.com" to "https://manhuabuddy.com/",
    // weebcentral obrazky prochazely i bez Refereru pri testu, ale pridano
    // defenzivne - hotlink ochrana bezi na strane CDN a muze byt nekonzistentni.
    "hot.planeptune.us" to "https://weebcentral.com/",
)

// Hitomi.La serví thumbnaily (tn.*) i plné stránky (w1.*/w2.*/…) na
// libovolně pojmenovaných subdoménách gold-usergeneratedcontent.net -
// match je proto podle přípony domény, ne přesného hostu.
private val hotlinkRefererSuffixes = mapOf(
    "gold-usergeneratedcontent.net" to "https://hitomi.la/",
    // MangaTown obrazky bezi na ruznych CDN subdomenach mangahere sit (zjcdn.mangahere.org,
    // fmcdn.mangahere.com, ...) - suffix match pokryje obe TLD varianty.
    "mangahere.org" to "https://www.mangatown.com/",
    "mangahere.com" to "https://www.mangatown.com/",
    // Comizy (drive MangaBuddy) servi obrazky na x{N}.cmzcdn.org, cislo subdomeny
    // se meni chapter od chapteru - suffix match pokryje vsechny varianty.
    "cmzcdn.org" to "https://comizy.io/",
    // MangaDoom servi obrazky na nahodne pojmenovanych subdomenach redirectto.cc
    // (napr. 9giiu0g54k8c.redirectto.cc) - suffix match pokryje vsechny varianty.
    "redirectto.cc" to "https://manga-doom.com/",
)

// MangaK servi obrazky na rx.{nahodne-slovo}.org - CELA druha uroven domeny
// (ne jen subdomena) se meni chapter od chapteru (rx.qvzrg.org, rx.resmk.org, ...),
// takze ani presny host, ani prípona nefunguje spolehlive. Spolecny je jen prefix
// "rx." - match je proto podle zacatku hostu.
private val hotlinkRefererPrefixes = mapOf(
    "rx." to "https://mangak.io/",
)

/**
 * OkHttpovo vychozi ConnectionSpec.MODERN_TLS pouziva uzsi a jinak
 * seřazenou sadu cipher suites, nez jakou v TLS ClientHello nabizi realny
 * Chrome na Androidu - anti-bot systemy (Cloudflare aj.) tohle pouzivaji
 * jako jeden ze signalu ("vypada to jako knihovna, ne prohlizec").
 * Tahle sada kopiruje poradi cipher suites, ktere Chrome pro Android
 * skutecne nabizi. POZOR: nejde o plnohodnotny JA3/TLS fingerprint spoofing -
 * presne poradi TLS extensions a GREASE hodnoty OkHttp/Conscrypt neumoznuje
 * ovlivnit (na to by bylo potreba vlastni TLS stack, napr. Cronet/BoringSSL),
 * a moderni Chrome (110+) navic poradi extensions sam nahodne mixuje prave
 * proto, aby JA3 fingerprinting znejistil. Tohle je tedy jen "co nejlepsi
 * priblizeni" pres verejne OkHttp API, ne exaktni klon.
 */
private val chromeLikeConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    .cipherSuites(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
    )
    .build()

private class HotlinkRefererInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Referer") != null) return chain.proceed(request)

        val host = request.url.host
        val referer = hotlinkReferers[host]
            ?: hotlinkRefererSuffixes.entries.find { (suffix, _) -> host == suffix || host.endsWith(".$suffix") }?.value
            ?: hotlinkRefererPrefixes.entries.find { (prefix, _) -> host.startsWith(prefix) }?.value

        val finalRequest = if (referer != null) {
            request.newBuilder().header("Referer", referer).build()
        } else {
            request
        }
        return chain.proceed(finalRequest)
    }
}

/**
 * Na HTTP 429 vyhodí [SourceRateLimitedException] místo obyčejné odpovědi - viz [Throwable.toFriendlyMessage]
 * pro srozumitelnou hlášku uživateli. Výjimka záměrně NENÍ IOException, takže ji [RetryInterceptor]
 * (umístěný před tímhle v řetězci) nezachytí a nebude zbytečně opakovat request, který stejně
 * zůstane rate-limitovaný.
 */
private class RateLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            val retryAfterMs = response.header("Retry-After")?.let { parseRetryAfterMs(it) } ?: 0L
            response.close()
            throw SourceRateLimitedException(retryAfterMs)
        }
        return response
    }
}

/**
 * `internal` (ne `private`) a mimo třídu, aby to šlo přímo zavolat z čistého JVM testu
 * (stejný vzor jako `isCloudflareBlocked` v `CloudflareInterceptor.kt`). Parsuje `Retry-After`
 * hlavičku - buď počet sekund (RFC 7231), nebo HTTP-date formát. `null`, když ani jedno
 * neodpovídá.
 */
internal fun parseRetryAfterMs(header: String): Long? {
    header.toLongOrNull()?.let { return it * 1000 }
    return try {
        ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - System.currentTimeMillis()
    } catch (_: Exception) {
        null
    }
}

/**
 * DNS-over-HTTPS přes Cloudflare - pomáhá na sítích, kde ISP/router DNS pro manga zdroje
 * blokuje nebo zpomaluje (běžné u některých poskytovatelů/zemí). Bootstrap IP adresy jsou
 * nutné, aby appka vůbec našla `cloudflare-dns.com` bez kruhové závislosti na DNS, které se
 * má teprve použít - stejné adresy, jaké má Cloudflare veřejně zdokumentované jako svůj
 * resolver (1.1.1.1/1.0.0.1). Při selhání (`UnknownHostException`) appka spadne zpátky na
 * systémové DNS - DoH se tím nikdy nemůže appku "zaseknout", jen v nejhorším případě nepomůže.
 */
private object CloudflareDoh : Dns {
    private val bootstrapClient = OkHttpClient.Builder().build()

    private val delegate: Dns by lazy {
        DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                listOfNotNull(
                    tryGetByIp("1.1.1.1"),
                    tryGetByIp("1.0.0.1"),
                    tryGetByIp("2606:4700:4700::1111"),
                    tryGetByIp("2606:4700:4700::1001"),
                ),
            )
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> = try {
        delegate.lookup(hostname)
    } catch (_: UnknownHostException) {
        Dns.SYSTEM.lookup(hostname)
    }

    private fun tryGetByIp(ip: String): InetAddress? = try {
        InetAddress.getByName(ip)
    } catch (_: UnknownHostException) {
        null
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(cloudflare: CloudflareInterceptor): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(CloudflareDoh)
        .connectionSpecs(listOf(chromeLikeConnectionSpec, ConnectionSpec.COMPATIBLE_TLS))
        .addInterceptor(ThrottleInterceptor(maxConcurrentPerHost = 5))
        .addInterceptor(RetryInterceptor(maxRetries = 3))
        .addInterceptor(RateLimitInterceptor())
        .addInterceptor(HotlinkRefererInterceptor())
        .addInterceptor(cloudflare)
        .build()

    @Provides
    @Singleton
    @ImageHttpClient
    fun provideImageHttpClient(cloudflare: CloudflareInterceptor): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(CloudflareDoh)
        .connectionSpecs(listOf(chromeLikeConnectionSpec, ConnectionSpec.COMPATIBLE_TLS))
        .addInterceptor(RateLimitInterceptor())
        .addInterceptor(HotlinkRefererInterceptor())
        .addInterceptor(cloudflare)
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "jiyu.db")
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26,
                AppDatabase.MIGRATION_26_27,
                AppDatabase.MIGRATION_27_28,
                AppDatabase.MIGRATION_28_29,
                AppDatabase.MIGRATION_29_30,
                AppDatabase.MIGRATION_30_31,
                AppDatabase.MIGRATION_31_32,
                AppDatabase.MIGRATION_32_33,
                AppDatabase.MIGRATION_33_34,
            )
            .build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides fun provideMangaDao(db: AppDatabase): MangaDao = db.mangaDao()
    @Provides fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()
    @Provides fun provideTranslatedPageDao(db: AppDatabase): TranslatedPageDao = db.translatedPageDao()
    @Provides fun provideTranslatedNovelDao(db: AppDatabase): TranslatedNovelDao = db.translatedNovelDao()
    @Provides fun provideGlossaryDao(db: AppDatabase): GlossaryDao = db.glossaryDao()
    @Provides fun provideManualTranslationDao(db: AppDatabase): com.haise.jiyu.data.db.ManualTranslationDao = db.manualTranslationDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideCustomSourceDao(db: AppDatabase): CustomSourceDao = db.customSourceDao()
    @Provides fun provideReadHistoryDao(db: AppDatabase): ReadHistoryDao = db.readHistoryDao()
    @Provides fun provideMangaNoteDao(db: AppDatabase): MangaNoteDao = db.mangaNoteDao()
    @Provides fun provideMangaTagDao(db: AppDatabase): MangaTagDao = db.mangaTagDao()

    @Provides
    @Singleton
    fun provideSleepTimerManager(): SleepTimerManager = SleepTimerManager()

    @Provides
    @Singleton
    fun provideMangaCloudSession(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
    ): MangaCloudSession = WebViewMangaCloudSession(context, dataStore)
}

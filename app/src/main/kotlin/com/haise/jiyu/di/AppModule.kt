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
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

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
 * Některé CDN vyžadují konkrétní Referer, jinak vrací 403 (hotlink protection) -
 * projevuje se jako "obrázek se nikdy nenačte" pro obálky/thumbnaily, protože
 * Coil sdílí tenhle stejný OkHttpClient. Referer se nastaví jen když ho
 * request ještě nemá (aby to nerozbilo zdroje, které si ho nastavují samy).
 */
private val hotlinkReferers = mapOf(
    "rx.resmk.org" to "https://mangak.io/",
    "webtoon-phinf.pstatic.net" to "https://www.webtoons.com/",
)

// Hitomi.La serví thumbnaily (tn.*) i plné stránky (w1.*/w2.*/…) na
// libovolně pojmenovaných subdoménách gold-usergeneratedcontent.net -
// match je proto podle přípony domény, ne přesného hostu.
private val hotlinkRefererSuffixes = mapOf(
    "gold-usergeneratedcontent.net" to "https://hitomi.la/",
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

        val finalRequest = if (referer != null) {
            request.newBuilder().header("Referer", referer).build()
        } else {
            request
        }
        return chain.proceed(finalRequest)
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
        .connectionSpecs(listOf(chromeLikeConnectionSpec, ConnectionSpec.COMPATIBLE_TLS))
        .addInterceptor(RetryInterceptor(maxRetries = 3))
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
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideCustomSourceDao(db: AppDatabase): CustomSourceDao = db.customSourceDao()
    @Provides fun provideReadHistoryDao(db: AppDatabase): ReadHistoryDao = db.readHistoryDao()
    @Provides fun provideMangaNoteDao(db: AppDatabase): MangaNoteDao = db.mangaNoteDao()
    @Provides fun provideMangaTagDao(db: AppDatabase): MangaTagDao = db.mangaTagDao()

    @Provides
    @Singleton
    fun provideSleepTimerManager(): SleepTimerManager = SleepTimerManager()
}

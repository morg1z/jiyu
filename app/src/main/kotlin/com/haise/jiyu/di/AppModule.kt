package com.haise.jiyu.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.haise.jiyu.BuildConfig
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
import com.haise.jiyu.source.interceptor.DomainOverrideInterceptor
import com.haise.jiyu.source.interceptor.CacheLimitInterceptor
import com.haise.jiyu.source.interceptor.DomainOverrides
import com.haise.jiyu.source.interceptor.ImageCloudflareInterceptor
import com.haise.jiyu.source.interceptor.WebViewCookieInterceptor
import com.haise.jiyu.source.SourceSlowdown
import com.haise.jiyu.source.interceptor.ImageProxyConfig
import com.haise.jiyu.source.interceptor.ImageProxyInterceptor
import com.haise.jiyu.source.interceptor.NetworkProxyConfig
import com.haise.jiyu.source.interceptor.NoNetworkInterceptor
import com.haise.jiyu.source.interceptor.RateLimitInterceptor
import com.haise.jiyu.source.interceptor.SlowdownInterceptor
import com.haise.jiyu.source.interceptor.RetryInterceptor
import com.haise.jiyu.source.interceptor.SlowRequestLogInterceptor
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.source.mangacloud.MangaCloudSession
import com.haise.jiyu.source.mangacloud.WebViewMangaCloudSession
import okhttp3.Cache
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
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

/**
 * Klient pro volitelný "bring your own key" vlastní LLM endpoint (viz [com.haise.jiyu.translate.ByokTranslateClient],
 * item 14 v plánu) - uživatelem zadaná URL, klidně lokální síť/self-hosted server (Ollama,
 * LM Studio...), ne veřejné CDN za Cloudflare. Proto BEZ [CloudflareInterceptor]/vlastního
 * DNS (mohly by rozbít resolving lokální/privátní adresy) a BEZ [RetryInterceptor] (opakování
 * requestu na PLACENÉ uživatelovo API při network chybě by ho mohlo zbytečně vyúčtovat
 * dvakrát) - jen delší read timeout, protože lokální/pomalejší modely můžou generovat desítky
 * sekund.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LlmHttpClient

/**
 * Klient pro překladový proxy (Supabase edge function translate-proxy - viz GroqTranslateClient a
 * GeminiTranslateClient). Sdílený výchozí klient se pro něj nehodil hned ve třech věcech (audit nálezy
 * JIYU-NET-2/NET-3): (1) jeho [RateLimitInterceptor] házel při 429 SourceRateLimitedException dřív, než
 * odpověď došla ke kódu klientů, takže jejich větev "kvóta vyčerpána" (markAllUnavailable +
 * RateLimitedException) byla mrtvá; (2) [RetryInterceptor] opakoval každý IOException 3× bez prodlevy
 * uvnitř klientských MAX_ATTEMPTS = 2, takže z jednoho překladu bylo až 6 POSTů a proxy dál generovala
 * pro každý opuštěný pokus a pálila sdílenou kvótu; (3) 30s read timeout je na odpověď LLM krátký.
 * Proto tady BEZ Retry/RateLimit/Throttle a s delším timeoutem; opakování řeší jen klient, jednou a s prodlevou.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TranslateProxyHttpClient

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
    // LruCache (ne ConcurrentHashMap) - appka za dobu behu mluvi s desitkami zdroju + jejich
    // CDN hostiteli, bez stropu by mapa rostla neomezene po celou dobu behu procesu (audit
    // nalez). Evikce zaznamu NEPOSKODI prave bezici pozadavek - ten uz drzi PRIMOU referenci
    // na svuj Semaphore objekt, .release() na ni funguje bez ohledu na to, jestli jeste je
    // v mape. Nejhorsi dusledek evikce je, ze se pro znovu-navstiveneho hostitele vytvori
    // novy semafor od nuly - prijatelny kompromis oproti neomezenemu rustu.
    private val semaphores = object : android.util.LruCache<String, Semaphore>(MAX_TRACKED_HOSTS) {}

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val semaphore = synchronized(semaphores) {
            semaphores.get(host) ?: Semaphore(maxConcurrentPerHost).also { semaphores.put(host, it) }
        }
        semaphore.acquire()
        try {
            return chain.proceed(chain.request())
        } finally {
            semaphore.release()
        }
    }

    private companion object {
        const val MAX_TRACKED_HOSTS = 64
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
 * `internal` (ne `private`) a mimo třídu, aby to šlo přímo zavolat z čistého JVM testu
 * (stejný vzor jako `isCloudflareBlocked` v `CloudflareInterceptor.kt`). Parsuje `Retry-After`
 * hlavičku - buď počet sekund (RFC 7231), nebo HTTP-date formát. `null`, když ani jedno
 * neodpovídá.
 */
internal fun parseRetryAfterMs(header: String): Long? {
    // coerceAtLeast(0) - poskozena/minula HTTP-date hlavicka (nebo zaporne cislo sekund) by
    // jinak vratila zaporny cas. Dnesnimu jedinemu volajicimu (Throwable.toFriendlyMessage,
    // ktery uz zapor/nulu bere stejne) na tom nezalezi, ale je to levna pojistka proti
    // budoucimu volajicimu, co by tenhle predpoklad necekane porusil.
    header.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
    return try {
        (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}

/**
 * DNS-over-HTTPS přes Cloudflare - pomáhá na sítích, kde ISP/router DNS pro manga zdroje
 * blokuje nebo zpomaluje (běžné u některých poskytovatelů/zemí). Bootstrap IP adresy jsou
 * nutné, aby appka vůbec našla `cloudflare-dns.com` bez kruhové závislosti na DNS, které se
 * má teprve použít - stejné adresy, jaké má Cloudflare veřejně zdokumentované jako svůj
 * resolver (1.1.1.1/1.0.0.1). Při jakémkoli selhání DoH dotazu (`IOException` - nejen
 * `UnknownHostException`, ale i timeout/TLS chyba při komunikaci s cloudflare-dns.com samotným,
 * viz audit nalez) appka spadne zpátky na systémové DNS - DoH se tím nikdy nemůže appku
 * "zaseknout", jen v nejhorším případě nepomůže.
 */
private object CloudflareDoh : Dns {
    // Krátké timeouty: kdyby byl 1.1.1.1 na síti zablokovaný, výchozí 10s connect timeout by
    // zdržel první request; DoH pak spadne na systémové DNS (viz lookup).
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

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
    } catch (_: IOException) {
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
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        cloudflare: CloudflareInterceptor,
        domainOverrides: DomainOverrides,
        networkMonitor: NetworkMonitor,
        slowdown: SourceSlowdown,
        proxyConfig: NetworkProxyConfig,
    ): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(proxyConfig.selector)
        .proxyAuthenticator(proxyConfig.authenticator)
        // HTTP cache zdrojů: podmíněné dotazy (ETag/Last-Modified -> 304) šetří přenos na slabém signálu a JSON API
        // zdrojů se cachuje. Zastaralý obsah hlídá CacheLimitInterceptor (max 10 min / vždy ověřit). Obrázky mají
        // vlastní cache v Coilu, proto ji @ImageHttpClient nemá.
        .cache(Cache(context.cacheDir.resolve("http_cache"), 50L * 1024 * 1024))
        .addNetworkInterceptor(CacheLimitInterceptor())
        // Connect timeout 15 s (dřív 30): mrtvé spojení se pozná dřív a RetryInterceptor stihne jeden druhý pokus.
        // Read timeout zůstává 30 s - pomalá mobilní síť může data posílat pomalu, ale průběžně.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(CloudflareDoh)
        .connectionSpecs(listOf(chromeLikeConnectionSpec, ConnectionSpec.COMPATIBLE_TLS))
        .addInterceptor(DomainOverrideInterceptor(domainOverrides))
        .addInterceptor(NoNetworkInterceptor { networkMonitor.isOnline })
        .apply { if (BuildConfig.DEBUG) addInterceptor(SlowRequestLogInterceptor()) }
        .addInterceptor(SlowdownInterceptor(slowdown))
        .addInterceptor(WebViewCookieInterceptor())
        // Cloudflare PŘED Throttle/Retry: řešení výzvy (tiché až 18 s + dialog až 90 s) pak nedrží povolení hostitele
        // a RetryInterceptor ho neopakuje - opakují se jen skutečné síťové pokusy uvnitř.
        .addInterceptor(cloudflare)
        .addInterceptor(ThrottleInterceptor(maxConcurrentPerHost = 5))
        .addInterceptor(RetryInterceptor())
        .addInterceptor(RateLimitInterceptor(slowdown))
        .addInterceptor(HotlinkRefererInterceptor())
        .build()

    @Provides
    @Singleton
    @ImageHttpClient
    fun provideImageHttpClient(
        cloudflare: CloudflareInterceptor,
        domainOverrides: DomainOverrides,
        networkMonitor: NetworkMonitor,
        slowdown: SourceSlowdown,
        imageProxyConfig: ImageProxyConfig,
        proxyConfig: NetworkProxyConfig,
    ): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(proxyConfig.selector)
        .proxyAuthenticator(proxyConfig.authenticator)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // callTimeout ohranicuje CELKOVY cas requestu (connect+write+read dohromady), na
        // rozdil od readTimeout vyse (ktery hlida jen mezeru MEZI jednotlivymi byte cteni).
        // Spojeni, ktere "kape" data kousek pod hranici readTimeoutu, by jinak mohlo bezet
        // podstatne dele nez 30s a bez konce - presne pripad pomale/kolisave mobilni site.
        // Zamerne BEZ navratu RetryInterceptoru (viz komentar tridy vyse) - jde jen o strop
        // jednoho pokusu, ne o opakovani.
        // 120 s (dřív 45): stránky webtoonu bývají 3-5 MB a čtečka jich tahá několik souběžně - na pomalejším
        // mobilním připojení se sdílená rychlost rozdělí a jeden obrázek pak trvá přes 45 s, takže
        // se stránka "nenačetla", i když spojení běželo. Zaseknuté spojení pořád hlídá readTimeout (30 s
        // bez dat), tenhle strop je jen pojistka proti pomalému, ale živému stahování.
        .callTimeout(120, TimeUnit.SECONDS)
        .dns(CloudflareDoh)
        .connectionSpecs(listOf(chromeLikeConnectionSpec, ConnectionSpec.COMPATIBLE_TLS))
        .addInterceptor(DomainOverrideInterceptor(domainOverrides))
        .addInterceptor(NoNetworkInterceptor { networkMonitor.isOnline })
        .apply { if (BuildConfig.DEBUG) addInterceptor(SlowRequestLogInterceptor()) }
        .addInterceptor(SlowdownInterceptor(slowdown))
        // Volitelný úsporný režim obrázků (výchozí vypnuto) - před cookies/Cloudflare, aby požadavek na proxy
        // nenesl cookies zdroje a při selhání šel původní požadavek celým řetězcem jako dřív.
        .addInterceptor(ImageProxyInterceptor(imageProxyConfig))
        // Bez WebViewCookieInterceptor: obrázky (desítky obálek a log najednou) nepotřebují session cookies a dotaz
        // na CookieManager je při stovkách požadavků zbytečně drahý. Hotlink ochrany řeší Referer.
        .addInterceptor(RateLimitInterceptor(slowdown))
        .addInterceptor(HotlinkRefererInterceptor())
        // Obrázky Cloudflare výzvy NEŘEŠÍ (žádný WebView/dialog/zámek hostitele) - jen použijí už získanou clearance
        // a jinak rychle selžou; jinak by čekající obrázek držel vlákno a slot hostitele a zdržel obálky i loga.
        .addInterceptor(ImageCloudflareInterceptor(cloudflare))
        // Víc souběžných požadavků na jednoho hostitele (výchozí 5): obálky, loga a stránky kapitoly často sdílejí
        // jedno CDN a při pár pomalých stránkách by jinak ostatní obrázky čekaly ve frontě.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 10 })
        .build()

    @Provides
    @Singleton
    @TranslateProxyHttpClient
    fun provideTranslateProxyHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .dns(CloudflareDoh)
        .connectionSpecs(listOf(chromeLikeConnectionSpec, ConnectionSpec.COMPATIBLE_TLS))
        .build()

    @Provides
    @Singleton
    @LlmHttpClient
    fun provideLlmHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "jiyu.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // Nejnizsi registrovana migrace je 3->4 - instalace, ktera by (teoreticky, appka
            // od te doby vydala uz 120+ verzi) porad sedela na DB verzi 1 nebo 2, by jinak
            // spadla na "migrace nenalezena" pri kazdem startu, misto aby normalne nabehla.
            // Radsi cista prestavba DB (ztrata jen lokalni cache u extremne stare instalace)
            // nez tvrdy pad - viz audit nalez "chybejici migrace 1->2/2->3".
            .fallbackToDestructiveMigrationFrom(1, 2)
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

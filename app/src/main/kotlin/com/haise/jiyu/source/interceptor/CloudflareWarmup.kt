package com.haise.jiyu.source.interceptor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.haise.jiyu.di.ImageHttpClient
import com.haise.jiyu.settings.SettingsKeys
import com.haise.jiyu.util.CloudflareBlockedException
import com.haise.jiyu.util.CloudflareProtectedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Proaktivni reseni Cloudflare vyzv na pozadi, aby uzivatel pri prochazeni CF-chraneneho zdroje
 * nikdy necekal na WebView solve (ani nevidel neviditelny auto-tap pokus). Zdroje zname jako
 * CF-chranene se drzi v perzistentni sade ([SettingsKeys.CLOUDFLARE_WARMUP_HOSTS]):
 *
 * - **Seed** - pevny seznam z auditovanych CF webu (viz [SEED_HOSTS]).
 * - **Uceni za behu** - [CloudflareInterceptor] zaznamena kazdy host, ktery vraci CF vyzvu
 *   (i z obrazkovych CDN, i z noSolve hromadnych operaci) - novy CF web se tak do warm-upu
 *   prida sam, bez zasahu do kodu.
 * - **Samo-cisteni** - web, co CF vypnul, se po probe (levny GET pres image klienta) ze sady
 *   vyradi, aby se na nem zbytecne nespoustel WebView.
 *
 * Beh: [start] se vola jednou z `JiyuApp.onCreate`. Warm-up pak jede
 * 1. kratce po startu (detached tichy WebView - Managed Challenge se vyresi i bez UI),
 * 2. pri kazdem prechodu UI do popredi ([CloudflareChallengeBridge.uiActive]) - doběhnou
 *    hosty, co potrebuji Turnstile auto-tap (ten chce pripojeny WebView),
 * 3. pri obnoveni site (cf_clearance je vazana na IP - prepnuti WiFi/mobilni data ji zabije,
 *    proto kolo s `revalidate` proveri i hosty s formalne platnou cache),
 * 4. periodicky (clearance cache drzi 2 h, realna cookie casto dele - re-warm udrzi cerstvost),
 * 5. on-demand pres [kick] - jakykoli request (i obrazek nebo hromadna operace), co narazi
 *    na CF, okamzite zaridi reseni toho konkretniho hosta na pozadi.
 *
 * Vsechno jede sekvencne (jeden WebView naraz), s malymi prestavkami a backoffem po selhani -
 * warm-up je "best effort" optimalizace, nikdy nesmi brzdit appku ani spamovat web.
 */
@Singleton
class CloudflareWarmup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val interceptor: CloudflareInterceptor,
    @param:ImageHttpClient private val probeClient: OkHttpClient,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()

    /** Hosti prave reseni (kick i kola sdili stejny WebView zdroj - dedup). */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Spusti automaticky warm-up - volat jednou z Application.onCreate. Idempotentni. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // 1) Pocatecni kolo - zpozdene, at WebView nesoutezi se startem appky o main thread.
        scope.launch {
            delay(INITIAL_DELAY_MS)
            warmupOnce()
        }
        // 2) Dobeh pri objeveni UI - Turnstile hosty, co detached WebView nevyresil.
        scope.launch {
            CloudflareChallengeBridge.uiActive.collect { active ->
                if (active) warmupOnce()
            }
        }
        // 3) Zmena site = nova IP = clearance neplati (viz trida) - revalidace vsech hostu.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch { warmupOnce(revalidate = true) }
                    }
                })
        }
        // 4) Periodicky re-warm - vetsina kol skonci hned (vsechny hosty maji platnou clearance).
        scope.launch {
            while (true) {
                delay(REWARM_INTERVAL_MS)
                warmupOnce()
            }
        }
        // 5) On-demand kicky z interceptoru - host, co prave bloknul request, se resi hned.
        scope.launch {
            kicks.collect { host -> warmupHost(host) }
        }
    }

    private fun isOnline(): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    }.getOrDefault(true) // kdyz se stav site zjistit neda, zkusit normalne (probe sam selze rychle)

    /**
     * Jedno kompletni kolo pres vsechny dluzne hosty. [revalidate] (zmena site): proveri i hosty
     * s formalne platnou clearance - ta po prepnuti site (nova IP) typicky neplati; probe pres
     * skutecny request to pozna a host se pre-resi.
     */
    private suspend fun warmupOnce(revalidate: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            if (!isOnline()) return
            val now = System.currentTimeMillis()
            val json = dataStore.data.first()[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS]
            val due = warmupHostsDue(json, SEED_HOSTS, now, RETRY_BACKOFF_MS)
            for (host in due) {
                if (!coroutineContext.isActive) break
                if (!revalidate && interceptor.hasValidClearance(host)) continue
                // Kick z prave bloknuteho requestu muze na tento host spustit vlastni solve
                // - inFlight dedup zabrani paralelnimu WebView na stejnem hostu.
                if (!inFlight.add(host)) continue
                try {
                    when (probeProtected(host)) {
                        // Web CF vypnul - ze sady pryc. Pri revalidate probe bezel S pripadnou
                        // starou clearance, takze 200 nic nevypovida o vypnuti CF - host zustava.
                        false -> if (!revalidate) removeHost(host)
                        null -> continue                    // sit/DNS chyba - priste znovu
                        true -> solveAndTrack(host)         // stale chraneny - vyresit
                    }
                } finally {
                    inFlight -= host
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Cilene reseni jednoho hosta z [kick] - request na nej prave narazil na CF, takze
     * probe se preskakuje (dukaz je cerstvy) a backoff se ignoruje (uzivatelska poptavka
     * ma prednost pred setrenim).
     */
    private suspend fun warmupHost(host: String) {
        if (interceptor.hasValidClearance(host)) return
        if (!inFlight.add(host)) return
        try {
            solveAndTrack(host)
        } finally {
            inFlight -= host
        }
    }

    /** WebView solve + zapis backoffu. Selhani bez UI se nepocita - viz komentar nize. */
    private suspend fun solveAndTrack(host: String) {
        val uiWasActive = CloudflareChallengeBridge.hasUi
        val ok = runCatching { interceptor.solveForWarmup("https://$host/") }.getOrDefault(false)
        // Backoff se zapisuje jen po selhani S UI (auto-tap selhal = realne selhani). Detached
        // WebView u Turnstile hosta selze vzdy - to neni "realne" selhani, jen nedostatek
        // kontextu; s backoffem by se po objeveni UI host 30 min preskakoval a auto-tap
        // by se nikdy nespustil.
        if (!ok && uiWasActive) markFailed(host, System.currentTimeMillis())
        if (ok) delay(INTER_HOST_DELAY_MS)
    }

    /**
     * Levny probe: GET homepage pres image klienta (ma [ImageCloudflareInterceptor] - CF blok
     * hodi typovanou vyjimku, neceka na reseni). `true` = porad CF, `false` = volny web,
     * `null` = nedosazitelny (stav neznamy).
     */
    private fun probeProtected(host: String): Boolean? = try {
        probeClient.newCall(Request.Builder().url("https://$host/").build()).execute().use { resp ->
            if (resp.isSuccessful) false else if (isCloudflareBlocked(resp)) true else null
        }
    } catch (_: CloudflareProtectedException) {
        true
    } catch (_: CloudflareBlockedException) {
        true
    } catch (_: Exception) {
        null
    }

    private suspend fun markFailed(host: String, now: Long) {
        try {
            dataStore.edit { prefs ->
                val json = prefs[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS]
                prefs[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS] = markWarmupFailed(json, host, now)
            }
        } catch (_: Exception) { /* jen backoff metadata */ }
    }

    private suspend fun removeHost(host: String) {
        try {
            dataStore.edit { prefs ->
                val json = prefs[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS]
                prefs[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS] = removeWarmupHost(json, host)
            }
        } catch (_: Exception) { }
    }

    companion object {
        private val INITIAL_DELAY_MS = TimeUnit.SECONDS.toMillis(8)
        private val INTER_HOST_DELAY_MS = TimeUnit.SECONDS.toMillis(2)
        private val REWARM_INTERVAL_MS = TimeUnit.MINUTES.toMillis(45)
        /** Po selhani warm-up pokusu S UI se host zkusi znovu az za tohle - trvale blok se nedridi. */
        private val RETRY_BACKOFF_MS = TimeUnit.MINUTES.toMillis(30)

        /**
         * Kicky z interceptoru (host, co prave bloknul request) - mimo [start] (testy, proces bez
         * appky) se hodi zahodi, jinak je zbytecne bufferovat: po startu se stejne hraje plne kolo.
         */
        private val kicks = MutableSharedFlow<String>(extraBufferCapacity = 8)
        private val kickTimes = ConcurrentHashMap<String, Long>()
        private val KICK_THROTTLE_MS = TimeUnit.SECONDS.toMillis(90)

        /**
         * Zaznamenany CF host se okamzite zaridi k vyreseni na pozadi - vola CloudflareInterceptor.
         * Davka blokovanych obrazku na jednom hostu by jinak spoustela pokus o reseni pro
         * kazdy request - proto throttling na jeden kick za [KICK_THROTTLE_MS] na host.
         */
        internal fun kick(host: String) {
            val now = System.currentTimeMillis()
            val last = kickTimes[host] ?: 0L
            if (now - last < KICK_THROTTLE_MS) return
            kickTimes[host] = now
            kicks.tryEmit(host)
        }

        /**
         * Zname CF-chranene hosty z live auditu (2026-10) - seed, ke kteremu se za behu
         * pridavaji hosty naucene z interceptoru (viz [mergeWarmupHost] volani).
         */
        internal val SEED_HOSTS = setOf(
            "batcave.biz",
            "www.japscan.foo",
            "www.silentquill.net",
            "www.team-shadowi.com",
            "mangadot.net",
            "mangafire.to",
            "dragontea.ink",
            "v4.luvyaa.co",
            "linkmanga.com",
            "www.manhwa18.today",
        )
    }
}

// ─── Ciste funkce nad persistovanym JSON (kvuli JVM testum bez Androidu) ──────
// Format: {"host": lastFailAtMs} - klic = CF-chraneny host, hodnota = cas posledniho
// neuspesneho warm-up pokusu S UI (0 = jeste nikdy realne neselhalo). Hosty s platnou
// clearance se preskakuji jeste predtim, nez se na backoff vubec koukne.

/**
 * Prida host do sady (existujici lastFailAt zachova). Kapacita je omezena - naučene hosty
 * zapisuje interceptor podle CF odpovedi, a web (nebo jeho inzerat/obrazkovy CDN) by mohl
 * vracet CF-vypadajici 403 naschval a sadu si nadnafukovat - warm-up by pak spoustel
 * WebView na stovky cizich domen (audit). Pres strop se nove hosty ignoruji - seed a
 * dosavadni nauceni zustavají.
 */
internal const val MAX_WARMUP_HOSTS = 100
internal fun mergeWarmupHost(json: String?, host: String): String {
    val obj = json.toWarmupJson()
    if (!obj.has(host) && obj.length() >= MAX_WARMUP_HOSTS) return obj.toString()
    if (!obj.has(host)) obj.put(host, 0L)
    return obj.toString()
}

/** Zapise cas neuspesneho warm-up pokusu (ridici backoff). */
internal fun markWarmupFailed(json: String?, host: String, now: Long): String {
    val obj = json.toWarmupJson()
    obj.put(host, now)
    return obj.toString()
}

/** Vyradi host ze sady (web CF vypnul - viz CloudflareWarmup.probeProtected). */
internal fun removeWarmupHost(json: String?, host: String): String {
    val obj = json.toWarmupJson()
    obj.remove(host)
    return obj.toString()
}

/**
 * Hosty ke spracovani v tomto kole: naucene (abecedne, deterministicky - JSONObject.keys()
 * poradi nedrzi) driv, protoze jsou to hosty, na ktere uzivatel realne narazil; seed az po nich.
 * Hosty v backoffu po selhani se preskakuji.
 */
internal fun warmupHostsDue(json: String?, seed: Set<String>, now: Long, backoffMs: Long): List<String> {
    val obj = json.toWarmupJson()
    val learned = mutableListOf<String>()
    obj.keys().forEach { learned += it }
    learned.sort()
    val all = LinkedHashSet<String>(learned.size + seed.size)
    all += learned
    all += seed
    return all.filter { host ->
        val lastFail = obj.optLong(host, 0L)
        lastFail <= 0L || now - lastFail >= backoffMs
    }
}

private fun String?.toWarmupJson(): JSONObject =
    if (isNullOrBlank()) JSONObject() else try { JSONObject(this) } catch (_: Exception) { JSONObject() }

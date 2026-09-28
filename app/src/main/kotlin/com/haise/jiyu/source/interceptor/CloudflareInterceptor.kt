package com.haise.jiyu.source.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.haise.jiyu.settings.SettingsKeys
import com.haise.jiyu.util.CloudflareBlockedException
import com.haise.jiyu.util.CloudflareProtectedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudflareInterceptor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * cf_clearance vyresene pres WebView se cachuji per-host, jinak by kazdy
     * dalsi zablokovany pozadavek na tu samou domenu (napr. dalsi stranka
     * vypisu) znovu spoustel cely WebView flow (~1-15s). TTL je konzervativni
     * odhad - Cloudflare Managed Challenge clearance v praxi obvykle vydrzi
     * casto i nekolik hodin, presna doba se ale lisi web od webu a nikde se
     * neda precist z odpovedi predem (2 hodiny je porad konzervativni oproti
     * realne pozorovane dobe, jen o dost min casta nez puvodnich 25 minut).
     * Cache se navic persistuje do DataStore, aby vyresena vyzva prezila i
     * restart appky (jinak by se po kazdem cold startu muselo resit znovu,
     * i kdyz clearance jeste realne plati).
     */
    private data class CachedClearance(val cookies: String, val expiresAt: Long)
    // Vsechny per-host mapy omezene LRU - scrapeovana stranka muze vnutit requesty na
    // libovolne hosty a bez stropu by se cache neomezene nafukovaly (audit).
    private val clearanceCache = com.haise.jiyu.util.boundedLruMap<String, CachedClearance>(MAX_HOSTS)
    private val clearanceTtlMs = TimeUnit.HOURS.toMillis(2)

    /**
     * Kdyz reseni (tiche i interaktivni) pro host selze - typicky trvaly "Sorry,
     * you have been blocked" misto resitelne vyzvy - nema smysl to zkouset znovu
     * pro kazdy dalsi pozadavek na stejnou domenu (dalsi obalka v knihovne, dalsi
     * stranka kapitoly...). Bez tehle cache by se interaktivni dialog objevoval
     * znovu a znovu hned po zavreni predchoziho, prakticky bez moznosti appku
     * pouzivat. Po vyprseni cooldownu se zkusi znovu (treba uz block pominul).
     */
    private val failureCache = com.haise.jiyu.util.boundedLruMap<String, Long>(MAX_HOSTS)
    private val failureCooldownMs = TimeUnit.MINUTES.toMillis(10)

    /** Soubezne pozadavky na stejny host cekaji na JEDNO reseni, ne kazdy spousti vlastni WebView/dialog. */
    private val hostLocks = com.haise.jiyu.util.boundedLruMap<String, Any>(MAX_HOSTS)

    /**
     * Kdyz appka na pozadi souběžně prohledává desitky zdrojů najednou (ComicKChapterResolver -
     * hledani realneho zdroje pro ComicK titul), interaktivni Cloudflare vyzva (viz
     * [CloudflareChallengeBridge]) by uzivatele bombardovala dialogy od zdroju, o ktere se
     * vubec nezajima - jeden po druhem, jak se na synchronizovanem bridge stridaji (uzivatelsky
     * pozadavek: "skáče to od různých zdrojů"). Kdyz je tenhle flag zapnuty, tichy WebView pokus
     * (bezinterakcni Managed Challenge) porad probehne, ale interaktivni fallback se preskoci -
     * zdroj, ktery potrebuje skutecnou CAPTCHU, se proste bere jako nedostupny pro tenhle pokus
     * (presne jako kdyby spadl na chybu site), misto aby prekazel. Explicitni prime prochazeni
     * jednoho zdroje (SourceBrowseScreen) tenhle flag nenastavuje - tam interaktivni vyzva davat
     * smysl porad ma, uzivatel si ho vybral sam.
     */
    @Volatile var suppressInteractiveChallenge: Boolean = false

    /** Globální příznak (ComicK resolver) nebo kontext pozadí - viz [InteractiveChallengePolicy]. */
    private val interactiveSuppressed: Boolean
        get() = suppressInteractiveChallenge || InteractiveChallengePolicy.isSuppressed

    // Načte se líně při prvním požadavku (na vlákně OkHttp), ne v konstruktoru - ten běží při startu
    // Hiltu na main vlákně a runBlocking nad DataStore by tam blokoval start aplikace.
    private val persistedCacheLoaded: Unit by lazy { loadPersistedCache() }

    private fun loadPersistedCache() {
        try {
            val json = runBlocking { dataStore.data.first()[SettingsKeys.CLOUDFLARE_CLEARANCE_CACHE] }
            if (json.isNullOrBlank()) return
            val obj = JSONObject(json)
            val now = System.currentTimeMillis()
            obj.keys().forEach { host ->
                val entry = obj.optJSONObject(host) ?: return@forEach
                val expiresAt = entry.optLong("expiresAt")
                val cookies = entry.optString("cookies")
                if (expiresAt > now && cookies.isNotBlank()) {
                    clearanceCache[host] = CachedClearance(cookies, expiresAt)
                }
            }
        } catch (_: Exception) { /* poskozeny/prazdny zaznam - zacneme s prazdnou cache */ }
    }

    private fun persistCacheAsync() {
        ioScope.launch {
            try {
                val obj = JSONObject()
                // Snapshot pod zamkem mapy - synchronizedMap nema atomickou iteraci
                // jako ConcurrentHashMap a soubezny put by mohl hodit CME (audit).
                val entries = synchronized(clearanceCache) {
                    clearanceCache.entries.map { it.key to it.value }
                }
                entries.forEach { (host, c) ->
                    obj.put(host, JSONObject().put("cookies", c.cookies).put("expiresAt", c.expiresAt))
                }
                dataStore.edit { it[SettingsKeys.CLOUDFLARE_CLEARANCE_CACHE] = obj.toString() }
            } catch (_: Exception) { /* perzistence je jen optimalizace, nesmi shodit request */ }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Hromadné hledání: výzvu neřešit (žádný WebView ani dialog), viz InteractiveChallengePolicy.noSolve.
        if (InteractiveChallengePolicy.isNoSolve) return interceptWithoutSolving(chain)
        persistedCacheLoaded
        val request = chain.request()
        val host = request.url.host

        val cached = clearanceCache[host]?.takeIf { it.expiresAt > System.currentTimeMillis() }
        val requestToTry = if (cached != null) request.withClearance(cached.cookies) else request

        val response = chain.proceed(requestToTry)
        if (!isCloudflareBlocked(response)) return response
        // Zaznamenat host pro proaktivni warm-up - i kdyz je pozadavek v noSolve kontextu
        // (intercept sem pak uz nedojde, takze jedina sance se naucit host je tady).
        recordProtectedHost(host)
        val unsolvable = isUnsolvableWafBlock(response)
        // 403 s JSON tělem = brána na API endpointu (např. "Missing token"), ne stránka s výzvou: řešit se má na
        // hlavní stránce webu (tam se cookie získá), ne načítáním samotného API v neviditelném WebView.
        val jsonGate = response.header("Content-Type")?.contains("json", ignoreCase = true) == true
        response.close()
        if (cached != null) clearanceCache.remove(host)

        if (unsolvable) {
            failureCache[host] = System.currentTimeMillis()
            throw CloudflareBlockedException(host, request.url.toString())
        }

        if (isInFailureCooldown(host)) throw CloudflareProtectedException(host, request.url.toString())

        // getOrPut pod zamkem MAPY - boundedLruMap (synchronizedMap) nema atomicke
        // getOrPut jako ConcurrentHashMap; bez nej by dve vlakna mohla ziskat ruzne
        // zamky pro stejny host a spustit paralelni reseni (audit).
        val lock = synchronized(hostLocks) { hostLocks.getOrPut(host) { Any() } }
        synchronized(lock) {
            // Mezitim uz mohlo jine (souběžné) vlakno pro tenhle host uspet nebo
            // selhat - pokud ano, staci pouzit vysledek, ne spoustet dalsi WebView/dialog.
            clearanceCache[host]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return chain.proceed(request.withClearance(it.cookies))
            }
            if (isInFailureCooldown(host)) throw CloudflareProtectedException(host, request.url.toString())

            // Blokace znamená, že cookies, které jsme poslali (i ty sdílené z WebView), web nechce - smažou se, ať je
            // nový pokus nepovažuje za "už vyřešeno" jen proto, že tam cf_clearance pořád leží.
            CloudflareCookies.clear(request.url.toString())

            val url = if (jsonGate) request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
            else request.url.toString()
            val isCancelled = chain.call()::isCanceled
            val interactive = !interactiveSuppressed && !chain.call().isCanceled()
            val cookies = if (interactive && CloudflareChallengeBridge.hasUi) {
                // Appka je v popředí: dialog si výzvu nejdřív zkusí sám v PŘIPOJENÉM (neviditelném) WebView
                // v reálné velikosti, kde Turnstile funguje spolehlivěji než v odpojeném, a až pak se ukáže.
                // Odpojený tichý WebView by tu jen zbytečně spolkl až 15 s.
                CloudflareChallengeBridge.awaitUserSolve(url, host, timeoutSeconds = AUTO_SOLVE_WAIT_SECONDS, isCancelled = isCancelled)
            } else {
                // Na pozadí (nebo bez obrazovky) jen tichý pokus - vyřeší bezinterakční "Managed Challenge".
                // Fallback na awaitUserSolve jen kdyz UI skutecne sbira vyzvy - jinak by tu vlakno
                // zbytecne stalo cely timeout (nikdo pending neslucha, viz hasUi).
                solveCloudflareSynchronously(url, host, isCancelled)
                    ?: if (interactive && CloudflareChallengeBridge.hasUi) {
                        CloudflareChallengeBridge.awaitUserSolve(url, host, timeoutSeconds = AUTO_SOLVE_WAIT_SECONDS, isCancelled = isCancelled)
                    } else {
                        null
                    }
            }

            if (cookies == null) {
                // Kdyz to bylo "jen" potlacene (viz suppressInteractiveChallenge), nejde o
                // skutecne zjisteny trvaly block - do failureCache se to nedava, aby pozdejsi
                // PRIME prochazeni tohohle zdroje (mimo hromadne hledani) porad dostalo sanci
                // na skutecnou interaktivni vyzvu, misto aby ho cooldown preskocil bez ptani.
                if (!interactiveSuppressed) failureCache[host] = System.currentTimeMillis()
                throw CloudflareProtectedException(host, request.url.toString())
            }

            // Cookie z WebView (cf_clearance) neni zaruka uspechu - napr. kdyz chybi dalsi
            // cookie jako __cf_bm, nebo interaktivni reseni bylo neuplne. Bez tyhle kontroly
            // by se takova neplatna clearance ulozila do cache na 25 minut a uzivatel by
            // dalsich 25 minut dostaval 403 bez jakekoli dalsi sance to zkusit znovu.
            val retried = chain.proceed(request.withClearance(cookies))
            if (isCloudflareBlocked(retried)) {
                failureCache[host] = System.currentTimeMillis()
                retried.close()
                throw CloudflareProtectedException(host, request.url.toString())
            }

            AndroidCookieEditor.flush()
            clearanceCache[host] = CachedClearance(cookies, System.currentTimeMillis() + clearanceTtlMs)
            persistCacheAsync()
            return retried
        }
    }

    /**
     * Varianta pro OBRÁZKY (Coil, čtečka): použije už získanou clearance, ale výzvu nikdy neřeší - žádný WebView,
     * dialog ani zámek hostitele. Obrázek je vedlejší zdroj: kdyby čekal na řešení výzvy, držel by po dobu až
     * desítek sekund vlákno a slot hostitele a s ním se zdržely i ostatní obrázky (obálky, loga). Clearance
     * získává požadavek na stránku zdroje (viz [intercept]); tady stačí rychle selhat.
     */
    fun interceptWithoutSolving(chain: Interceptor.Chain): Response {
        persistedCacheLoaded
        val request = chain.request()
        val host = request.url.host
        val cached = clearanceCache[host]?.takeIf { it.expiresAt > System.currentTimeMillis() }
        val response = chain.proceed(if (cached != null) request.withClearance(cached.cookies) else request)
        if (!isCloudflareBlocked(response)) return response
        // I obrazkove CDN hosty (fmcdn.mfcdn.net apod.) se uci - warm-up pak ziska clearance i pro ne.
        recordProtectedHost(host)
        val unsolvable = isUnsolvableWafBlock(response)
        response.close()
        val url = request.url.toString()
        throw if (unsolvable) CloudflareBlockedException(host, url) else CloudflareProtectedException(host, url)
    }

    /**
     * Ruční "Vyřešit ověření" z chybové hlášky (viz `ErrorAction.SolveCloudflare`): přeskočí cooldown po dřívějším
     * neúspěchu, zkusí tiché řešení a pak ukáže viditelný dialog. Vrací `true`, když se povedlo získat clearance
     * cookies (další požadavek na host ji použije; kdyby přesto nestačila, projde normální cestou znovu).
     */
    suspend fun solveNow(url: String): Boolean = solve(url, waitForUiWhenSilentFails = true)

    /**
     * Proaktivní warm-up na pozadí (viz [CloudflareWarmup]). Na rozdíl od [solveNow] po selhání tichého
     * detached WebView NEČEKÁ na UI výzvu - bez UI by `awaitUserSolve` jen zablokoval vlákno na cely
     * timeout (žádný sběrač [CloudflareChallengeBridge.pending] neexistuje). Turnstile hosty bez UI
     * se nechají selhat a warm-up je doběhne, až se UI objeví (poslouchá [CloudflareChallengeBridge.uiActive]).
     */
    suspend fun solveForWarmup(url: String): Boolean = solve(url, waitForUiWhenSilentFails = false)

    /** Má host platnou clearance v cache (neexpirovana)? Warm-up tak preskoci uz vyresene domeny. */
    fun hasValidClearance(host: String): Boolean {
        persistedCacheLoaded
        return clearanceCache[host]?.expiresAt?.let { it > System.currentTimeMillis() } == true
    }

    /**
     * Poznamena host jako Cloudflare-chraneny do sdilene sady pro [CloudflareWarmup] (persistuje se do
     * DataStore). Volano z vlakna site - zapis se deleguje na [ioScope], aby nikdy neblokoval request.
     * In-memory dedup ([recordedHosts]) zabrani tomu, aby davka obrazku na CF hostu psala do
     * DataStore pro kazdy request zvlast.
     */
    private val recordedHosts = com.haise.jiyu.util.boundedLruMap<String, Boolean>(MAX_HOSTS)

    private fun recordProtectedHost(host: String) {
        // On-demand reseni tohoto konkretniho hosta na pozadi (throttle je uvnitr kicku) -
        // i kdyz tenhle request selze, dalsi uz projdou s cerstvou clearance.
        CloudflareWarmup.kick(host)
        if (recordedHosts.put(host, true) == true) return
        ioScope.launch {
            try {
                dataStore.edit { prefs ->
                    val json = prefs[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS]
                    prefs[SettingsKeys.CLOUDFLARE_WARMUP_HOSTS] = mergeWarmupHost(json, host)
                }
            } catch (_: Exception) { /* zaznam je jen proaktivni optimalizace */ }
        }
    }

    private suspend fun solve(url: String, waitForUiWhenSilentFails: Boolean): Boolean = withContext(Dispatchers.IO) {
        val host = try { url.toHttpUrl().host } catch (_: IllegalArgumentException) { return@withContext false }
        failureCache.remove(host)
        clearanceCache.remove(host)
        CloudflareCookies.clear(url)
        val lock = synchronized(hostLocks) { hostLocks.getOrPut(host) { Any() } }
        val cookies = synchronized(lock) {
            // Ruční řešení běží v popředí: stejně jako v interceptoru jde rovnou přes připojený WebView v dialogu.
            if (CloudflareChallengeBridge.hasUi) {
                CloudflareChallengeBridge.awaitUserSolve(url, host, timeoutSeconds = AUTO_SOLVE_WAIT_SECONDS)
            } else {
                solveCloudflareSynchronously(url, host) { false }
                    ?: if (waitForUiWhenSilentFails) {
                        CloudflareChallengeBridge.awaitUserSolve(url, host, timeoutSeconds = AUTO_SOLVE_WAIT_SECONDS)
                    } else {
                        null
                    }
            }
        }
        if (cookies == null) return@withContext false
        AndroidCookieEditor.flush()
        clearanceCache[host] = CachedClearance(cookies, System.currentTimeMillis() + clearanceTtlMs)
        persistCacheAsync()
        true
    }

    private fun isInFailureCooldown(host: String): Boolean {
        val failedAt = failureCache[host] ?: return false
        if (System.currentTimeMillis() - failedAt >= failureCooldownMs) {
            failureCache.remove(host)
            return false
        }
        return true
    }

    private fun Request.withClearance(cookies: String) = newBuilder()
        .header("Cookie", mergeCookieHeaders(header("Cookie"), cookies))
        // Stejný UA jako WebView, který clearance získal (viz CloudflareUserAgent) - cookie patří konkrétní identitě.
        .header("User-Agent", CloudflareUserAgent.value(context))
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveCloudflareSynchronously(url: String, host: String, isCancelled: () -> Boolean): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        val engineUserAgent = CloudflareUserAgent.value(context)
        // Referenci na WebView drzime mimo post lambdu, aby ho po cancelu slo bezpecne
        // znicit i mimo postovane handlery (poll runnable jinak bezel az do 15s timeoutu).
        val webViewRef = java.util.concurrent.atomic.AtomicReference<WebView?>()

        mainHandler.post {
            lateinit var webView: WebView
            webView = WebView(context).apply {
                webViewRef.set(this)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = engineUserAgent
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        // Misto jednoho pevneho cekani na jeden konkretni cas (drive 3000ms -
                        // u pomalejsich webu/zarizeni casto prilis brzo, cf_clearance jeste
                        // nestihla dorazit) appka teď kontroluje cookie opakovane po 500ms az
                        // do celkoveho limitu (viz fallback nize) - vyresi se hned, jak je
                        // hotovo, misto zbytecneho cekani NEBO zmeskani pozdejsiho vysledku.
                        //
                        // Ne kazdy web pouziva primo Cloudflare Managed Challenge s cf_clearance
                        // cookie - nektere sity (napr. BatCave) maji VLASTNI JS+PoW branu na
                        // urovni originu, co po uspechu jen JS presmeruje (window.location.replace)
                        // zpatky na PUVODNI pozadovanou URL, bez zaruky, ze pritom vubec nastavi
                        // cookie jmenem "cf_clearance". Kdyz se WebView po dokonceni navigace
                        // reálně vrati presne na puvodni cilovou URL (na rozdil od mezikroku typu
                        // "/_c?t=..."), bereme to jako uspesne proslou vyzvu i bez tehle konkretni
                        // cookie - jakekoli cookie, co tam v tu chvili jsou, se pak stejne jeste
                        // overi skutecnym opakovanym requestem (viz CloudflareInterceptor.intercept),
                        // takze falesny uspech tady neproklouzne dal bez kontroly.
                        // Cookie sama nestačí (cf_clearance se může změnit, zatímco mezistránka ještě běží):
                        // hotovo je, až se stránka opakovaně ohlásí jako skutečná (viz SolveTracker).
                        val tracker = SolveTracker()
                        val poll = object : Runnable {
                            override fun run() {
                                if (latch.count == 0L) return
                                val cookies = CookieManager.getInstance().getCookie(finishedUrl)
                                val settledOnTarget = finishedUrl == url
                                val hasClearance = cookies?.contains("cf_clearance") == true ||
                                    (settledOnTarget && !cookies.isNullOrBlank())
                                if (!hasClearance) {
                                    tracker.onPoll(false, null)
                                    mainHandler.postDelayed(this, 500L)
                                    return
                                }
                                webView.evaluateJavascript(CF_PAGE_STATE_JS) { raw ->
                                    if (latch.count == 0L) return@evaluateJavascript
                                    if (tracker.onPoll(true, com.haise.jiyu.util.decodeJsResult(raw))) {
                                        result = cookies
                                        latch.countDown()
                                        webView.destroy()
                                    } else {
                                        mainHandler.postDelayed(this, 500L)
                                    }
                                }
                            }
                        }
                        mainHandler.postDelayed(poll, 500L)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Presny host nebo jeho subdomena - drive `host.contains(host)` povolilo
                        // i evil-a.to / a.to.attacker.com (audit): challenge stranka pak mohla
                        // navigovat skryte WebView na cizi domenu a cist tam jeho cookies.
                        val reqHost = request.url.host.orEmpty()
                        return !(reqHost == host || reqHost.endsWith(".$host"))
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                loadUrl(url)
            }

            mainHandler.postDelayed({
                if (latch.count > 0) {
                    val cookies = CookieManager.getInstance().getCookie(url)
                    val settledOnTarget = webView.url == url
                    result = cookies?.takeIf { it.contains("cf_clearance") || (settledOnTarget && it.isNotBlank()) }
                    latch.countDown()
                    webView.destroy()
                }
            }, 15_000L)
        }

        // Po zrušení volání (zavřená obrazovka, zastavený worker) se přestane čekat - jinak by vlákno a
        // per-host permit držely až 18 s zbytečně.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
        while (System.nanoTime() < deadline && !latch.await(250, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                // Bez countDown+destroy by poll runnable na WebView bezel az do sveho 15s
                // timeoutu - zombie WebView a drzene prostredky i po navratu (audit).
                mainHandler.post {
                    if (latch.count > 0L) {
                        latch.countDown()
                        runCatching { webViewRef.get()?.destroy() }
                    }
                }
                return null
            }
        }
        return result
    }

    companion object {
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36"
    }
}

/**
 * `internal` (ne `private`) a mimo třídu, aby to šlo přímo zavolat z čistého JVM testu
 * (viz CloudflareBlockedDetectionTest) bez nutnosti sestavovat celou [CloudflareInterceptor]
 * (ta potřebuje Android Context + DataStore).
 *
 * `cf-mitigated` je hlavička, kterou Cloudflare posílá SCHVÁLNĚ pro programovou detekci
 * výzvy (hodnoty jako "challenge") - na rozdíl od sniffování textu z těla stránky není
 * závislá na tom, jak zrovna vypadá HTML aktuální verze výzvy (Cloudflare ho v čase mění,
 * viz komentář v [CloudflareInterceptor] - staré textové shody jako "cf-browser-verification"
 * už novým výzvám vůbec neodpovídají a appka se bez týhle hlavičky spoléhala jen na
 * náhodnou shodu `Server: cloudflare` + 403). Text-sniffing zůstává jako fallback pro
 * starší/neobvyklé nasazení, kde by hlavička chyběla.
 */
/** Jak dlouho interceptor čeká na automatické (neviditelné) řešení výzvy; o něco déle než pokus v [CloudflareChallengeHost]. */
private const val AUTO_SOLVE_WAIT_SECONDS = 25L

/** Strop per-host map (clearance/failure/locks/recorded) - viz clearanceCache. */
private const val MAX_HOSTS = 256

internal fun isCloudflareBlocked(response: Response): Boolean {
    if (response.code in listOf(403, 503)) {
        if (response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) return true
        // Hard WAF block ("you have been blocked") neni resitelna vyzva, ale porad jde o
        // blokaci - patri do typed-error vetve (CloudflareBlockedException), ne do passthrough.
        if (isUnsolvableWafBlock(response)) return true
        val body = response.peekBody(8 * 1024).string()
        if (body.contains("cf-browser-verification") ||
            body.contains("challenge-running") ||
            body.contains("jschl_vc") ||
            body.contains("cf_clearance") ||
            body.contains("challenge-platform") ||
            // Fallback "Server: cloudflare + 403" - ale jen kdyz telo vypada jako
            // resitelna challenge STRANKA. Drive stacily hlavicky samotne a JAKAKOLI
            // obycejna 403 (WAF hard block, geo-block, spatny Referer na CDN za CF edge)
            // spustila cele solve-flow vcetne interaktivniho WebView dialogu (audit).
            (response.header("Server")?.contains("cloudflare") == true && response.code == 403 &&
                (body.contains("challenge", ignoreCase = true) ||
                    body.contains("just a moment", ignoreCase = true) ||
                    body.contains("turnstile", ignoreCase = true)))
        ) return true
    }
    // Nektere sity (napr. BatCave) maji nad Cloudflare vlastni JS+PoW branu na urovni
    // originu, co "podezrelym" klientum (nahlasene: appcinu OkHttp requestu, byt s korektnimi
    // prohlizecovymi hlavickami) misto skutecneho 403 servíruje STEJNOU vyzvu maskovanou jako
    // nevinna 404 - schvalne, aby odradila automatizovane opakovane pokusy. Konkretni status
    // kod se proto NEKONTROLUJE (na rozdil od bloku vyse) - staci Cloudflare edge (Server
    // hlavicka) + charakteristicke znacky tehle konkretni fingerprint+proof-of-work vyzvy.
    // Zamerne AZ JAKO DRUHY, samostatny krok (ne slouceny do bloku vyse) - ten 403/503 blok
    // ma zustat presne podle puvodni zdokumentovane logiky (viz CloudflareBlockedDetectionTest).
    // Hlavička se kontroluje PŘED čtením těla - jinak by se 8 KB těla zkopírovalo u každé odpovědi,
    // včetně obrázků.
    if (response.header("Server")?.contains("cloudflare") != true) return false
    val body = response.peekBody(8 * 1024).string()
    return body.contains("navigator.webdriver") && body.contains("pow_nonce")
}

/**
 * Nektere WAF (typicky Wordfence, i kdyz bezi za Cloudflare) na skutecny hard
 * block (IP/rate-limit, ne resitelna vyzva) vraci stranku bez jakekoli
 * CAPTCHY/Turnstile widgetu k vyreseni - "Sorry, you have been blocked".
 * Ukazovat na tohle interaktivni WebView dialog uzivateli nema smysl (neni
 * co resit) a jen by po kazdem vyprseni [CloudflareInterceptor.failureCooldownMs]
 * zase vyskocil znovu - presne tenhle vzorec uzivatel nahlasil jako "kazdou
 * chvili skacou picoviny" pro fmcdn.mfcdn.net (MangaFire CDN). Detekce cili
 * primo na tenhle konkretni pripad, specifictejsi nez [isCloudflareBlocked],
 * aby nezachytila i skutecne resitelne vyzvy (Managed Challenge/Turnstile),
 * ktere pro uzivatele smysl ukazat porad maji.
 */
internal fun isUnsolvableWafBlock(response: Response): Boolean {
    if (response.code != 403) return false
    val body = response.peekBody(8 * 1024).string()
    return body.contains("you have been blocked", ignoreCase = true) &&
        body.contains("security service", ignoreCase = true)
}

/**
 * Sloučí Cookie hlavičku, kterou si požadavek nese sám (např. session zdroje), s cookies z
 * Cloudflare clearance. Při shodě jména vyhrává clearance; dřív ji clearance celou přepsala.
 */
internal fun mergeCookieHeaders(existing: String?, clearance: String): String {
    if (existing.isNullOrBlank()) return clearance
    val clearanceNames = clearance.split(";").mapNotNull { it.substringBefore('=', "").trim().ifEmpty { null } }.toSet()
    val kept = existing.split(";").map { it.trim() }
        .filter { it.isNotEmpty() && it.substringBefore('=').trim() !in clearanceNames }
    return (kept + clearance).joinToString("; ")
}

/** Cloudflare pro obrázky - viz [CloudflareInterceptor.interceptWithoutSolving]. */
class ImageCloudflareInterceptor(private val delegate: CloudflareInterceptor) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = delegate.interceptWithoutSolving(chain)
}

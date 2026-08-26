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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val clearanceCache = ConcurrentHashMap<String, CachedClearance>()
    private val clearanceTtlMs = TimeUnit.HOURS.toMillis(2)

    /**
     * Kdyz reseni (tiche i interaktivni) pro host selze - typicky trvaly "Sorry,
     * you have been blocked" misto resitelne vyzvy - nema smysl to zkouset znovu
     * pro kazdy dalsi pozadavek na stejnou domenu (dalsi obalka v knihovne, dalsi
     * stranka kapitoly...). Bez tehle cache by se interaktivni dialog objevoval
     * znovu a znovu hned po zavreni predchoziho, prakticky bez moznosti appku
     * pouzivat. Po vyprseni cooldownu se zkusi znovu (treba uz block pominul).
     */
    private val failureCache = ConcurrentHashMap<String, Long>()
    private val failureCooldownMs = TimeUnit.MINUTES.toMillis(10)

    /** Soubezne pozadavky na stejny host cekaji na JEDNO reseni, ne kazdy spousti vlastni WebView/dialog. */
    private val hostLocks = ConcurrentHashMap<String, Any>()

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

    init {
        loadPersistedCache()
    }

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
                clearanceCache.forEach { (host, c) ->
                    obj.put(host, JSONObject().put("cookies", c.cookies).put("expiresAt", c.expiresAt))
                }
                dataStore.edit { it[SettingsKeys.CLOUDFLARE_CLEARANCE_CACHE] = obj.toString() }
            } catch (_: Exception) { /* perzistence je jen optimalizace, nesmi shodit request */ }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        val cached = clearanceCache[host]?.takeIf { it.expiresAt > System.currentTimeMillis() }
        val requestToTry = if (cached != null) request.withClearance(cached.cookies) else request

        val response = chain.proceed(requestToTry)
        if (!isCloudflareBlocked(response)) return response
        val unsolvable = isUnsolvableWafBlock(response)
        response.close()
        if (cached != null) clearanceCache.remove(host)

        if (unsolvable) {
            failureCache[host] = System.currentTimeMillis()
            return chain.proceed(request)
        }

        if (isInFailureCooldown(host)) return chain.proceed(request)

        val lock = hostLocks.getOrPut(host) { Any() }
        synchronized(lock) {
            // Mezitim uz mohlo jine (souběžné) vlakno pro tenhle host uspet nebo
            // selhat - pokud ano, staci pouzit vysledek, ne spoustet dalsi WebView/dialog.
            clearanceCache[host]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return chain.proceed(request.withClearance(it.cookies))
            }
            if (isInFailureCooldown(host)) return chain.proceed(request)

            // Tichy pokus resi jen bezinterakcni "Managed Challenge". Kdyz selze
            // (typicky skutecna interaktivni Turnstile CAPTCHA nebo trvaly block),
            // zkusime jeste ukazat WebView viditelne uzivateli (CloudflareChallengeBridge).
            val cookies = solveCloudflareSynchronously(request.url.toString(), host)
                ?: if (suppressInteractiveChallenge) null
                   else CloudflareChallengeBridge.awaitUserSolve(request.url.toString(), host, timeoutSeconds = 90)

            if (cookies == null) {
                // Kdyz to bylo "jen" potlacene (viz suppressInteractiveChallenge), nejde o
                // skutecne zjisteny trvaly block - do failureCache se to nedava, aby pozdejsi
                // PRIME prochazeni tohohle zdroje (mimo hromadne hledani) porad dostalo sanci
                // na skutecnou interaktivni vyzvu, misto aby ho cooldown preskocil bez ptani.
                if (!suppressInteractiveChallenge) failureCache[host] = System.currentTimeMillis()
                return chain.proceed(request)
            }

            // Cookie z WebView (cf_clearance) neni zaruka uspechu - napr. kdyz chybi dalsi
            // cookie jako __cf_bm, nebo interaktivni reseni bylo neuplne. Bez tyhle kontroly
            // by se takova neplatna clearance ulozila do cache na 25 minut a uzivatel by
            // dalsich 25 minut dostaval 403 bez jakekoli dalsi sance to zkusit znovu.
            val retried = chain.proceed(request.withClearance(cookies))
            if (isCloudflareBlocked(retried)) {
                failureCache[host] = System.currentTimeMillis()
                return retried
            }

            clearanceCache[host] = CachedClearance(cookies, System.currentTimeMillis() + clearanceTtlMs)
            persistCacheAsync()
            return retried
        }
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
        .header("Cookie", cookies)
        .header("User-Agent", CHROME_UA)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveCloudflareSynchronously(url: String, host: String): String? {
        var result: String? = null
        val latch = CountDownLatch(1)

        mainHandler.post {
            lateinit var webView: WebView
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = CHROME_UA
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Misto jednoho pevneho cekani na jeden konkretni cas (drive 3000ms -
                        // u pomalejsich webu/zarizeni casto prilis brzo, cf_clearance jeste
                        // nestihla dorazit) appka teď kontroluje cookie opakovane po 500ms az
                        // do celkoveho limitu (viz fallback nize) - vyresi se hned, jak je
                        // hotovo, misto zbytecneho cekani NEBO zmeskani pozdejsiho vysledku.
                        val poll = object : Runnable {
                            override fun run() {
                                if (latch.count == 0L) return
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies?.contains("cf_clearance") == true) {
                                    result = cookies
                                    latch.countDown()
                                    webView.destroy()
                                } else {
                                    mainHandler.postDelayed(this, 500L)
                                }
                            }
                        }
                        mainHandler.postDelayed(poll, 500L)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return !request.url.host.orEmpty().contains(host)
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                loadUrl(url)
            }

            mainHandler.postDelayed({
                if (latch.count > 0) {
                    val cookies = CookieManager.getInstance().getCookie(url)
                    result = cookies?.takeIf { it.contains("cf_clearance") }
                    latch.countDown()
                    webView.destroy()
                }
            }, 15_000L)
        }

        latch.await(18, TimeUnit.SECONDS)
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
internal fun isCloudflareBlocked(response: Response): Boolean {
    if (response.code !in listOf(403, 503)) return false
    if (response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) return true
    val body = response.peekBody(8 * 1024).string()
    return body.contains("cf-browser-verification") ||
        body.contains("challenge-running") ||
        body.contains("jschl_vc") ||
        body.contains("cf_clearance") ||
        (response.header("Server")?.contains("cloudflare") == true && response.code == 403)
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

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
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * cf_clearance vyresene pres WebView se cachuji per-host, jinak by kazdy
     * dalsi zablokovany pozadavek na tu samou domenu (napr. dalsi stranka
     * vypisu) znovu spoustel cely WebView flow (~1-15s). TTL je konzervativni
     * odhad - Cloudflare Managed Challenge clearance obvykle vydrzi rady
     * desitek minut, presna doba se ale lisi web od webu a nikde se neda
     * precist z odpovedi predem. Cache se navic persistuje do DataStore, aby
     * vyresena vyzva prezila i restart appky (jinak by se po kazdem cold
     * startu muselo resit znovu, i kdyz clearance jeste realne plati).
     */
    private data class CachedClearance(val cookies: String, val expiresAt: Long)
    private val clearanceCache = ConcurrentHashMap<String, CachedClearance>()
    private val clearanceTtlMs = TimeUnit.MINUTES.toMillis(25)

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
        response.close()
        if (cached != null) clearanceCache.remove(host)

        // Tichy pokus resi jen bezinterakcni "Managed Challenge". Kdyz selze
        // (typicky skutecna interaktivni Turnstile CAPTCHA), zkusime jeste
        // ukazat WebView viditelne uzivateli (viz CloudflareChallengeBridge).
        val cookies = solveCloudflareSynchronously(request.url.toString(), host)
            ?: CloudflareChallengeBridge.awaitUserSolve(request.url.toString(), host, timeoutSeconds = 90)
            ?: return chain.proceed(request)

        clearanceCache[host] = CachedClearance(cookies, System.currentTimeMillis() + clearanceTtlMs)
        persistCacheAsync()
        return chain.proceed(request.withClearance(cookies))
    }

    private fun Request.withClearance(cookies: String) = newBuilder()
        .header("Cookie", cookies)
        .header("User-Agent", CHROME_UA)
        .build()

    private fun isCloudflareBlocked(response: Response): Boolean {
        if (response.code !in listOf(403, 503)) return false
        val body = response.peekBody(8 * 1024).string()
        return body.contains("cf-browser-verification") ||
            body.contains("challenge-running") ||
            body.contains("jschl_vc") ||
            body.contains("cf_clearance") ||
            (response.header("Server")?.contains("cloudflare") == true && response.code == 403)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveCloudflareSynchronously(url: String, host: String): String? {
        var result: String? = null
        val latch = CountDownLatch(1)

        mainHandler.post {
            val webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = CHROME_UA
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        postDelayed({
                            val cookies = CookieManager.getInstance().getCookie(url)
                            if (cookies?.contains("cf_clearance") == true) {
                                result = cookies
                                latch.countDown()
                                destroy()
                            }
                        }, 3000L)
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
            }, 12_000L)
        }

        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    companion object {
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36"
    }
}

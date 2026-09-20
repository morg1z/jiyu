package com.haise.jiyu.source.interceptor

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import androidx.core.view.doOnLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Globální pozorovatel [CloudflareChallengeBridge] - když tichý WebView solve v CloudflareInterceptor nestačí
 * (typicky Turnstile), spustí se tady neviditelný WebView, který výzvu vyřeší sám (viz
 * [CloudflareChallengeAttempt]). Nic se uživateli neukazuje. Vložit jednou nahoře ve stromu (MainActivity),
 * aby fungoval na jakékoli obrazovce.
 */
@Composable
fun CloudflareChallengeHost() {
    val pending by CloudflareChallengeBridge.pending.collectAsStateWithLifecycle()
    pending?.let { challenge ->
        CloudflareChallengeAttempt(
            challenge = challenge,
            onDone = { cookies -> CloudflareChallengeBridge.resolve(cookies) },
        )
    }
}

/**
 * Automatický pokus o vyřešení jedné [PendingChallenge]. WebView je NEVIDITELNÝ a nereaguje na skutečné dotyky
 * uživatele (appka se dá dál normálně ovládat); stránku výzvy načte v reálné velikosti a sám opakovaně "klikne" na
 * Turnstile checkbox. Když se do [AUTO_TAP_TIMEOUT_MS] nenajde cf_clearance, výzva se vzdá ([onDone] s `null`) -
 * uživateli se NIKDY neukazuje okno ani se po něm nechce ověření.
 */
@Composable
private fun CloudflareChallengeAttempt(challenge: PendingChallenge, onDone: (String?) -> Unit) {
    InvisibleAutoTapAttempt(
        challenge = challenge,
        onSolved = onDone,
        onGiveUp = { onDone(null) },
    )
}

/**
 * WebView, který ignoruje dotyky uživatele (celoobrazovkový neviditelný překryv by jinak blokoval appku), ale přijímá
 * dotyky, které do něj pošle appka sama přes [tap] (klik na Turnstile checkbox).
 */
private class SilentWebView(context: android.content.Context) : WebView(context) {
    private var allowTouch = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = allowTouch && super.dispatchTouchEvent(event)

    fun tap(event: MotionEvent) {
        allowTouch = true
        try { dispatchTouchEvent(event) } finally { allowTouch = false }
    }
}

// Neviditelný pokus je teď JEDINÝ automatický pokus v popředí (odpojený tichý WebView se přeskakuje), proto dostane
// víc času - pomalejší bezinterakční výzva by jinak zbytečně ukázala dialog uživateli.
private const val AUTO_TAP_TIMEOUT_MS = 18_000L
private const val AUTO_TAP_POLL_MS = 400L
private const val AUTO_TAP_RETRY_INTERVAL_MS = 2_500L
private const val AUTO_TAP_PRESS_MS = 90L
private const val MAX_TAP_ATTEMPTS = 3

/**
 * Skript vraci primo JS objekt (ne JSON.stringify) - WebView.evaluateJavascript
 * serializuje vysledek do JSON textu sam, dvoji serializace by ho jen znovu
 * naobalila do escapovaneho retezce. `iframe.contentDocument` by kvuli
 * cross-origin politice stejne selhal - appka proto cili primo na ohranicujici
 * obdelnik SAMOTNEHO iframu (checkbox je typicky poblíž jeho leveho okraje,
 * svisle uprostred), ne na skutecny checkbox uvnitr.
 *
 * Zkousi vic selektoru za sebou - ruzne weby vkladaji Turnstile widget jinak
 * (primy `src` na challenges.cloudflare.com, obalujici `.cf-turnstile` div,
 * nebo jen popisny `title` atribut) - overeno zive jen na hrstce webu, proto
 * radeji vic pokusu nez spoleh na jedinou strukturu.
 */
private const val FIND_TURNSTILE_IFRAME_JS = """
(function() {
    var sel = [
        'iframe[src*="challenges.cloudflare.com"]',
        '.cf-turnstile iframe',
        'div[class*="turnstile"] iframe',
        'iframe[title*="challenge" i]',
        'iframe[title*="widget containing" i]'
    ];
    var f = null;
    for (var i = 0; i < sel.length; i++) {
        f = document.querySelector(sel[i]);
        if (f) break;
    }
    if (!f) return null;
    var r = f.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return null;
    return { x: r.left + Math.min(30, r.width * 0.18), y: r.top + r.height / 2 };
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InvisibleAutoTapAttempt(
    challenge: PendingChallenge,
    onSolved: (String) -> Unit,
    onGiveUp: () -> Unit,
) {
    val stopped = remember(challenge) { booleanArrayOf(false) }
    DisposableEffect(challenge) { onDispose { stopped[0] = true } }

    AndroidView(
        // fillMaxSize (ne 1x1dp) - stranka musí dostat skutecny viewport, jinak
        // by se Turnstile widget vyrenderoval do prakticky nulove plochy a JS
        // by nenasel zadny pouzitelny obdelnik. alpha(0f) ho jen udela vizualne
        // neviditelnym, na WebView.dispatchTouchEvent() volane primo z kodu to
        // nema vliv (a bez pointer-input modifieru Compose skrz nej propousti
        // i skutecne uzivatelske dotyky na obsah pod nim).
        modifier = Modifier.fillMaxSize().alpha(0f),
        factory = { ctx ->
            SilentWebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // UA skutečného WebView (ne pevný řetězec) - viz CloudflareUserAgent.
                settings.userAgentString = CloudflareUserAgent.value(ctx)
                var tapsUsed = 0
                var lastTapAt = 0L
                var gaveUp = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val tracker = SolveTracker()
                        postDelayed(object : Runnable {
                            override fun run() {
                                if (stopped[0] || gaveUp) return
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies?.contains("cf_clearance") == true) {
                                    // Cookie sama nestačí - hotovo je až když se stránka opakovaně ohlásí jako
                                    // skutečná (cf_clearance se může změnit, zatímco mezistránka ještě běží).
                                    view.evaluateJavascript(CF_PAGE_STATE_JS) { raw ->
                                        if (stopped[0] || gaveUp) return@evaluateJavascript
                                        if (tracker.onPoll(true, com.haise.jiyu.util.decodeJsResult(raw))) {
                                            onSolved(cookies)
                                        } else {
                                            view.postDelayed(this, AUTO_TAP_POLL_MS)
                                        }
                                    }
                                    return
                                }
                                tracker.onPoll(false, null)
                                // Az MAX_TAP_ATTEMPTS pokusu, ne jeden - prvni odhad souradnic
                                // muze minout (jina velikost/pozice widgetu, nez appka cekala),
                                // nebo prvni klik jen otevre/prehraje animaci checkboxu bez
                                // efektu. Kazdy dalsi pokus znovu preplocuje iframe (pozice se
                                // muze behem nacitani stranky jeste posunout).
                                val now = SystemClock.uptimeMillis()
                                if (tapsUsed < MAX_TAP_ATTEMPTS && now - lastTapAt >= AUTO_TAP_RETRY_INTERVAL_MS) {
                                    view.evaluateJavascript(FIND_TURNSTILE_IFRAME_JS) { raw ->
                                        if (stopped[0] || gaveUp) return@evaluateJavascript
                                        val point = parseTapPoint(raw, view.resources.displayMetrics.density)
                                        if (point != null) {
                                            tapsUsed++
                                            lastTapAt = SystemClock.uptimeMillis()
                                            (view as? SilentWebView)?.let { dispatchTap(it, point.first, point.second, tapsUsed) }
                                        }
                                    }
                                }
                                view.postDelayed(this, AUTO_TAP_POLL_MS)
                            }
                        }, AUTO_TAP_POLL_MS)
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                postDelayed({
                    if (!stopped[0]) {
                        gaveUp = true
                        onGiveUp()
                    }
                }, AUTO_TAP_TIMEOUT_MS)
                // Stránka se načte až po prvním layoutu: Turnstile potřebuje skutečný viewport, ne WebView o velikosti nula.
                doOnLayout { loadUrl(challenge.url) }
            }
        },
        onRelease = { it.destroy() },
    )
}

/**
 * Parsuje vysledek [FIND_TURNSTILE_IFRAME_JS] ("null" retezec, nebo JSON objekt
 * `{"x":..,"y":..}` v CSS pixelech WebView viewportu) a prevede na skutecne
 * pixely zarizeni pro [MotionEvent] - nasobenim density displeje. Appka
 * nenastavuje `useWideViewPort`/vlastni zoom, takze CSS px WebView viewportu
 * odpovidaji dp - tenhle prevod je proto spolehlivy pro bezne stranky, ale
 * NEnI zaruceny univerzalne (stranky s vlastnim viewport meta tagem/zoomem
 * by mohly dat mirne posunuty vysledek - viz komentar u volajici funkce,
 * cely auto-tap je "best effort", ne zaruceny bypass).
 */
private fun parseTapPoint(raw: String?, density: Float): Pair<Float, Float>? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        val obj = JSONObject(raw)
        val x = obj.optDouble("x").takeIf { !it.isNaN() } ?: return null
        val y = obj.optDouble("y").takeIf { !it.isNaN() } ?: return null
        (x.toFloat() * density) to (y.toFloat() * density)
    } catch (_: Exception) {
        null
    }
}

/**
 * Skutecna Android touch udalost (ne JS `element.click()`) - ta jde skrz
 * WebView do Chromia jako "trusted" input, presne jako kdyby na displej sahl
 * clovek. Synteticky vyvolany JS click by Cloudflare poznal a ignoroval.
 *
 * `attempt` posouva misto doteku o par pixelu a mirne prodluzuje drzeni -
 * kdyz prvni presny odhad souradnic minul (napr. widget se jeste behem
 * nacitani posunul), dalsi pokus na uplne stejnem miste by nejspis minul
 * znovu ze stejneho duvodu.
 */
private fun dispatchTap(view: SilentWebView, x: Float, y: Float, attempt: Int) {
    val tx = x + (attempt - 1) * 3f
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tx, y, 0)
    view.tap(down)
    down.recycle()
    view.postDelayed({
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, tx, y, 0)
        view.tap(up)
        up.recycle()
    }, AUTO_TAP_PRESS_MS + attempt * 15L)
}

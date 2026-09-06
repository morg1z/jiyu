package com.haise.jiyu.source.interceptor

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

/**
 * Globalni pozorovatel [CloudflareChallengeBridge] - kdyz tichy WebView solve
 * v CloudflareInterceptor selze (typicky interaktivni Cloudflare Turnstile),
 * zobrazi se tenhle dialog s viditelnym WebView, aby vyzvu mohl vyresit
 * uzivatel sam. Vlozit jednou nekam vysoko v strome (napr. MainActivity),
 * aby fungoval nezavisle na tom, na jake obrazovce appky se uzivatel zrovna
 * nachazi.
 */
@Composable
fun CloudflareChallengeHost() {
    val pending by CloudflareChallengeBridge.pending.collectAsState()
    pending?.let { challenge ->
        CloudflareChallengeAttempt(
            challenge = challenge,
            onDone = { cookies -> CloudflareChallengeBridge.resolve(cookies) },
        )
    }
}

/**
 * Dvoufazovy pokus o vyreseni jedne [PendingChallenge]:
 *
 * 1) Nejdrive [InvisibleAutoTapAttempt] - stejna stranka se nacte neviditelne
 *    (alpha=0, ale v realne velikosti, takze Turnstile widget dostane skutecny
 *    layout) a appka zkusi sama vicekrat najit a "kliknout" na jeho checkbox.
 *    Funguje to jen na cast pripadu (zavisi na tom, jestli Cloudflare zrovna
 *    chce jen potvrzeni kliknutim, nebo dalsi interakci/vizualni hlavolam) -
 *    NENI to zaruceny bypass, jen nejlepsi bezplatna snaha.
 * 2) Kdyz se do [AUTO_TAP_TIMEOUT_MS] nenajde cf_clearance, [revealed] se
 *    prepne na true a zobrazi se puvodni viditelny [CloudflareChallengeDialog]
 *    se stejnou strankou, aby vyzvu dores uzivatel rucne.
 */
@Composable
private fun CloudflareChallengeAttempt(challenge: PendingChallenge, onDone: (String?) -> Unit) {
    var revealed by remember(challenge) { mutableStateOf(false) }
    if (revealed) {
        CloudflareChallengeDialog(challenge = challenge, onDone = onDone)
    } else {
        InvisibleAutoTapAttempt(
            challenge = challenge,
            onSolved = onDone,
            onGiveUp = { revealed = true },
        )
    }
}

private const val AUTO_TAP_TIMEOUT_MS = 9_000L
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
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CloudflareInterceptor.CHROME_UA
                var tapsUsed = 0
                var lastTapAt = 0L
                var gaveUp = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        postDelayed(object : Runnable {
                            override fun run() {
                                if (stopped[0] || gaveUp) return
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies?.contains("cf_clearance") == true) {
                                    onSolved(cookies)
                                    return
                                }
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
                                            dispatchTap(view, point.first, point.second, tapsUsed)
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
                loadUrl(challenge.url)
            }
        },
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
private fun dispatchTap(view: WebView, x: Float, y: Float, attempt: Int) {
    val tx = x + (attempt - 1) * 3f
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tx, y, 0)
    view.dispatchTouchEvent(down)
    down.recycle()
    view.postDelayed({
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, tx, y, 0)
        view.dispatchTouchEvent(up)
        up.recycle()
    }, AUTO_TAP_PRESS_MS + attempt * 15L)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CloudflareChallengeDialog(challenge: PendingChallenge, onDone: (String?) -> Unit) {
    // "stopped" prezije jen tenhle jeden zobrazeni dialogu (remember bez klice) - kdyz se
    // slozi (vyresenim/zavrenim/timeoutem), DisposableEffect ho nastavi na true a
    // rozjety retezec postDelayed pollu se sam zastavi na dalsim tiku.
    val stopped = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) { onDispose { stopped[0] = true } }
    Dialog(
        onDismissRequest = { onDone(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Web ${challenge.host} vyžaduje jedno ověření, že nejsi robot. Vyřeš prosím výzvu níže.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(onClick = { onDone(null) }) {
                        Text("Zavřít")
                    }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = CloudflareInterceptor.CHROME_UA
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    // Jednorazova kontrola 1.2s po nacteni STRANKY VYZVY (driv,
                                    // nez ji uzivatel stihne rucne vyresit) byla k nicemu -
                                    // pokud vyreseni Turnstile nevyvola dalsi plne nacteni
                                    // stranky (caste u vlozeneho widgetu misto celostrankove
                                    // vyzvy), onPageFinished uz znovu nespusti a appka nikdy
                                    // nezjisti, ze cf_clearance mezitim dorazila - uzivatel pak
                                    // musel dialog zavrit rucne, coz se bralo jako SELHANI
                                    // (onDone(null)), i kdyz CAPTCHU realne vyresil. Misto
                                    // jednoho pokusu se ted zkousi opakovane kazdych 500ms, dokud
                                    // se dialog nezavre (viz "stopped" vyse) - stejny vzor jako
                                    // tichy pokus v CloudflareInterceptor.solveCloudflareSynchronously.
                                    val poll = object : Runnable {
                                        override fun run() {
                                            if (stopped[0]) return
                                            val cookies = CookieManager.getInstance().getCookie(url)
                                            if (cookies?.contains("cf_clearance") == true) {
                                                onDone(cookies)
                                            } else {
                                                postDelayed(this, 500L)
                                            }
                                        }
                                    }
                                    postDelayed(poll, 500L)
                                }
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            loadUrl(challenge.url)
                        }
                    },
                )
            }
        }
    }
}

package com.haise.jiyu.source.comix

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.haise.jiyu.util.decodeJsResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Výsledek zachyceného běhu stránky: JSON payload + crypto materiál (když stránka podepisovala). */
class ComixCapture(
    val payload: String,
    val material: ComixCipherMaterial?,
)

class ComixCaptureException(message: String, val cloudflare: Boolean = false) : Exception(message)

/** Rozhraním kvůli JVM testům - [ComixSource] dostane ve vyrovní implementaci fake/null. */
interface ComixPageRunner {
    suspend fun capture(
        url: String,
        html: String,
        script: String,
        contentFilter: String? = null,
        timeoutMs: Long = 60_000L,
    ): ComixCapture
}

/**
 * Skrytý WebView runner pro comix.to. Načte HTML stránky (už stažené OkHttp klientem
 * zdroje) přes `loadDataWithBaseURL` s reálnou URL, takže stránka běží ve svém
 * originu a její skripty můžou podepisovat a volat API. Náš bootstrap
 * ([ComixScripts.bootstrap]) se vloží přímo do HTML jako první `<script>` - běží
 * před skripty stránky a zachytí crypto materiál + výsledek dotazu.
 *
 * Výsledek se vrací dvojitě: `JiyuComixBridge` interface a záložně přes
 * `window.__jiyuComixResult` polling. Běhy se serializují mutexem - WebView je
 * drahé a souběžné běhy by stejně čekaly na main thread.
 */
@Singleton
class ComixWebViewRunner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ComixPageRunner {
    private val mutex = Mutex()

    override suspend fun capture(
        url: String,
        html: String,
        script: String,
        contentFilter: String?,
        timeoutMs: Long,
    ): ComixCapture = mutex.withLock {
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val webView = try {
                        WebView(context)
                    } catch (_: Throwable) {
                        cont.resumeWith(Result.failure(ComixCaptureException("WebView unavailable")))
                        return@suspendCancellableCoroutine
                    }

                    fun finish(result: ComixCapture?, error: String?) {
                        if (cont.isActive) {
                            if (result != null) {
                                cont.resume(result)
                            } else {
                                cont.resumeWith(
                                    Result.failure(
                                        ComixCaptureException(
                                            error ?: "capture failed",
                                            cloudflare = error?.contains("CLOUDFLARE") == true,
                                        ),
                                    ),
                                )
                            }
                        }
                        // finish() bezi i z JavaBridge vlakna - WebView se musi rusit na main.
                        webView.post { webView.destroy() }
                    }

                    val bridge = object : ComixBridge {
                        @JavascriptInterface
                        override fun pass(json: String) = finish(parseCapture(json), null)

                        @JavascriptInterface
                        override fun fail(message: String) = finish(null, message)
                    }

                    // SEC-2: stranka z loadDataWithBaseURL bezi v originu `url` - navigace
                    // (klik, location.replace, redirect) smí jen na její host/subdomény.
                    // Cizi domena by dostala plny JS runtime vcetne JiyuComixBridge na
                    // SVE strance, ne jen na te, co jsme nacetli.
                    val allowedHost = runCatching {
                        android.net.Uri.parse(url).host?.lowercase()
                    }.getOrNull()
                    setup(webView, bridge)
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean {
                            val host = request.url.host?.lowercase()
                            val allowed = allowedHost != null && host != null &&
                                (host == allowedHost || host.endsWith(".$allowedHost"))
                            if (!allowed) {
                                android.util.Log.w(
                                    "ComixWebView",
                                    "blocked navigation to ${request.url.scheme}://${host ?: "?"}",
                                )
                                return true
                            }
                            return false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) finish(null, "neterror: ${error?.description}")
                        }
                    }
                    cont.invokeOnCancellation { webView.post { webView.destroy() } }

                    webView.loadDataWithBaseURL(
                        url,
                        inject(html, ComixScripts.bootstrap(contentFilter) + script),
                        "text/html",
                        "UTF-8",
                        null,
                    )

                    // Zalozni polling - kdyby JavascriptInterface nebyl dostupny,
                    // skripty vysledek ukladaji i do window.__jiyuComixResult/Error.
                    webView.post(object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            webView.evaluateJavascript(
                                "(function(){var r=window.__jiyuComixResult,e=window.__jiyuComixError;" +
                                    "if(r)return r; if(e)return JSON.stringify({__error:e}); return null;})()",
                            ) { raw ->
                                decodeJsResult(raw)?.let { text ->
                                    if (text.startsWith("{\"__error\"")) {
                                        finish(null, JSONObject(text).optString("__error"))
                                    } else {
                                        finish(parseCapture(text), null)
                                    }
                                    return@evaluateJavascript
                                }
                                if (cont.isActive) webView.postDelayed(this, POLL_MS)
                            }
                        }
                    })
                }
            }
        } ?: throw ComixCaptureException("timeout after ${timeoutMs}ms")
    }

    /**
     * JS↔Kotlin bridge pro Comix capture. Pojmenovane rozhrani (ne `Any`) - lint
     * `JavascriptInterface` check na `Object` anotace nevidel a hazel error, pritom
     * anotace na metodach byly. Za běhu se to chovalo stejne (reflexe cte runtime
     * tridu), jen presnejsi typ umoznuje lintu overit anotace staticky.
     */
    private interface ComixBridge {
        @JavascriptInterface
        fun pass(json: String)

        @JavascriptInterface
        fun fail(message: String)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup(webView: WebView, bridge: ComixBridge) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.blockNetworkImage = true // obrazky stranky nepotrebujeme - setrime data
        // SEC-2: cookies tretich stran nesmi - sdileny CookieManager by jinak cpal
        // cookies jineho hosta do subrequestu cizi domeny.
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.addJavascriptInterface(bridge, "JiyuComixBridge")
    }

    private fun parseCapture(json: String): ComixCapture? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val payload = root.optString("payload").takeIf { it.isNotEmpty() } ?: return null
        val material = root.optJSONObject("material")?.let { m ->
            val sboxes = m.optJSONArray("sboxes")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.optJSONArray(i)?.let { row -> (0 until row.length()).map(row::optInt) } ?: emptyList()
                }
            } ?: emptyList()
            val keys = m.optJSONArray("keys")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.optJSONArray(i)?.let { row -> (0 until row.length()).map(row::optInt) } ?: emptyList()
                }
            } ?: emptyList()
            ComixCipherMaterial(sboxes, keys)
        }
        return ComixCapture(payload, material?.takeIf { it.isValid() })
    }

    private companion object {
        const val POLL_MS = 400L

        /** Vloží skripty hned za <head> (nebo na začátek dokumentu) - musí běžet před skripty stránky. */
        fun inject(html: String, scripts: String): String {
            val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            return if (head != null) {
                html.substring(0, head.range.last + 1) + scripts + html.substring(head.range.last + 1)
            } else {
                scripts + html
            }
        }
    }
}

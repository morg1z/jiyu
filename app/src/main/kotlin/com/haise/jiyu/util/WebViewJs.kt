package com.haise.jiyu.util

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Spuštění JavaScriptu v kontextu stránky - rozhraním kvůli testům bez Androidu. */
interface JsRunner {
    /**
     * Vyhodnotí [script] (tělo funkce, může končit `return ...`) na prázdné stránce s adresou [baseUrl] a vrátí
     * výsledek jako text, nebo `null` (chyba, timeout, `undefined`/`null`).
     */
    suspend fun evaluate(baseUrl: String, script: String, timeoutMs: Long): String?
}

/**
 * Skrytý `WebView` se sdíleným `CookieManager`. Slouží zdrojům, které si musí nechat něco spočítat kódem, jenž web
 * poskytuje sám (např. cookie z lehké JS výzvy) - stejné, jako by stránku otevřel prohlížeč. Vždy se ukončí a
 * uvolní, i při zrušení nebo timeoutu.
 */
@Singleton
class WebViewJsRunner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : JsRunner {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun evaluate(baseUrl: String, script: String, timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val webView = try { WebView(context) } catch (_: Throwable) { cont.resume(null); return@suspendCancellableCoroutine }
                    fun finish(result: String?) {
                        if (cont.isActive) cont.resume(result)
                        webView.destroy()
                    }
                    webView.settings.javaScriptEnabled = true
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript("(function(){\n$script\n})()") { raw -> finish(decodeJsResult(raw)) }
                        }
                    }
                    cont.invokeOnCancellation { webView.post { webView.destroy() } }
                    webView.loadDataWithBaseURL(baseUrl, "<html><body></body></html>", "text/html", "UTF-8", null)
                }
            }
        }
}

/**
 * `evaluateJavascript` vrací výsledek jako JSON literál ("text" v uvozovkách, `null`, číslo ...). Vrátí prostý text,
 * nebo `null` pro `null`/`undefined`/prázdné.
 */
fun decodeJsResult(raw: String?): String? {
    val r = raw?.trim() ?: return null
    if (r.isEmpty() || r == "null" || r == "undefined") return null
    if (r.length < 2 || r.first() != '"' || r.last() != '"') return r
    val body = r.substring(1, r.length - 1)
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\' || i + 1 >= body.length) { out.append(c); i++; continue }
        when (val n = body[i + 1]) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'r' -> out.append('\r')
            'b' -> out.append('\b')
            'u' -> {
                val hex = body.substring(i + 2, minOf(i + 6, body.length))
                val code = hex.toIntOrNull(16)
                if (hex.length == 4 && code != null) { out.append(code.toChar()); i += 6; continue } else out.append(n)
            }
            else -> out.append(n) // \" \\ \/
        }
        i += 2
    }
    return out.toString().ifEmpty { null }
}

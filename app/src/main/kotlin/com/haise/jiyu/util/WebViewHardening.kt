package com.haise.jiyu.util

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Spolecne zabezpeceni pro vsechny WebView v appce (Cloudflare solver, OAuth
 * dialogy, in-app prohlizec zdroju). Stranky v nich bezi JavaScript tretich
 * stran - blokuje vsechno, co stranka k plneni ukolu nepotrebuje:
 * file:// a content:// pristup (kradez lokanich dat), geolokaci, automaticky
 * prehravane media a Safe Browsing vypnuty.
 *
 * Third-party cookies se nastavuji extra volajicim - Cloudflare Turnstile
 * iframe ve uzivatelsky viditelnem WebView (SourceWebScreen) je potrebuje,
 * silent solver/WebView bootstrap je nesmi prijimat (SEC-2).
 */
object WebViewHardening {

    /** Zakladni restrikce - volej na WebView hned po vytvoreni, pred loadUrl. */
    fun applyDefaults(webView: WebView) {
        webView.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
            setGeolocationEnabled(false)
            setSafeBrowsingEnabled(true)
            mediaPlaybackRequiresUserGesture = true
        }
    }

    /** Zakaz third-party cookies pro WebView, co nepotrebuje embedded iframes. */
    fun blockThirdPartyCookies(webView: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }
}

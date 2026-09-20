package com.haise.jiyu.ui.source

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.haise.jiyu.R
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.screenGradient

/**
 * Web zdroje zobrazený UVNITŘ appky (vlastní WebView, ne externí prohlížeč): přihlášení, souhlas nebo jednorázová
 * akce, kterou zdroj po uživateli chce (viz `ErrorAction.OpenSourceWeb`). Cookies zůstávají ve sdíleném
 * `CookieManager`, takže je požadavky zdroje použijí automaticky (viz `WebViewCookieInterceptor`). Tlačítko "Hotovo"
 * se vrátí zpět a obrazovka, ze které uživatel přišel, načtení zopakuje.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SourceWebScreen(url: String, onDone: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Zpět uvnitř WebView (odkazy mezi stránkami přihlášení) má přednost před opuštěním obrazovky.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Column(modifier = Modifier.fillMaxSize().background(screenGradient).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.source_web_title),
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text(stringResource(R.string.source_web_done), color = GlowViolet) }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Stejný User-Agent jako u řešení Cloudflare - cookies vydané webem patří konkrétní identitě.
                    settings.userAgentString = com.haise.jiyu.source.interceptor.CloudflareUserAgent.value(ctx)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    webView = this
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

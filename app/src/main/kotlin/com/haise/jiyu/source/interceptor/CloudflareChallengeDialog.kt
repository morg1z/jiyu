package com.haise.jiyu.source.interceptor

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
        CloudflareChallengeDialog(
            challenge = challenge,
            onDone = { cookies -> CloudflareChallengeBridge.resolve(cookies) },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CloudflareChallengeDialog(challenge: PendingChallenge, onDone: (String?) -> Unit) {
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
                                    postDelayed({
                                        val cookies = CookieManager.getInstance().getCookie(url)
                                        if (cookies?.contains("cf_clearance") == true) onDone(cookies)
                                    }, 1200L)
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

package com.haise.jiyu.source.interceptor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PendingChallenge(val url: String, val host: String)

/**
 * Most mezi CloudflareInterceptor (bezi na pozadi na OkHttp vlakne) a Compose
 * UI (MainActivity). Ticha WebView reseni (viz CloudflareInterceptor) funguji
 * jen na Cloudflare "Managed Challenge" (bez interakce) - kdyz web nasadi
 * skutecnou interaktivni CAPTCHU (Turnstile), tichy pokus nikdy nenajde
 * cf_clearance a musi zasahnout uzivatel. Interceptor v tom pripade nastavi
 * [pending] a zablokuje se na [awaitUserSolve]; UI dialog to zobrazi jako
 * viditelny WebView a po vyreseni/zavreni zavola [resolve].
 */
internal object CloudflareChallengeBridge {
    private val _pending = MutableStateFlow<PendingChallenge?>(null)
    val pending = _pending.asStateFlow()

    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var result: String? = null

    /** Vola se z pozadoveho vlakna interceptoru. Blokuje volajici vlakno. */
    @Synchronized
    fun awaitUserSolve(url: String, host: String, timeoutSeconds: Long): String? {
        result = null
        val ownLatch = CountDownLatch(1)
        latch = ownLatch
        _pending.value = PendingChallenge(url, host)
        ownLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        _pending.value = null
        return result
    }

    /** Vola se z UI vlakna, kdyz WebView najde cf_clearance nebo uzivatel dialog zavre (cookies = null). */
    fun resolve(cookies: String?) {
        result = cookies
        latch?.countDown()
    }
}

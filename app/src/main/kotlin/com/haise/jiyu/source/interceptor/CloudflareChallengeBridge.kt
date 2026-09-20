package com.haise.jiyu.source.interceptor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [id] je unikátní na KAŽDOU výzvu (i opakovanou pro stejný host) - viz [CloudflareChallengeBridge]. */
data class PendingChallenge(val url: String, val host: String, val id: String = UUID.randomUUID().toString())

/**
 * Most mezi CloudflareInterceptor (bezi na pozadi na OkHttp vlakne) a Compose
 * UI (MainActivity). Ticha WebView reseni (viz CloudflareInterceptor) funguji
 * jen na Cloudflare "Managed Challenge" (bez interakce) - kdyz web nasadi
 * skutecnou interaktivni CAPTCHU (Turnstile), tichy pokus nikdy nenajde
 * cf_clearance a musi zasahnout uzivatel. Interceptor v tom pripade nastavi
 * [pending] a zablokuje se na [awaitUserSolve]; UI dialog to zobrazi jako
 * viditelny WebView a po vyreseni/zavreni zavola [resolve].
 *
 * Pred zobrazenim viditelneho dialogu uzivateli [CloudflareChallengeDialog] sam
 * nejdrive zkusi tichou fazi - stejny WebView neviditelne (alpha=0) najde a
 * "klikne" na Turnstile checkbox skutecnou Android touch udalosti (ne JS
 * .click(), ten Cloudflare pozna a ignoruje). Kdyz to vyjde, dialog se
 * uzivateli vubec neukaze.
 *
 * Kazda vyzva ma VLASTNI latch/vysledek (klicovano podle [PendingChallenge.id], ne podle
 * hostitele - i dva soubezne pozadavky na STEJNY host tak nekolinduji). UI porad zobrazuje
 * jen JEDNU vyzvu najednou ([pending]) - clovek fyzicky nemuze resit dve captchy soucasne -
 * ale pozadavky na DALSI hostitele uz na tu prvni neCEKAJI zablokovane na spolecnem zamku
 * (puvodni chyba - viz audit nalez "jeden globalni latch"), jen se zaradi do fronty a
 * dostanou svou radu, jakmile se aktualne zobrazena vyzva vyresi/zavre.
 */
internal object CloudflareChallengeBridge {
    private class HostState {
        val latch = CountDownLatch(1)
        @Volatile var result: String? = null
    }

    private val hostStates = ConcurrentHashMap<String, HostState>()
    private val queue = ConcurrentLinkedQueue<PendingChallenge>()

    private val _pending = MutableStateFlow<PendingChallenge?>(null)
    val pending = _pending.asStateFlow()

    /**
     * Je právě na obrazovce (ve stavu STARTED) něco, co výzvy zobrazuje? Když ano, řešení rovnou přebírá dialog s
     * připojeným WebView; když ne (appka na pozadí), zkouší se jen tichý pokus a nečeká se zbytečně na okno,
     * které nikdo nevidí.
     */
    val hasUi: Boolean get() = _pending.subscriptionCount.value > 0

    /** Vola se z pozadoveho vlakna interceptoru. Blokuje volajici vlakno - VLASTNIM latchem. */
    fun awaitUserSolve(
        url: String,
        host: String,
        timeoutSeconds: Long,
        isCancelled: () -> Boolean = { false },
    ): String? {
        val challenge = PendingChallenge(url, host)
        val state = HostState()
        hostStates[challenge.id] = state
        queue.add(challenge)
        advanceQueue()
        // Čeká po kouscích, aby šlo přestat, jakmile je volání zrušené (viz CloudflareInterceptor).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline && !state.latch.await(250, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) break
        }
        hostStates.remove(challenge.id)
        queue.remove(challenge)
        advanceQueue()
        return state.result
    }

    /** Vola se z UI vlakna, kdyz WebView najde cf_clearance nebo uzivatel dialog zavre (cookies = null). */
    fun resolve(cookies: String?) {
        val current = _pending.value ?: return
        hostStates[current.id]?.let {
            it.result = cookies
            it.latch.countDown()
        }
    }

    /** Zveřejní další čekající výzvu, pokud UI zrovna žádnou neukazuje. */
    @Synchronized
    private fun advanceQueue() {
        val currentlyShown = _pending.value
        if (currentlyShown != null && hostStates.containsKey(currentlyShown.id)) return
        _pending.value = queue.peek()
    }
}

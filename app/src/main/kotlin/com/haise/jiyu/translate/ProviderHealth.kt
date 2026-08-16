package com.haise.jiyu.translate

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Krátkodobá paměť o tom, který překladový provider (gemini/groq/openrouter) právě odmítá
 * obsluhu - aby na něj další dávky téže kapitoly vůbec nesahaly.
 *
 * Proč to vzniklo: fallback řetězec v [translateChain] zkouší až pět kroků za sebou, ale
 * neměl ŽÁDNOU paměť. Když Gemini vyčerpalo denní free-tier kvótu, každá další dávka
 * kapitoly začala znovu od něj, znovu si vybrala plný počet pokusů i s čekáním, a teprve
 * pak sestoupila na providera, o kterém už z předchozí dávky bylo jasné, že funguje.
 * U 54stránkové kapitoly (~15 dávek) se tahle daň platila patnáctkrát - odtud hlášené
 * "22/54 po 15 minutách".
 *
 * Prodleva je stupňovitá ([BASE_COOLDOWN_MILLIS] zdvojnásobená za každé DALŠÍ selhání bez
 * úspěchu mezi tím, strop [MAX_COOLDOWN_MILLIS]) schválně: free-tier limity jsou dvojí a
 * z odpovědi se nedají rozlišit. Limit na minutu se sám uvolní hned po prvním čekání;
 * vyčerpaná denní kvóta naopak selže znovu, prodleva se zdvojnásobí a po pár dávkách je
 * provider odstavený na tak dlouho, že už zbytek kapitoly nezdržuje. Jeden úspěch celý
 * žebříček vynuluje ([markHealthy]).
 *
 * Instance je [Singleton] a sdílená mezi [GeminiTranslateClient] a [GroqTranslateClient] -
 * klíčem je upstream služba, ne prompt. Když Groq odmítá "ultra" prompt kvůli kvótě,
 * odmítne o vteřinu později i obyčejný, takže obě cesty mají sdílet stejný poznatek.
 */
@Singleton
class ProviderHealth internal constructor(
    private val nowMillis: () -> Long,
) {
    /** Hilt používá tenhle konstruktor; monotonní hodiny (ne wall clock - změna času systémem by prodlevu rozhodila). */
    @Inject
    constructor() : this({ System.nanoTime() / 1_000_000L })

    private data class Strike(val availableAtMillis: Long, val consecutiveFailures: Int)

    private val strikes = HashMap<String, Strike>()

    /** false = provider je v prodlevě, volající ho má rovnou přeskočit bez jediného requestu. */
    @Synchronized
    fun isAvailable(provider: String): Boolean {
        val strike = strikes[provider] ?: return true
        return nowMillis() >= strike.availableAtMillis
    }

    /**
     * Provider odmítl obsluhu (vyčerpaná kvóta upstreamu, výpadek, deprekovaný model) -
     * odstaví se na stupňovitou prodlevu, viz komentář u třídy.
     *
     * @param knownRetryAfterSeconds skutečné navržené čekání ze samotné odpovědi providera
     *   (Retry-After hlavička u Groq/OpenRouteru, `RetryInfo.retryDelay` z těla u Gemini -
     *   viz [translate-proxy/index.ts] `retryDelaySecondsFromHeader`/`retryDelaySecondsFromGeminiBody`).
     *   Když appka tohle dostane, NENÍ důvod dál hádat - použije se přímo, žebříček
     *   opakovaných selhání se resetuje (další neúspěch dostane novou, taky přesnou hodnotu,
     *   ne eskalaci nad starým odhadem). Null (výchozí) = provider signál nedal, spadne se na
     *   stupňovité hádání níž - to platí i pro krátkodobé přechodné chyby (5xx, síť), kde
     *   Retry-After typicky vůbec nepřijde.
     */
    @Synchronized
    fun markUnavailable(provider: String, knownRetryAfterSeconds: Double? = null) {
        if (knownRetryAfterSeconds != null && knownRetryAfterSeconds > 0) {
            val delayMillis = (knownRetryAfterSeconds * 1000).toLong().coerceIn(0L, MAX_KNOWN_COOLDOWN_MILLIS)
            strikes[provider] = Strike(availableAtMillis = nowMillis() + delayMillis, consecutiveFailures = 0)
            return
        }
        val previousFailures = strikes[provider]?.consecutiveFailures ?: 0
        val failures = previousFailures + 1
        // shl místo pow - prodleva roste 1x, 2x, 4x... a strop drží posun v rozumném rozsahu.
        val backoff = (BASE_COOLDOWN_MILLIS shl (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT))
            .coerceAtMost(MAX_COOLDOWN_MILLIS)
        strikes[provider] = Strike(availableAtMillis = nowMillis() + backoff, consecutiveFailures = failures)
    }

    /** Provider odpověděl - žebříček prodlev se vynuluje, ať ho jeden výpadek neodstaví natrvalo. */
    @Synchronized
    fun markHealthy(provider: String) {
        strikes.remove(provider)
    }

    /**
     * Odstaví VŠECHNY providery najednou - pro případ, kdy limit hlásí sama proxy
     * (viz RateLimitedException), ne konkrétní upstream. Tam nemá smysl zkoušet zbytek
     * řetězce, protože přes tu samou proxy vede úplně stejně.
     */
    @Synchronized
    fun markAllUnavailable() {
        ALL_PROVIDERS.forEach { markUnavailable(it) }
    }

    /**
     * true = odstavení jsou všichni provideři najednou, takže z překladu teď nemůže nic vyjít.
     *
     * Volající ([TranslateRepository]) na to musí reagovat výjimkou, ne tichým pokračováním:
     * bez téhle kontroly by zbytek kapitoly rychle "doběhl" s prázdnými výsledky a uživatel
     * by dostal nepřeložené stránky bez jediného vysvětlení. Takhle uvidí konkrétní hlášku
     * o vyčerpaném limitu a hotové stránky mu zůstanou v cache, takže pozdější pokus
     * naváže tam, kde tenhle skončil.
     */
    @Synchronized
    fun allUnavailable(): Boolean = ALL_PROVIDERS.none { isAvailable(it) }

    companion object {
        /** Prodleva po prvním selhání - pokrývá free-tier limit "na minutu". */
        private const val BASE_COOLDOWN_MILLIS = 60_000L

        /** Strop prodlevy PŘI HÁDANÉM odhadu - déle než tohle už providera držet stranou nemá smysl. */
        private const val MAX_COOLDOWN_MILLIS = 15 * 60_000L

        /**
         * Strop prodlevy, i když provider sám řekne delší čekání - jen pojistka proti
         * nesmyslné hodnotě (chyba parsování), reálná denní kvóta se do 24 hodin vejde vždycky.
         */
        private const val MAX_KNOWN_COOLDOWN_MILLIS = 24 * 60 * 60_000L

        /** Ochrana proti přetečení při posunu (2^8 * 60s je dávno nad stropem). */
        private const val MAX_BACKOFF_SHIFT = 8

        internal val ALL_PROVIDERS = listOf("gemini", "groq", "openrouter", "cerebras", "mistral")
    }
}

package com.haise.jiyu.source.interceptor

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Smí `CloudflareInterceptor` ukázat uživateli interaktivní výzvu (WebView dialog až 90 s)? Pozadí (stahování,
 * kontrola nových kapitol, překlad) ji ukazovat nemá: nikdo se nedívá, vlákno i per-host permit by zbytečně
 * držely až ~108 s a uživatele by dialogy rušily ze zdrojů, o které se právě nezajímá.
 *
 * Příznak je ThreadLocal přenášený do korutiny (`asContextElement`) - platí jen pro požadavky vyvolané uvnitř
 * [suppressed], ne globálně. Ruční procházení zdroje ve foregroundu tak výzvu dostane dál. Zdroje volají
 * `client.newCall().execute()` synchronně na vlákně korutiny, takže interceptor ho tam přečte.
 */
object InteractiveChallengePolicy {
    private val suppressedInThread = ThreadLocal<Boolean>()
    private val noSolveInThread = ThreadLocal<Boolean>()

    /**
     * Ještě přísnější než [isSuppressed]: Cloudflare výzva se NEŘEŠÍ vůbec (ani tiše ve skrytém WebView), použije se
     * jen už získaná clearance a jinak požadavek rychle selže s [com.haise.jiyu.util.CloudflareProtectedException].
     * Pro hromadné hledání přes desítky zdrojů: jinak by se pro každý chráněný web spouštěl WebView (až ~18 s
     * s držením zámku hostitele a povolení hledání), otevíraly by se dialogy a celá obrazovka by se seknula.
     */
    val isNoSolve: Boolean get() = noSolveInThread.get() == true

    val isSuppressed: Boolean get() = suppressedInThread.get() == true

    suspend fun <T> suppressed(block: suspend () -> T): T =
        withContext(suppressedInThread.asContextElement(true)) { block() }

    /** Viz [isNoSolve]; platí i pro potlačení dialogu ([isSuppressed]). */
    suspend fun <T> noSolve(block: suspend () -> T): T =
        withContext(suppressedInThread.asContextElement(true) + noSolveInThread.asContextElement(true)) { block() }
}

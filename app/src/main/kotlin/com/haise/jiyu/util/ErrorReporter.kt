package com.haise.jiyu.util

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.haise.jiyu.BuildConfig
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * Jediné místo, kudy se hlásí zachycené (nefatální) výjimky.
 *
 * Proč vůbec existuje: kód měl přes 400 míst se vzorem `catch (_: Exception) { ... }`,
 * které chybu spolkly beze stopy, a přitom NULA volání `Log.e`/`recordException`. Když
 * uživatel nahlásil rozbitý překlad nebo mrtvý zdroj, nebylo se od čeho odpíchnout -
 * každá diagnóza znamenala znovu postavit emulátor a ručně dovnitř vpíchnout dočasné
 * logování. Crashlytics je v projektu zapojený, ale sám o sobě zachytí jen tvrdé pády;
 * přesně ty spolknuté výjimky, které se projeví jako "nefunguje to", k němu nikdy
 * nedorazily.
 *
 * Záměrně NEHLÁSÍ všechno - viz [isExpectedNoise]. Výpadek sítě nebo timeout je u appky,
 * která leze na desítky cizích webů, normální provozní stav, ne chyba k prošetření;
 * kdyby šly do Crashlytics taky, skutečné chyby by se v nich utopily.
 */
object ErrorReporter {

    private const val TAG = "Jiyu"

    /**
     * Chyby, které nejsou defekt v appce, ale běžný stav okolního světa - nemá smysl je
     * posílat do Crashlytics. Do logu jdou dál (jen tišeji), aby byly vidět při lokálním
     * ladění.
     *
     * [CancellationException] je tu proto, že zrušení korutiny (uživatel odlistoval pryč,
     * ViewModel zanikl) NENÍ chyba - je to normální řízení toku, a hlásit ho by znamenalo
     * zaplavit dashboard pokaždé, když někdo zavře čtečku.
     */
    internal fun isExpectedNoise(t: Throwable): Boolean = when (t) {
        is CancellationException -> true
        is UnknownHostException -> true
        is SocketTimeoutException -> true
        is InterruptedIOException -> true
        is IOException -> true
        else -> false
    }

    /**
     * Nahlásí zachycenou výjimku. [context] má říct, CO se nepovedlo a u čeho - ne jen
     * "chyba", ale třeba "ocr:page=12" nebo "source:mangapark:chapterList" - v Crashlytics
     * je to jediné, podle čeho se dá nález zařadit.
     */
    fun report(context: String, t: Throwable) {
        val noise = isExpectedNoise(t)
        if (noise) {
            Log.d(TAG, "$context: ${t.friendlyLabel()}")
            return
        }
        Log.e(TAG, context, t)
        if (!BuildConfig.FIREBASE_ENABLED) return
        runCatching {
            Firebase.crashlytics.log(context)
            Firebase.crashlytics.recordException(t)
        }
    }

    /** Krátký popis do logu - u očekávaného šumu nechceme celý stack trace. */
    private fun Throwable.friendlyLabel(): String =
        "${this::class.simpleName}: ${message.orEmpty().take(120)}"

    /**
     * Ne-fatal stopa pro inkonzistentní stavy, které "nemají nastat", ale nejsou
     * výjimka - nenajde se kapitola v seznamu, zvláštní přechod stavu atd.
     * Zaloguje se (Log.w) a připne jako Crashlytics breadcrumb: při případném
     * pozdějším crashi nebo reportu je vidět, že stav nastal a kde - jde čistě
     * o vystopování, žádná Crashlytics issue se nevytváří.
     */
    fun breadcrumb(context: String, detail: String) {
        val msg = "$context: $detail"
        Log.w(TAG, msg)
        if (!BuildConfig.FIREBASE_ENABLED) return
        runCatching { Firebase.crashlytics.log(msg.take(500)) }
    }
}

/**
 * Zkratka pro [ErrorReporter.report] použitelná přímo v `catch` bloku:
 * `catch (e: Exception) { e.report("translate:chapter") ; null }`
 */
fun Throwable.report(context: String) = ErrorReporter.report(context, this)

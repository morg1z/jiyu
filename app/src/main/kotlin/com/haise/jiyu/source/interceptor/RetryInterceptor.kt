package com.haise.jiyu.source.interceptor

import com.haise.jiyu.util.NonRetryable
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SSLException

/**
 * Jeden opakovaný pokus pro přechodné síťové chyby (přerušené/resetované spojení, connect timeout), s krátkou
 * prodlevou a časovým rozpočtem. Dřív se opakovala KAŽDÁ `IOException` třikrát hned za sebou, takže mrtvé spojení
 * (30 s timeout × 3) hlásilo chybu až po 90-180 s a celou dobu drželo povolení hostitele v Throttle.
 *
 * Neopakuje se:
 *  - jiné než `GET`/`HEAD` (POST by se mohl provést dvakrát),
 *  - `UnknownHostException` a `SSLException` (druhý pokus to nespraví, jen zdrží),
 *  - timeout čtení po dlouhém čekání (server je pomalý, ne spojení přerušené) - opakuje se jen timeout
 *    do [fastTimeoutMs] (typicky connect timeout),
 *  - zrušené volání a výjimky označené [NonRetryable] (offline, Cloudflare ...),
 *  - když už byl vyčerpán časový rozpočet [totalBudgetMs].
 *
 * Časování a náhodu jde v testech nahradit ([sleep], [nowMs], [jitterMs]).
 */
class RetryInterceptor(
    private val maxAttempts: Int = 2,
    private val totalBudgetMs: Long = 25_000,
    private val fastTimeoutMs: Long = 16_000,
    private val baseDelayMs: Long = 400,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitterMs: () -> Long = { ThreadLocalRandom.current().nextLong(0, 250) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" && request.method != "HEAD") return chain.proceed(request)

        val start = nowMs()
        var attempt = 1
        while (true) {
            val attemptStart = nowMs()
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                val now = nowMs()
                val giveUp = attempt >= maxAttempts ||
                    chain.call().isCanceled() ||
                    !isRetryable(e, now - attemptStart) ||
                    now - start >= totalBudgetMs
                if (giveUp) throw e
                attempt++
                sleep(baseDelayMs + jitterMs())
            }
        }
    }

    private fun isRetryable(e: IOException, attemptDurationMs: Long): Boolean = when (e) {
        is NonRetryable -> false
        is UnknownHostException, is SSLException -> false
        is SocketTimeoutException -> attemptDurationMs <= fastTimeoutMs
        // Jiné přerušení vlákna než timeout = zrušené volání.
        is java.io.InterruptedIOException -> false
        else -> true
    }
}

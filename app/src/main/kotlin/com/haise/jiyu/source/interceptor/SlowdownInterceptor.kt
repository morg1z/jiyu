package com.haise.jiyu.source.interceptor

import com.haise.jiyu.source.SourceSlowdown
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Před požadavkem na host, který nedávno vrátil 429, počká podle [SourceSlowdown] (jinak nedělá nic). Čeká po
 * malých krocích, aby šlo přestat, jakmile je volání zrušené (zavřená čtečka, zastavený worker).
 */
class SlowdownInterceptor(
    private val slowdown: SourceSlowdown,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var remaining = slowdown.reserve(chain.request().url.host)
        while (remaining > 0) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val step = minOf(remaining, STEP_MS)
            try {
                sleep(step)
            } catch (e: InterruptedException) {
                // Interceptor se kontraktne ukoncuje IOException - InterruptedException
                // ven by prolezl mimo OkHttp retry/cancel cesty (audit). Vlajku vratime.
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", e)
            }
            remaining -= step
        }
        return chain.proceed(chain.request())
    }

    private companion object {
        const val STEP_MS = 100L
    }
}

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
        val incoming = chain.request()
        // X-Jiyu-Priority oznacuje interaktivni request (stranka pod prstem) - v okne
        // po 429 jde pred frontou prefetch slotu (viz SourceSlowdown.reserve priority).
        // Hlavicka je interni - pred odeslanim se odstrani, na server se nepremita.
        val priority = incoming.header(HEADER_PRIORITY) == "1"
        val request = if (priority) {
            incoming.newBuilder().removeHeader(HEADER_PRIORITY).build()
        } else {
            incoming
        }
        var remaining = slowdown.reserve(request.url.host, priority)
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
        return chain.proceed(request)
    }

    companion object {
        /**
         * Interní hlavička "skoč před frontu zpomaleného hosta" - nastavují zobrazovací
         * requesty (buildPageImageRequest priority=true, PageSlicer), prefetch ji nikdy
         * nemá. Interceptor ji čte a před odesláním odstraní.
         */
        const val HEADER_PRIORITY = "X-Jiyu-Priority"
        private const val STEP_MS = 100L
    }
}

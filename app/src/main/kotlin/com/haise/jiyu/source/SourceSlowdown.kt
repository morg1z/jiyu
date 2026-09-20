package com.haise.jiyu.source

import com.haise.jiyu.util.boundedLruMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptivní odstup mezi požadavky na jednoho hostitele: dokud web nevrátil HTTP 429, jde všechno naplno; po
 * [noteRateLimited] se na dobu [SLOWDOWN_WINDOW_MS] mezi požadavky na tenhle host udrží odstup [INTERVAL_MS]. Tím se
 * stahování a předstahování přestane opakovaně narážet do limitu (a hrozí méně blokací IP), aniž by se zpomalily
 * weby, které limit nemají. Stav je jen v paměti, po restartu appky se začíná znovu.
 */
@Singleton
class SourceSlowdown @Inject constructor() {

    private val lock = Any()
    private val slowedUntil = boundedLruMap<String, Long>(MAX_HOSTS)
    private val nextSlot = boundedLruMap<String, Long>(MAX_HOSTS)

    /** Čas se v testech nahrazuje. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    fun noteRateLimited(host: String) {
        synchronized(lock) { slowedUntil[host] = nowMs() + SLOWDOWN_WINDOW_MS }
    }

    /**
     * Kolik milisekund musí volající počkat před dalším požadavkem na [host] (0 = hned). Termín si tím zároveň
     * REZERVUJE - souběžné požadavky se tak seřadí za sebe po [INTERVAL_MS], ne všechny naráz.
     */
    fun reserve(host: String): Long = synchronized(lock) {
        val now = nowMs()
        if ((slowedUntil[host] ?: 0L) <= now) return 0L
        val slot = maxOf(now, nextSlot[host] ?: 0L)
        nextSlot[host] = slot + INTERVAL_MS
        slot - now
    }

    companion object {
        const val INTERVAL_MS = 1_600L
        const val SLOWDOWN_WINDOW_MS = 10L * 60 * 1000
        private const val MAX_HOSTS = 64
    }
}

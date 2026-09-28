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
     *
     * Rezervace horizontu je omezena [MAX_QUEUE_MS]: po 429 + velkem davu obrazku by se jinak
     * rezervace nakoupily do minut a desitky OkHttp dispatcher vlaken by jen spalo (audit).
     * Pozadavek nad horizont se pusti hned - pripadne 429 znovu nabije okno a fronta se
     * samo-regulacne rozevře, misto aby se dispatcher zaplavil spicemi.
     *
     * [priority] = interaktivní požadavek (stránka pod prstem, řez viditelné stránky):
     * pustí se IHNED před veškerou narezervovanou prefetch frontu a místo čekání za ní
     * posune hranici [nextSlot] dopředu - normální sloty si díky tomu odstup drží dál.
     * Bez tohohle po scrub-soku v zpomaleném okně viditelná stránka čekala klidně ~30 s
     * za frontou prefetch requestů, i když načtená měla být hned.
     */
    fun reserve(host: String, priority: Boolean = false): Long = synchronized(lock) {
        val now = nowMs()
        if ((slowedUntil[host] ?: 0L) <= now) return 0L
        if (priority) {
            nextSlot[host] = maxOf(nextSlot[host] ?: 0L, now + INTERVAL_MS)
            return 0L
        }
        val slot = maxOf(now, nextSlot[host] ?: 0L)
        if (slot - now > MAX_QUEUE_MS) return 0L
        nextSlot[host] = slot + INTERVAL_MS
        slot - now
    }

    companion object {
        const val INTERVAL_MS = 1_600L
        const val SLOWDOWN_WINDOW_MS = 10L * 60 * 1000
        /** Nejvic si rezervovat dopredu na jednoho hosta - viz reserve(). */
        const val MAX_QUEUE_MS = 30_000L
        private const val MAX_HOSTS = 64
    }
}

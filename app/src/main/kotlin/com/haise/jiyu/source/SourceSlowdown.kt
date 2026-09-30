package com.haise.jiyu.source

import android.content.Context
import com.haise.jiyu.util.boundedLruMap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptivní odstup mezi požadavky na jednoho hostitele: dokud web nevrátil HTTP 429, jde všechno naplno; po
 * [noteRateLimited] se na dobu [SLOWDOWN_WINDOW_MS] mezi požadavky na tenhle host udrží odstup [intervalMs]
 * (začíná na [INTERVAL_MS], opakované 429 v okně ho zdvojnásobí do [MAX_INTERVAL_MS], Retry-After je minimální
 * odstup a posune celou frontu). Tím se stahování a předstahování přestane opakovaně narážet do limitu (a hrozí
 * méně blokací IP), aniž by se zpomalily weby, které limit nemají.
 *
 * Naučené intervaly se perzistují do `filesDir/source_slowdown.json` (TTL [PERSIST_TTL_MS]) - audit comicknew:
 * bez perzistence se po restartu appky stejný burst znovu naboural do 429 a celá desetiminutová penalizace se
 * opakovala od začátku. Po načtení platí okno normálně - když už host nelimituje, po [SLOWDOWN_WINDOW_MS] čistého
 * provozu se sám vrátí na plnou rychlost.
 */
@Singleton
class SourceSlowdown @Inject constructor(@ApplicationContext context: Context?) {

    private val lock = Any()
    private val slowedUntil = boundedLruMap<String, Long>(MAX_HOSTS)
    private val nextSlot = boundedLruMap<String, Long>(MAX_HOSTS)
    private val intervalMs = boundedLruMap<String, Long>(MAX_HOSTS)
    private val persistFile = context?.filesDir?.let { File(it, PERSIST_FILE) }

    /** Čas se v testech nahrazuje. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    init {
        hydrate()
    }

    /**
     * 429 od [host]. První zásah zapne okno se základním odstupem [INTERVAL_MS]; každá
     * DALŠÍ 429 uvnitř aktivního okna odstup zdvojnásobí (cap [MAX_INTERVAL_MS]) - audit
     * comicknew CDN ukázal, že 429 dorážejí i při 1,6 s/slot (server limituje na
     * minutové okno, ne na odstup), takže fixní interval se nikdy nedostal pod limit a
     * každá nová 429 zase re-armovala celé okno. Eskalace je jediná cesta, jak se pod
     * neznámý limit reálně dostat. [retryAfterMs] (serverem oznámené Retry-After) je
     * minimální odstup - pod něj se nejde ani při základním intervalu.
     */
    fun noteRateLimited(host: String, retryAfterMs: Long = 0) {
        synchronized(lock) {
            val now = nowMs()
            val interval = if ((slowedUntil[host] ?: 0L) > now) {
                minOf((intervalMs[host] ?: INTERVAL_MS) * 2, MAX_INTERVAL_MS)
            } else {
                INTERVAL_MS
            }
            intervalMs[host] = maxOf(interval, minOf(retryAfterMs, MAX_INTERVAL_MS))
            if (retryAfterMs > 0) {
                // Server si explicitně řekl o blackout - fronta se posune za jeho
                // hranici, jinak nejbližší sloty poletí do aktivního limitu a jen
                // re-armují okno. Cap MAX_QUEUE_MS: delší blackout než doba, po kterou
                // request ještě přežije (callTimeout), nemá smysl držet.
                nextSlot[host] = maxOf(nextSlot[host] ?: 0L, now + minOf(retryAfterMs, MAX_QUEUE_MS))
            }
            slowedUntil[host] = now + SLOWDOWN_WINDOW_MS
        }
        persist()
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
    fun reserve(host: String, priority: Boolean = false): Long {
        var expired = false
        val wait = synchronized(lock) {
            val now = nowMs()
            if ((slowedUntil[host] ?: 0L) <= now) {
                // Okno vypršelo bez další 429 - limit se zjevně uklidnil, zpátky naplno
                // (a naučená hodnota se zahodí i z perzistence, jinak by po restartu
                // host zpomalený zůstal navěky).
                expired = intervalMs.remove(host) != null
                return@synchronized 0L
            }
            val gap = intervalMs[host] ?: INTERVAL_MS
            if (priority) {
                nextSlot[host] = maxOf(nextSlot[host] ?: 0L, now + gap)
                return@synchronized 0L
            }
            val slot = maxOf(now, nextSlot[host] ?: 0L)
            if (slot - now > MAX_QUEUE_MS) return@synchronized 0L
            nextSlot[host] = slot + gap
            slot - now
        }
        // persist() mimo [lock] - uvnitř by vznikl lock->this a noteRateLimited
        // volá persist mimo lock jako this->lock (opačné pořadí = deadlock).
        if (expired) persist()
        return wait
    }

    /** Aktuální odstup slotů pro [host]; 0 mimo zpomalené okno. Viditelné v testech. */
    internal fun currentIntervalMs(host: String): Long = synchronized(lock) {
        if ((slowedUntil[host] ?: 0L) <= nowMs()) return 0L
        intervalMs[host] ?: INTERVAL_MS
    }

    // ── perzistence naučených limitů ─────────────────────────────────────────
    // Soubor source_slowdown.json: {"t":<ms>, "h":{<host>:<intervalMs>}}. Zápis jde mimo
    // hlavní zámek a je levý - volá se jen na 429 (ty přicházejí maximálně tak rychle,
    // jak requesty na host doletí, tedy nejrychleji co INTERVAL_MS) a jednou na expiraci.
    // Příští start appky pak začíná už s naučeným odstupem, ne s burstem do limitu.

    private fun persist() {
        val file = persistFile ?: return
        val snapshot = synchronized(lock) {
            intervalMs.entries.associate { (h, i) -> h to i }
        }
        runCatching {
            if (snapshot.isEmpty()) {
                file.delete()
            } else {
                file.writeText(
                    JSONObject()
                        .put("t", nowMs())
                        .put("h", JSONObject(snapshot))
                        .toString(),
                )
            }
        }
    }

    private fun hydrate() {
        val file = persistFile ?: return
        runCatching {
            if (!file.isFile) return@runCatching
            val obj = JSONObject(file.readText())
            val ts = obj.optLong("t", 0L)
            if (nowMs() - ts > PERSIST_TTL_MS) {
                file.delete()
                return@runCatching
            }
            val hosts = obj.optJSONObject("h") ?: return@runCatching
            val now = nowMs()
            synchronized(lock) {
                for (host in hosts.keys()) {
                    val interval = hosts.optLong(host, 0L)
                        .coerceIn(INTERVAL_MS, MAX_INTERVAL_MS)
                    intervalMs[host] = interval
                    slowedUntil[host] = now + SLOWDOWN_WINDOW_MS
                }
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 1_600L

        /** Strop eskalace odstupu při opakovaných 429 (audit comicknew - viz noteRateLimited). */
        const val MAX_INTERVAL_MS = 12_800L
        const val SLOWDOWN_WINDOW_MS = 10L * 60 * 1000
        /** Nejvic si rezervovat dopredu na jednoho hosta - viz reserve(). */
        const val MAX_QUEUE_MS = 30_000L
        private const val MAX_HOSTS = 64
        internal const val PERSIST_FILE = "source_slowdown.json"

        /** Naučený limit platí den - host, co dnes limitoval, zítra limitovat nemusí. */
        internal const val PERSIST_TTL_MS = 24L * 60 * 60 * 1000
    }
}

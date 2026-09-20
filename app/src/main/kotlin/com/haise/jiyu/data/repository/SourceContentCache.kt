package com.haise.jiyu.data.repository

import com.haise.jiyu.util.boundedLruMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paměťová cache výsledků ze zdrojů (seznam stránek kapitoly, detail titulu) se SDÍLENÍM rozběhnutých volání:
 * druhý volající, který přijde, dokud první ještě načítá, počká na stejný požadavek místo aby scrapoval web znovu.
 * Dřív se seznam stránek kapitoly stahoval při každém volání a čtečka ho volala z pěti míst (načtení kapitoly,
 * předstahování další kapitoly, segmenty, překladové preloady) - stejná stránka tak šla na web víckrát.
 *
 * - Načítání běží v aplikačním scope, takže zrušení jednoho volajícího (zavřená obrazovka) neshodí ostatní.
 * - Do cache se nezapíše neúspěch ani prázdný seznam (zdroj po chybě často vrací prázdno) - další volání zkusí znovu.
 * - Položky vyprší po [ttlMs]; [force] přeskočí cache a nahradí ji čerstvým výsledkem.
 * - [clear] a [trim] volá aplikace při nedostatku paměti.
 */
@Singleton
class SourceContentCache @Inject constructor() {

    private class Entry(val deferred: Deferred<Any>, val createdAtMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val spaces = HashMap<String, MutableMap<String, Entry>>()

    /** Čas se v testech nahrazuje. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getOrLoad(
        space: String,
        key: String,
        ttlMs: Long,
        maxEntries: Int,
        force: Boolean = false,
        loader: suspend () -> T,
    ): T {
        val entry = synchronized(lock) {
            val map = spaces.getOrPut(space) { boundedLruMap(maxEntries) }
            val existing = map[key]
            val usable = existing != null && !force && !isStale(existing, ttlMs)
            if (usable) {
                existing!!
            } else {
                Entry(scope.async { loader() as Any }, nowMs()).also { map[key] = it }
            }
        }
        try {
            val result = entry.deferred.await() as T
            if (result is Collection<*> && result.isEmpty()) remove(space, key, entry)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Zrušil se volající (ne načítání samo) - položka zůstává pro ostatní čekající.
            if (entry.deferred.isCancelled) remove(space, key, entry)
            throw e
        } catch (e: Throwable) {
            remove(space, key, entry)
            throw e
        }
    }

    /** Neúspěšné načítání (i bez čekajícího volajícího) se nikdy nepoužije znovu. */
    private fun isStale(entry: Entry, ttlMs: Long): Boolean {
        if (nowMs() - entry.createdAtMs >= ttlMs) return true
        return entry.deferred.isCompleted && entry.deferred.isCancelled
    }

    private fun remove(space: String, key: String, entry: Entry) {
        synchronized(lock) {
            val map = spaces[space] ?: return
            if (map[key] === entry) map.remove(key)
        }
    }

    fun invalidate(space: String, key: String) {
        synchronized(lock) { spaces[space]?.remove(key) }
    }

    fun clear() {
        synchronized(lock) { spaces.clear() }
    }

    /** Při tlaku na paměť nechá jen nejnovější položky každého prostoru. */
    fun trim(keepPerSpace: Int = 1) {
        synchronized(lock) {
            spaces.values.forEach { map ->
                while (map.size > keepPerSpace) map.remove(map.keys.first())
            }
        }
    }
}

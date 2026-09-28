package com.haise.jiyu.source

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Stáhne stránky [from]..[toInclusive] stránkovaného endpointu SOUBĚŽNĚ se stropem
 * [parallelism] souběžných požadavků na jednoho hostitele.
 *
 * Proč: zdroje jako comick.art stránkují seznam kapitol fixně po 60 položkách a
 * parametr limit= ignorují - u titulu se stovkami kapitol (Solo Leveling = 70
 * stránek × ~0,8 s RTT) sekvenční smyčka trvá desítky sekund, zatímco paralelně
 * je to pár sekund. Souběžnost je omezená, ať se netrefí do rate-limitu webu.
 *
 * Semafor se aplikuje uvnitř [fetch], takže i helper volaný z více korutin najednou
 * drží strop per-volání (OkHttp dispatcher má vlastní maxRequestsPerHost jako
 * druhou zábranu). Chyba kterékoli stránky zruší ostatní a propaguje - stejná
 * sémantika jako původní sekvenční smyčka (celý seznam selže, ne tiše poloviční).
 * Volající časový strop (withTimeoutOrNull) zůstává na místě volání.
 */
internal suspend fun <T> fetchPagesParallel(
    from: Int,
    toInclusive: Int,
    parallelism: Int = DEFAULT_PARALLELISM,
    fetch: suspend (Int) -> List<T>,
): List<T> = coroutineScope {
    if (toInclusive < from) return@coroutineScope emptyList()
    val semaphore = Semaphore(parallelism)
    (from..toInclusive).map { page ->
        async { semaphore.withPermit { fetch(page) } }
    }.flatMap { it.await() }
}

/**
 * Jedna stránka z [fetchPagesBatched] - [items] položky stránky a [isLast],
 * jestli je tahle stránka poslední (prázdná/krátká odpověď, chybějící "další"
 * odkaz, hasMore=false - smysl rozhoduje volající).
 */
internal data class PageBatch<T>(val items: List<T>, val isLast: Boolean)

/**
 * "Stahuj stránky dokud zdroj nenahlásí konec" po paralelních DÁVKÁCH velikosti
 * [batchSize]: místo čekání na každou stránku zvlášť se tahá souběžně cela davka,
 * stop-podmínka se ale vyhodnocuje v PORADI stranek - jakmile stranka vrati
 * [PageBatch.isLast], polozky stranek za ni se zahodi a dalsi davka se nestahuje
 * (stránkování je souvislé, takže mezera/prázdná stránka = konec seznamu).
 *
 * Proč: většina zdrojů známý počet stránek nevydá - sekvenční smyčka "dokud
 * neprázdná stránka" u 40-stránkového seznamu kapitol trvá 40 RTT, zatímco po
 * dávkách 4 souběžně jen ~10 RTT. Chyba stránky propaguje - stejná sémantika jako
 * původní sekvenční smyčka - S VÝJIMKOU spekulativních stránek za stránkou, která
 * už hlásila isLast (ty by sekvenční smyčka nikdy nefetchovala; jejich chyba -
 * typicky 404 za koncem - se ignoruje, položky zahodí).
 */
internal suspend fun <T> fetchPagesBatched(
    firstPage: Int = 1,
    maxPages: Int = 200,
    batchSize: Int = 4,
    fetch: suspend (Int) -> PageBatch<T>,
): List<T> = coroutineScope {
    val out = ArrayList<T>()
    val lastAllowed = firstPage + maxPages - 1
    var from = firstPage
    while (from <= lastAllowed) {
        val to = minOf(from + batchSize - 1, lastAllowed)
        val pages = (from..to).map { page -> async { runCatching { fetch(page) } } }
        var stop = false
        for (deferred in pages) {
            val result = deferred.await()
            if (stop) continue // spekulativní stránka za koncem - chybu zahodíme
            val batch = result.getOrElse { throw it }
            out += batch.items
            if (batch.isLast) stop = true
        }
        if (stop) break
        from = to + 1
    }
    out
}

private const val DEFAULT_PARALLELISM = 4

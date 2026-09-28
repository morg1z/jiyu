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

private const val DEFAULT_PARALLELISM = 4

package com.haise.jiyu.ui.reader

/** Kolik stránek dopředu se má předstáhnout - viz [computePrefetchIndices]. */
const val PREFETCH_WINDOW = 4

/**
 * Spočítá, které indexy stránek je potřeba předstáhnout (aktuální pozice + [count] dopředu),
 * vynechá ty, co jsou už v [alreadyPrefetched], a nikdy nepřeteče za konec [pageCount].
 */
fun computePrefetchIndices(
    fromIndex: Int,
    pageCount: Int,
    alreadyPrefetched: Set<Int>,
    count: Int = PREFETCH_WINDOW,
): List<Int> {
    if (fromIndex < 0 || pageCount <= 0) return emptyList()
    return (fromIndex until minOf(fromIndex + count, pageCount))
        .filter { it !in alreadyPrefetched }
}

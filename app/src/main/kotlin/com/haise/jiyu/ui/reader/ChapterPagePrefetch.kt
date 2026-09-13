package com.haise.jiyu.ui.reader

/** Kolik stránek dopředu se má předstáhnout na nezpoplatněném (WiFi) připojení - viz [computePrefetchIndices]. */
const val PREFETCH_WINDOW = 4

/**
 * Širší okno na zpoplatněném/mobilním připojení (viz [com.haise.jiyu.util.NetworkMonitor.isUnmetered]) -
 * na vysoké latenci a nižší rychlosti čtenář frontu 4 předstažených stránek při normálním tempu
 * čtení dojede a pak čeká stránku po stránce; hlubší fronta dá síti víc času na doběhnutí dopředu.
 */
const val PREFETCH_WINDOW_METERED = 8

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

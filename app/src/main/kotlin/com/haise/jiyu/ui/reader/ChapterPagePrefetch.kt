package com.haise.jiyu.ui.reader

/** Kolik stránek dopředu se má předstáhnout na nezpoplatněném (WiFi) připojení - viz [computePrefetchIndices]. */
const val PREFETCH_WINDOW = 4

/**
 * Širší okno na zpoplatněném/mobilním připojení (viz [com.haise.jiyu.util.NetworkMonitor.isUnmetered]) -
 * na vysoké latenci a nižší rychlosti čtenář frontu 4 předstažených stránek při normálním tempu
 * čtení dojede a pak čeká stránku po stránce; hlubší fronta dá síti víc času na doběhnutí dopředu.
 */
const val PREFETCH_WINDOW_METERED = 8

/** Okno při úsporném režimu baterie nebo nedostatku paměti - stahovat dopředu jen minimum. */
const val PREFETCH_WINDOW_MINIMAL = 1

/**
 * Kolik stránek dopředu předstahovat: v úsporném režimu baterie / při nedostatku paměti jen [PREFETCH_WINDOW_MINIMAL],
 * jinak [PREFETCH_WINDOW] (nezpoplatněná síť) nebo [PREFETCH_WINDOW_METERED].
 */
fun prefetchWindowFor(unmetered: Boolean, savingResources: Boolean): Int = when {
    savingResources -> PREFETCH_WINDOW_MINIMAL
    unmetered -> PREFETCH_WINDOW
    else -> PREFETCH_WINDOW_METERED
}

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

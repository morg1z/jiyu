package com.haise.jiyu.ui.reader

/**
 * Velikost v px, na kterou se prefetch dekóduje. Disk cache drží RAW stažené bajty pod klíčem
 * URL - skutečné zobrazení pak čte z disku bez sítě. Do memory cache přitom přibude jen malá
 * bitmapa, takže prefetch celé kapitoly nezaplní RAM desítkami 2000x3000 obrázků (které by se
 * vzájemně vyhazovaly dřív, než by se stihly použít).
 */
const val PREFETCH_DECODE_SIZE = 512

/**
 * Pauza mezi průchody sekvenčního prefetchu, když některá stránka selhala - další průchod
 * zařazené stránky zkusi znovu (viz ReaderViewModel.startChapterPrefetch).
 */
const val PREFETCH_RETRY_DELAY_MS = 2_000L

/**
 * Kolikrát maximálně projde sekvenční prefetch celou kapitolu - pojistka proti mrtvému
 * hostiteli, aby se selhávající stránky nezkoušely donekonečna.
 */
const val PREFETCH_MAX_PASSES = 3

/**
 * Kolik stránek se prefetchuje SOUČASNĚ. Dřívější sekvenční smyčka stahovala stránky
 * jednu po druhé - na pomalém CDN se kapitola naplnila až po desítkách sekund a čtenář
 * každé otočení stránky čekal na síť (nahlášeno proti Kotatsu, který stahuje paralelně).
 * 4 je kompromis: dost rychlé na naplnění cache před čtením (OkHttp dispatcher povoluje
 * 10 requestů na hostitele), ale pořád nechává kapacitu zobrazovacímu requestu stránky,
 * na kterou se uživatel zrovna kouká, a nedobuřcuje rate-limity zdrojů.
 */
const val PREFETCH_PARALLELISM = 4

/**
 * Kolik prvních stránek DALŠÍ kapitoly se předstáhne předem (viz
 * ReaderViewModel.prefetchChapterStart) - přechod kapitoly pak začíná na už stažených
 * stránkách místo studeného startu, který z každého dalšího dílu dělal "čekací" stránku.
 */
const val NEXT_CHAPTER_PREFETCH_PAGES = 6

/**
 * Pořadí indexů pro prefetch kapitoly - DOPŘEDU od aktuální pozice (ty čtenář potřebuje
 * nejdřív), teprve pak zpětně k začátku. Dřívější smyčka jela vždy 0..N, takže otevření
 * uložené pozice na str. 25 nejdřív stahovalo 0-24, zatímco čtenář čekal na 26+.
 * Viditelné v testech (internal).
 */
internal fun prefetchOrder(pageCount: Int, centerIndex: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val center = centerIndex.coerceIn(0, pageCount - 1)
    return (center until pageCount) + (center - 1 downTo 0)
}

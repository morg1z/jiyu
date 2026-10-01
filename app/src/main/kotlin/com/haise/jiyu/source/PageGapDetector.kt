package com.haise.jiyu.source

/**
 * Detekce mezer v číslování URL stránek kapitoly.
 *
 * Proč: některé agregátory (audit comick.art, RWS ch.215) publikují seznam obrázků,
 * kde číslování souborů má díry - `.../144.webp` pak `.../146.webp` - protože upload
 * skupiny soubor 145 nikdy nedostal (na CDN vrací 404) nebo byl smazán. Výsledek:
 * stránka příběhu UPROSTŘED kapitoly chybí a čtenář to pozná až narrativním skokem.
 *
 * Poznávací heuristika: název souboru (poslední segment cesty) končí číslem -
 * `145.webp`, `page_145.webp`, `0145.jpg`. Když ≥ [MIN_PARSE_FRACTION] stránek
 * číslo má a sekvence je ostře rostoucí, mezery v číslování = chybějící stránky.
 * Omezení jsou konzervativní - radši mezeru neohlásit, než vložit cizí obsah:
 * - díra se hlásí jen uvnitř sekvence (chybějící kraj čísla netušíme - pokryje to
 *   až případná evidence z donoru přes sousedy),
 * - díry větší než [MAX_GAP_SIZE] se ignorují (asi jiné číslování/extras, ne
 *   smazané stránky),
 * - stejně tak nekonzistentní sekvence (duplicity, poklesy - viz níže).
 */
object PageGapDetector {

    /** Kam vložit doplněné stránky (index do stávajícího seznamu) + kolik schází. */
    data class PageGap(
        /** Index v původním seznamu stránek, před který se má vložit obsah mezery. */
        val insertIndex: Int,
        /** Čísla souborů, která chybí (např. 145..145). Jen pro diagnostiku/log. */
        val missingNumbers: IntRange,
    ) {
        val missingCount: Int get() = missingNumbers.last - missingNumbers.first + 1
    }

    private val NUMERIC_STEM = Regex("""(?:^|[^0-9])(\d{1,6})$""")
    private const val MIN_PARSE_FRACTION = 0.8f
    private const val MAX_GAP_SIZE = 30
    private const val MAX_TOTAL_MISSING = 60

    /** Poslední číselná skupina v názvu souboru (`.../page_0145.webp` → 145), nebo null. */
    internal fun stemNumber(url: String): Int? {
        val fileName = url.substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
        val stem = fileName.substringBeforeLast('.')
        val match = NUMERIC_STEM.find(stem) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * Najde vnitřní díry v číslování. Prázdný výsledek = žádná mezera NEBO sekvence
     * nedetikovatelná (málo číslovaných URL, neostrost) - obojí znamená "nechat být".
     */
    fun detect(urls: List<String>): List<PageGap> {
        if (urls.size < 3) return emptyList()
        val numbers = urls.map { stemNumber(it) }
        val parsed = numbers.count { it != null }
        if (parsed < urls.size * MIN_PARSE_FRACTION) return emptyList()

        val gaps = mutableListOf<PageGap>()
        var totalMissing = 0
        var prevNum: Int? = null
        numbers.forEachIndexed { idx, num ->
            if (num == null) return@forEachIndexed
            val prev = prevNum
            if (prev != null) {
                val step = num - prev
                when {
                    step <= 0 -> return emptyList() // pokles/duplicita = necíselná sekvence
                    step == 1 -> {}
                    step - 1 > MAX_GAP_SIZE -> return emptyList() // příliš velká díra = jiný schéma
                    else -> {
                        totalMissing += step - 1
                        if (totalMissing > MAX_TOTAL_MISSING) return emptyList()
                        gaps += PageGap(insertIndex = idx, missingNumbers = prev + 1..num - 1)
                    }
                }
            }
            prevNum = num
        }
        return gaps
    }
}

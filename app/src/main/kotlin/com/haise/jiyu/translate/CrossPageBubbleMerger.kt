package com.haise.jiyu.translate

/**
 * Webtoon/manhwa stránky se řežou svisle z jednoho dlouhého pruhu na jednotlivé "stránky" jen
 * kvůli renderu/stahování - bublina samotná o týhle hranici neví a klidně přes ni pokračuje.
 * Appka dřív posílala fragment na konci stránky N a fragment na začátku stránky N+1 k
 * překladu ÚPLNĚ ODDĚLENĚ, každý bez kontextu toho druhého - nahlášeno jako věta rozseknutá
 * napůl, kde ani jedna půlka sama o sobě nedávala smysl a model si musel domýšlet konec/začátek
 * věty, kterou neviděl.
 *
 * Tenhle soubor řeší JEN tu polovinu problému, která appku skutečně bolí - PŘEKLAD (obě
 * poloviny dostanou stejný spojený text, takže model překládá CELOU větu, ne půlku). Skutečné
 * vykreslení JEDNÉ bubliny přes hranici dvou nezávislých bitmap by potřebovalo architekturní
 * změnu `WebtoonReader.kt` (každá stránka je dnes vlastní nezávislý Box, žádná neví o
 * sousedovi) - obě poloviny se tedy pořád vykreslí zvlášť na svém vlastním místě, jen se
 * stejným, kontextově správným textem. Nekoherentní PŘEKLAD byl to, na co si čtenáři
 * stěžovali - ne dvojí vykreslení samo o sobě.
 *
 * Čisté funkce (žádný Android/Bitmap) - testovatelné JVM testem, stejný vzor jako
 * [BubbleMerge]/[TranslationMerge].
 */

/** Kde přesně bublina leží - index stránky v kapitole + index bubliny na téhle stránce. */
internal data class BubbleLocation(val pageIndex: Int, val bubbleIndex: Int)

/**
 * Jeden nalezený pár pokračujících fragmentů + jejich už spojený text.
 * @param continuations fragmenty na DALŠÍ stránce, seřazené shora dolů - většinou jeden, ale
 *   pokračující řádek se na hranici stránky někdy sám rozpadne na víc OCR řádků (viz
 *   [findCrossPageMerges]).
 */
internal data class CrossPageMerge(
    val first: BubbleLocation,
    val continuations: List<BubbleLocation>,
    val mergedText: String,
)

/**
 * Najde páry bublin, které pokračují ze stránky na následující - fragment u DOLNÍHO okraje
 * stránky (viz [EDGE_TOUCH_FRACTION]) + fragment u HORNÍHO okraje další stránky, se SLUŠNÝM
 * vodorovným překryvem (viz [horizontalOverlapRatio]/[minHorizontalOverlap] - bublina
 * pokračující přes hranici zůstává přibližně na stejném vodorovném místě, i když se stránky
 * liší výškou).
 *
 * Od prvního nalezeného fragmentu se dál řetězí přes [shouldMerge] (stejná logika jako
 * spojování řádků JEDNÉ bubliny) - nahlášený bug: pokračující řádek se na začátku další
 * stránky sám rozpadl na dva OCR řádky vedle sebe ("Thev're calling this" + "e an" - zbytek
 * "one an", špatně rozpoznané). Vodorovný překryv proti PŮVODNÍMU dolnímu fragmentu by druhý
 * kus nenašel (dvě slova vedle sebe na jednom řádku se navzájem vodorovně nepřekrývají, jen
 * mají malou mezeru - přesně to, co [shouldMerge] už umí rozpoznat). Appka dřív spojila jen
 * ten první kus a osiřelé "e an" se přeložilo samo o sobě bez kontextu, což se vykreslilo jako
 * zmatený přesah navíc.
 *
 * @param pageOrder pořadí stránek KAPITOLY (ne nutně 0,1,2... - viz `translatable` v
 *   [TranslateRepository.translateChapter], které přeskakuje prázdné stránky). Porovnávají se
 *   jen dvojice, které jsou SOUSEDNÍ jak v tomhle pořadí, TAK v číslování stránky (rozdíl
 *   přesně 1) - `translatable` může vynechat prázdnou stránku uprostřed (žádný text/OCR
 *   selhalo), takže dvě POZIČNĚ sousední položky pořadí by jinak mohly být fyzicky oddělené
 *   vynechanou stránkou mezi nimi. Bublina přes tuhle mezeru fyzicky nepokračuje.
 */
internal fun findCrossPageMerges(
    bubblesByPage: Map<Int, List<ClassifiedBubble>>,
    pageOrder: List<Int>,
    edgeTouchFraction: Float = EDGE_TOUCH_FRACTION,
    minHorizontalOverlap: Float = MIN_HORIZONTAL_OVERLAP,
): List<CrossPageMerge> {
    val merges = mutableListOf<CrossPageMerge>()
    for (i in 0 until pageOrder.size - 1) {
        if (pageOrder[i + 1] != pageOrder[i] + 1) continue
        val currentBubbles = bubblesByPage[pageOrder[i]] ?: continue
        val nextBubbles = bubblesByPage[pageOrder[i + 1]] ?: continue
        fun isTopEdgeCandidate(bj: Int) = !nextBubbles[bj].isSfx && nextBubbles[bj].raw.topF <= edgeTouchFraction
        val usedNextIndices = mutableSetOf<Int>()
        for (bi in currentBubbles.indices) {
            val bottom = currentBubbles[bi]
            if (bottom.isSfx || bottom.raw.bottomF < 1f - edgeTouchFraction) continue
            var bestIndex = -1
            var bestOverlap = minHorizontalOverlap
            for (bj in nextBubbles.indices) {
                if (bj in usedNextIndices || !isTopEdgeCandidate(bj)) continue
                val overlap = horizontalOverlapRatio(bottom.raw, nextBubbles[bj].raw)
                if (overlap >= bestOverlap) {
                    bestOverlap = overlap
                    bestIndex = bj
                }
            }
            if (bestIndex < 0) continue

            val chainIndices = mutableListOf(bestIndex)
            var frontier = nextBubbles[bestIndex].raw
            var extended = true
            while (extended) {
                extended = false
                for (bj in nextBubbles.indices) {
                    if (bj in usedNextIndices || bj in chainIndices || !isTopEdgeCandidate(bj)) continue
                    if (shouldMerge(frontier, nextBubbles[bj].raw)) {
                        chainIndices += bj
                        frontier = nextBubbles[bj].raw
                        extended = true
                        break
                    }
                }
            }
            usedNextIndices += chainIndices
            // Poradi cteni: shora dolu, v ramci stejneho radku zleva doprava.
            val ordered = chainIndices.sortedWith(compareBy({ nextBubbles[it].raw.topF }, { nextBubbles[it].raw.leftF }))
            var mergedText = bottom.raw.text
            for (bj in ordered) mergedText = concatenateDeduplicating(mergedText, nextBubbles[bj].raw.text)
            merges += CrossPageMerge(
                first = BubbleLocation(pageOrder[i], bi),
                continuations = ordered.map { bj -> BubbleLocation(pageOrder[i + 1], bj) },
                mergedText = mergedText,
            )
        }
    }
    return merges
}

/** Poměr překryvu dvou vodorovných rozsahů (IoU jen na ose X) - 0 = žádný, 1 = totožné. */
private fun horizontalOverlapRatio(a: RawTextBlock, b: RawTextBlock): Float {
    val interLeft = maxOf(a.leftF, b.leftF)
    val interRight = minOf(a.rightF, b.rightF)
    val interWidth = maxOf(0f, interRight - interLeft)
    val unionLeft = minOf(a.leftF, b.leftF)
    val unionRight = maxOf(a.rightF, b.rightF)
    val unionWidth = maxOf(0f, unionRight - unionLeft)
    return if (unionWidth <= 0f) 0f else interWidth / unionWidth
}

/**
 * Spojí text dvou pokračujících fragmentů. Když konec [first] a začátek [second] jsou stejné
 * (OCR obou půlek zachytilo i kousek přesahu za hranicí stránky), přesah se NEZDVOJÍ - hledá se
 * nejdelší shoda konce/začátku (case-insensitive), ne jen spojení mezerou.
 */
internal fun concatenateDeduplicating(first: String, second: String): String {
    val a = first.trim()
    val b = second.trim()
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val maxOverlap = minOf(a.length, b.length)
    for (len in maxOverlap downTo MIN_OVERLAP_TO_DEDUPE) {
        if (a.regionMatches(a.length - len, b, 0, len, ignoreCase = true)) {
            return a + b.substring(len)
        }
    }
    return "$a $b"
}

/**
 * Aplikuje [merges] na [bubblesByPage] - VŠECHNY fragmenty jednoho merge (viz
 * [CrossPageMerge.continuations]) dostanou STEJNÝ [CrossPageMerge.mergedText] místo svého
 * původního (neúplného) textu, takže překladač uvidí u KAŽDÉHO z nich celou větu. Zbytek
 * bublin na stránce zůstává beze změny. Vlastní geometrie/pozice/tvar fragmentů se NEMĚNÍ -
 * každý se dál vykreslí na svém původním místě (viz doc komentář souboru).
 */
internal fun applyCrossPageMerges(
    bubblesByPage: Map<Int, List<ClassifiedBubble>>,
    merges: List<CrossPageMerge>,
): Map<Int, List<ClassifiedBubble>> {
    if (merges.isEmpty()) return bubblesByPage
    // (pageIndex, bubbleIndex) -> novy text, pro O(1) lookup misto prohledavani `merges` za kazdou bublinu.
    val overrides = HashMap<BubbleLocation, String>()
    for (merge in merges) {
        overrides[merge.first] = merge.mergedText
        for (loc in merge.continuations) overrides[loc] = merge.mergedText
    }
    return bubblesByPage.mapValues { (pageIndex, bubbles) ->
        bubbles.mapIndexed { bubbleIndex, bubble ->
            val mergedText = overrides[BubbleLocation(pageIndex, bubbleIndex)] ?: return@mapIndexed bubble
            bubble.copy(raw = bubble.raw.copy(text = mergedText))
        }
    }
}

/** Jak blízko dolnímu/hornímu okraji (zlomek výšky stránky) musí fragment ležet, aby se počítal jako "u hranice". */
internal const val EDGE_TOUCH_FRACTION = 0.05f

/** Minimální vodorovný překryv (IoU na ose X), aby se dva fragmenty považovaly za pokračování téže bubliny. */
internal const val MIN_HORIZONTAL_OVERLAP = 0.5f

private const val MIN_OVERLAP_TO_DEDUPE = 4

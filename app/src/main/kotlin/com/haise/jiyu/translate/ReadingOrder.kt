package com.haise.jiyu.translate

/**
 * Seřadí OCR bloky do skutečného pořadí čtení - shora dolů po "řádcích" bublin
 * (bublinách na podobné výšce), uvnitř řádku ZPRAVA DOLEVA pro japonštinu (tradiční
 * manga se čte pravo-levě panel po panelu), jinak ZLEVA DOPRAVA (manhwa/manhua/webtoon
 * mají obvykle západní - LTR - rozvržení).
 *
 * Bez tohohle [OcrEngine.mergeNearbyLines] vracel bloky v podstatě v náhodném pořadí
 * (podle interního indexu union-find skupiny, ne podle skutečné pozice na stránce) -
 * [GeminiUltraPrompt] pak dostával repliky v jiném pořadí, než v jakém je uvidí čtenář,
 * což kazilo návaznost dialogu a konzistenci zájmen v překladu.
 *
 * Řádky se detekují jednoduchým chamtivým 1D shlukováním podle svislého překryvu (žádná
 * skutečná detekce hranic panelů) - u diagonálně navazujících bublin nebo neobvyklého
 * rozvržení nemusí být dokonalé, ale je to podstatně blíž skutečnému čtecímu pořadí než
 * předchozí prakticky nahodilé pořadí.
 */
fun sortIntoReadingOrder(blocks: List<RawTextBlock>, rightToLeft: Boolean): List<RawTextBlock> {
    if (blocks.size <= 1) return blocks
    val sorted = blocks.sortedBy { it.topF }

    val rows = mutableListOf<MutableList<RawTextBlock>>()
    var rowBottom = Float.NEGATIVE_INFINITY
    for (block in sorted) {
        if (rows.isEmpty() || block.topF >= rowBottom) {
            rows += mutableListOf(block)
            rowBottom = block.bottomF
        } else {
            rows.last() += block
            rowBottom = maxOf(rowBottom, block.bottomF)
        }
    }

    return rows.flatMap { row -> if (rightToLeft) row.sortedByDescending { it.leftF } else row.sortedBy { it.leftF } }
}

/**
 * Měří, jak často FINÁLNÍ seřazené pořadí čtení skočí VÝRAZNĚ ZPÁTKY nahoru (další bublina v
 * pořadí leží nad tou předchozí o víc než [backwardJumpThreshold] výšky stránky) - takový skok
 * je silný signál, že geometrické řádkové shlukování ([sortIntoReadingOrder]) špatně
 * seskupilo bubliny ze DVOU RŮZNÝCH PANELŮ (např. vysoký levý panel + nízký pravý) do jedné
 * "řádky", místo aby respektovalo skutečnou hranici panelu - žádná verze tady detekci panelů
 * vůbec nedělá.
 *
 * Čistě observabilita/měřicí průchod (viz plán, EXPERIMENT položka 19) - NEMĚNÍ výsledné
 * pořadí, jen počítá, jak často by se tohle mělo stávat, PŘED rozhodnutím, jestli se vyplatí
 * portovat složitější detekci hranic panelů (Kumiko) - vysoká porovnávací obtížnost a
 * neznámá reálná frekvence byly přesně důvod, proč tahle položka zůstala jen u měření.
 */
internal fun countBackwardReadingOrderJumps(blocks: List<RawTextBlock>, backwardJumpThreshold: Float = BACKWARD_JUMP_THRESHOLD_FRACTION): Int {
    if (blocks.size < 2) return 0
    var jumps = 0
    for (i in 0 until blocks.size - 1) {
        val current = blocks[i]
        val next = blocks[i + 1]
        if (current.bottomF - next.topF > backwardJumpThreshold) jumps++
    }
    return jumps
}

/** Zlomek výšky stránky - pod touhle mezí je "zpětný" skok jen normální nepřesnost řádkového shlukování, ne panelová hranice. */
private const val BACKWARD_JUMP_THRESHOLD_FRACTION = 0.08f

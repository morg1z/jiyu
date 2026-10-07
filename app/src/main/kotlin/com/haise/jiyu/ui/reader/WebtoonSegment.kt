package com.haise.jiyu.ui.reader

import com.haise.jiyu.translate.TranslatedBlock

/**
 * Jedna "kapitola" v souvislém webtoon scrollu ([WebtoonReader]) - mimo "Nekonečné čtení"
 * (viz [com.haise.jiyu.settings.SettingsRepository.infiniteScrollEnabled]) je v seznamu vždy
 * jen jeden segment (aktuálně otevřená kapitola, chová se stejně jako dřívější plochý seznam
 * `pages`). Se zapnutým nekonečným čtením [ReaderViewModel.appendNextWebtoonSegment] přidává
 * další segmenty na konec, takže scroll pokračuje plynule přes hranici kapitoly.
 */
data class WebtoonSegment(
    val chapterId: String,
    val chapterName: String,
    val pages: List<String>,
)

/**
 * Projektuje globalni [flippedBubbles] klice ("$chapterId:$pageIndex:$bubbleIndex",
 * viz ReaderViewModel.toggleBubbleFlip) na plochy format "$pageIndex:$bubbleIndex",
 * ktery ocekava BubbleOverlayLayer - pro JEDNU kapitolu (aktualni u paged ctecek,
 * segment u webtoonu). Klice ostatnich kapitol se zahodi, takze se flip nepreliva
 * mezi segmenty se stejnymi indexy (audit RD-10).
 */
fun flippedKeysForChapter(flipped: Set<String>, chapterId: String): Set<String> {
    val prefix = "$chapterId:"
    return flipped.mapNotNullTo(LinkedHashSet(flipped.size)) { key ->
        if (key.startsWith(prefix)) key.substring(prefix.length) else null
    }
}

// ── Ploché projekce pro nekonečné čtení ve stránkovaných režimech ─────────────
// Paged čtečky (pager i curl) se zapnutým "Nekonečným čtením" dostanou seskládanou
// listu stranek pres vsechny segmenty - nasledujici helpery prekladaji mezi
// plochym indexem a dvojici (chapterId, lokalni index v kapitole). Viz
// ReaderViewModel.onPagedFlatPageChanged a ReaderContent (useInfinitePaged).

/** Plochy index, kde zacina segment [chapterId] (0..vsechny predchozi stranky); -1 kdyz tam neni. */
fun segmentStartFlatIndex(segments: List<WebtoonSegment>, chapterId: String): Int {
    var offset = 0
    for (seg in segments) {
        if (seg.chapterId == chapterId) return offset
        offset += seg.pages.size
    }
    return -1
}

/** Plochy index -> (chapterId, lokalni index); null mimo rozsah celeho proudu. */
fun locatePagedLocal(segments: List<WebtoonSegment>, flatIndex: Int): Pair<String, Int>? {
    var offset = 0
    for (seg in segments) {
        val local = flatIndex - offset
        if (local in 0 until seg.pages.size) return seg.chapterId to local
        offset += seg.pages.size
    }
    return null
}

/**
 * Per-chapter mapa prekladu (`chapterId` -> `pageIndex` -> bloky) -> plocha mapa
 * (`plochy index stranky` -> bloky) pres vsechny segmenty. Paged ctecky tim dostanou
 * overlay i pro stranky jeste neswitchnute kapitoly - preklady se neztraceji.
 */
fun flattenTranslatedPages(
    byChapter: Map<String, Map<Int, List<TranslatedBlock>>>,
    segments: List<WebtoonSegment>,
): Map<Int, List<TranslatedBlock>> {
    val out = HashMap<Int, List<TranslatedBlock>>()
    var offset = 0
    for (seg in segments) {
        byChapter[seg.chapterId]?.forEach { (local, blocks) -> out[offset + local] = blocks }
        offset += seg.pages.size
    }
    return out
}

/**
 * Globalni flip klice "$chapterId:$page:$bubble" -> plochy "$flatPage:$bubble" pres
 * vsechny segmenty. Bez tohohle by se lokalni indexy opakujici v kazde kapitole
 * krizily (flip na str. 3 kap. 1 by se zobrazil i na str. 3 kap. 2).
 */
fun flattenFlippedKeys(flipped: Set<String>, segments: List<WebtoonSegment>): Set<String> {
    val out = LinkedHashSet<String>()
    var offset = 0
    for (seg in segments) {
        for (key in flippedKeysForChapter(flipped, seg.chapterId)) {
            val colon = key.indexOf(':')
            val local = key.substring(0, colon).toIntOrNull() ?: continue
            out += "${offset + local}${key.substring(colon)}"
        }
        offset += seg.pages.size
    }
    return out
}

package com.haise.jiyu.ui.reader

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

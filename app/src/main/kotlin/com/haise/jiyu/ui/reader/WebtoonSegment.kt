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

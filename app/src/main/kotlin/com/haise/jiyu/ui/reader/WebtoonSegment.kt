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

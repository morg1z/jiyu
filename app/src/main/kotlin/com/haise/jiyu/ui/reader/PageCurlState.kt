package com.haise.jiyu.ui.reader

enum class TurnDirection { NEXT, PREV }

/**
 * Stav ohybu stránky - na které stránce/skupině jsme, kolik jich celkem je, a jak moc je
 * aktuálně "ohnutá" (0f = plochá, ±1f = plně otočená). Čistá immutable data třída bez
 * závislosti na Compose/gestech - testovatelná přímo. Sdílená mezi novel readerem
 * (pageCount = počet textových stránek z paginátoru) a manga readerem (pageCount =
 * groups.size, viz Task 7) - ničím jinak specifická.
 */
data class PageCurlState(
    val currentPageIndex: Int,
    val pageCount: Int,
    val dragProgress: Float = 0f, // -1f..1f: zaporne = ohyb k PREV, kladne = k NEXT
    // Nezaclampovany pokus o smer tahu (fix review nalezu Critical 1) - `dragProgress` se na
    // hranici kapitoly (prvni/posledni stranka) zaclampuje na 0f, aby nikdy neprobehla prazdna
    // ohybova animace (viz `withDrag`). To ale znamena, ze `dragProgress` sam o sobe uz na
    // hranici NENESE informaci o tom, kterym smerem uzivatel skutecne tahl - byl by 0f pri
    // svislem/nulovem tahu STEJNE jako pri tahu smerem NEXT/PREV, ktery jen narazil na hranici.
    // `rawDragProgress` si tenhle pokus pamatuje nezaclampovany (jen -1f..1f) i na hranici, takze
    // `onDragEnd` muze spravne rozlisit "zadny signifikantni tah" od "tah, co narazil na hranici".
    val rawDragProgress: Float = 0f,
)

/** Výsledek gesta - buď změna stránky uvnitř kapitoly, přechod na jinou KAPITOLU (hranice),
 * nebo zrušení (vráceno zpět naplocho). */
sealed class PageTurnResult {
    data class WithinChapter(val newState: PageCurlState) : PageTurnResult()
    data class ChapterBoundary(val direction: TurnDirection) : PageTurnResult()
    data class Cancelled(val newState: PageCurlState) : PageTurnResult()
}

/** Průběžný tah prstem - aktualizuje míru ohybu, NEMĚNÍ currentPageIndex (to se děje až
 * po puštění). Na hranici kapitoly (první/poslední stránka) se ohyb tím směrem nepovolí -
 * hranici řeší až [onDragEnd]/[onEdgeTap], aby prázdná animace nikdy neproběhla. */
fun PageCurlState.withDrag(deltaProgress: Float): PageCurlState {
    val atFirstPage = currentPageIndex == 0
    val atLastPage = currentPageIndex == pageCount - 1
    val raw = deltaProgress.coerceIn(-1f, 1f)
    val clamped = when {
        raw < 0f && atFirstPage -> 0f
        raw > 0f && atLastPage -> 0f
        else -> raw
    }
    // `dragProgress` (vizualni, clampovany na 0 na hranici) se pouziva pro vykresleni ohybu -
    // `rawDragProgress` (nikdy vynulovany na hranici) se pouziva jen pro rozpoznani smeru v
    // `onDragEnd` (fix Critical 1).
    return copy(dragProgress = clamped, rawDragProgress = raw)
}

/**
 * Rozhodne, co se stane po puštění prstu. Přesažení [completionThreshold] dokončí obrat,
 * jinak se stránka vrátí naplocho. Na hranici kapitoly dokončený obrat vrátí
 * [PageTurnResult.ChapterBoundary] místo změny currentPageIndex - volající pak zavolá
 * existující onNext()/onPrev() (novel) nebo onNavigateNextChapter()/onNavigatePrevChapter()
 * (manga).
 */
fun PageCurlState.onDragEnd(completionThreshold: Float = 0.4f): PageTurnResult {
    // Pouzivame `rawDragProgress` (fix Critical 1), NE `dragProgress` - ten druhy je na
    // hranici kapitoly zaclampovany na 0f bez ohledu na to, kterym smerem uzivatel skutecne
    // tahl (i svisly/nulovy tah by na hranici vypadal identicky jako tah smerem k hranici).
    // `rawDragProgress` zustava nezaclampovany i na hranici, takze tady muzeme spravne
    // rozlisit "zadny signifikantni tah" (-> Cancelled, zustan na miste) od "tah presahujici
    // prah smerem X, ktery narazil na hranici" (-> ChapterBoundary(X)).
    val magnitude = kotlin.math.abs(rawDragProgress)
    if (magnitude < completionThreshold) {
        return PageTurnResult.Cancelled(copy(dragProgress = 0f, rawDragProgress = 0f))
    }
    val direction = if (rawDragProgress > 0f) TurnDirection.NEXT else TurnDirection.PREV
    return completeTurn(direction)
}

/** Ťuknutí na okraj obrazovky = stejný výsledek jako dokončený tah, bez postupného ohybu -
 * proto funguje i pro jednostránkovou kapitolu, kde [withDrag] nikdy žádný ohyb nepovolí. */
fun PageCurlState.onEdgeTap(direction: TurnDirection): PageTurnResult = completeTurn(direction)

private fun PageCurlState.completeTurn(direction: TurnDirection): PageTurnResult {
    val atFirstPage = currentPageIndex == 0
    val atLastPage = currentPageIndex == pageCount - 1
    return when {
        direction == TurnDirection.PREV && atFirstPage -> PageTurnResult.ChapterBoundary(TurnDirection.PREV)
        direction == TurnDirection.NEXT && atLastPage -> PageTurnResult.ChapterBoundary(TurnDirection.NEXT)
        direction == TurnDirection.NEXT -> PageTurnResult.WithinChapter(
            copy(currentPageIndex = currentPageIndex + 1, dragProgress = 0f, rawDragProgress = 0f),
        )
        else -> PageTurnResult.WithinChapter(
            copy(currentPageIndex = currentPageIndex - 1, dragProgress = 0f, rawDragProgress = 0f),
        )
    }
}

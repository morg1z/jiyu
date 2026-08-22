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
    val clamped = when {
        deltaProgress < 0f && atFirstPage -> 0f
        deltaProgress > 0f && atLastPage -> 0f
        else -> deltaProgress.coerceIn(-1f, 1f)
    }
    return copy(dragProgress = clamped)
}

/**
 * Rozhodne, co se stane po puštění prstu. Přesažení [completionThreshold] dokončí obrat,
 * jinak se stránka vrátí naplocho. Na hranici kapitoly dokončený obrat vrátí
 * [PageTurnResult.ChapterBoundary] místo změny currentPageIndex - volající pak zavolá
 * existující onNext()/onPrev() (novel) nebo onNavigateNextChapter()/onNavigatePrevChapter()
 * (manga).
 */
fun PageCurlState.onDragEnd(completionThreshold: Float = 0.4f): PageTurnResult {
    // Check chapter boundary first: at the edge, any drag attempt (even 0f due to clamping)
    // counts as a boundary hit if it was in the boundary direction
    val atFirstPage = currentPageIndex == 0
    val atLastPage = currentPageIndex == pageCount - 1

    if (atFirstPage && dragProgress <= 0f) {
        return PageTurnResult.ChapterBoundary(TurnDirection.PREV)
    }
    if (atLastPage && dragProgress >= 0f) {
        return PageTurnResult.ChapterBoundary(TurnDirection.NEXT)
    }

    // Not at boundary - check threshold
    val magnitude = kotlin.math.abs(dragProgress)
    if (magnitude < completionThreshold) {
        return PageTurnResult.Cancelled(copy(dragProgress = 0f))
    }
    val direction = if (dragProgress > 0f) TurnDirection.NEXT else TurnDirection.PREV
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
            copy(currentPageIndex = currentPageIndex + 1, dragProgress = 0f),
        )
        else -> PageTurnResult.WithinChapter(
            copy(currentPageIndex = currentPageIndex - 1, dragProgress = 0f),
        )
    }
}

package com.haise.jiyu.ui.reader

import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

/**
 * Výsledek dvojklik-zoom přepočtu - viz [doubleTapZoomTransform].
 */
data class DoubleTapZoomResult(val scale: Float, val panOffset: Offset)

/**
 * Dvojklik-zoom matematika sdílená mezi `ReaderPager.kt` (MangaReader), `WebtoonReader.kt` a
 * `MangaPageCurlReader.kt` - dřív bit-identicky zkopírovaná ve všech třech (audit kolo 6, položka 2).
 * Pokud je už přiblíženo, vrátí zpět na 1x; jinak přiblíží na pevných 2.5x se středem pod prstem.
 */
fun doubleTapZoomTransform(tapOffset: Offset, size: IntSize, currentScale: Float): DoubleTapZoomResult {
    if (currentScale > 1f) return DoubleTapZoomResult(1f, Offset.Zero)
    val zoom = 2.5f
    val cx = size.width / 2f
    val cy = size.height / 2f
    return DoubleTapZoomResult(
        scale = zoom,
        panOffset = Offset(
            (tapOffset.x - cx) * (1f - zoom),
            (tapOffset.y - cy) * (1f - zoom),
        ),
    )
}

/**
 * 3×3 tap-zone lookup sdílený mezi stejnými třemi soubory jako [doubleTapZoomTransform].
 */
fun tapZoneAction(tapOffset: Offset, size: IntSize, tapZonesEnabled: Boolean, grid: TapZoneGrid): TapZoneAction {
    if (!tapZonesEnabled) return TapZoneAction.SHOW_PANEL
    val col = (tapOffset.x / size.width * 3).toInt().coerceIn(0, 2)
    val row = (tapOffset.y / size.height * 3).toInt().coerceIn(0, 2)
    return grid[row, col]
}

/**
 * Vlastní dvouprstá pinch-zoom detekce místo `detectTransformGestures` - ta v Compose Foundation
 * počítá pan/zoom už z JEDNOHO prstu a jakmile překročí touch slop, vždy zkonzumuje position change,
 * což by zkonzumovalo i jednoprstové tažení určené pro scroll/drag jinému gesture-nodu ve stejném
 * řetězci (LazyColumn scroll ve WebtoonReaderu, curl drag v MangaPageCurlReaderu). Čeká, dokud
 * nejsou dole aspoň 2 prsty, než začne cokoliv číst nebo konzumovat - jednoprstové gesto tak projde
 * nedotčené dál. Sdíleno mezi `WebtoonReader.kt` a `MangaPageCurlReader.kt` (audit kolo 6, položka 2).
 */
suspend fun PointerInputScope.detectTwoFingerPinchZoom(onGesture: (zoomChange: Float, panChange: Offset) -> Unit) {
    awaitPointerEventScope {
        while (true) {
            var event = awaitPointerEvent()
            while (event.changes.count { it.pressed } < 2 && event.changes.any { it.pressed }) {
                event = awaitPointerEvent()
            }
            if (event.changes.count { it.pressed } < 2) continue
            do {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                onGesture(zoomChange, panChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                event = awaitPointerEvent()
            } while (event.changes.count { it.pressed } >= 2)
        }
    }
}

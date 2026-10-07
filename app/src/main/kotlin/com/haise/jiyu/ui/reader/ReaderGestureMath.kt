package com.haise.jiyu.ui.reader

import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * 3×3 tap-zone lookup sdílený mezi `ReaderPager.kt` (MangaReader), `WebtoonReader.kt` a
 * `MangaPageCurlReader.kt` - dřív bit-identicky zkopírovaná ve všech třech (audit kolo 6, položka 2).
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
suspend fun PointerInputScope.detectTwoFingerPinchZoom(
    onGestureEnd: () -> Unit = {},
    onGesture: (zoomChange: Float, panChange: Offset) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            var event = awaitPointerEvent()
            while (event.changes.count { it.pressed } < 2 && event.changes.any { it.pressed }) {
                event = awaitPointerEvent()
            }
            if (event.changes.count { it.pressed } < 2) continue

            // Pinch-intent prah: dokud dvouprsta sekvence neprokaze realny pohyb
            // (rozpeti se zmenilo o >~5% NEBO prsty ujely o touch slop), nic se
            // nehlasi ani nekonzumuje. Bez toho stacilo pri rychlem tapovani/dragu
            // nahodne prekryt dva dotyky - detektor se zapojil, calculateZoom vratil
            // sumovou odchylku a `scale` prelezl pres 1f: pak nastejno umrely
            // `scale <= 1f` gaty (tap zony, curl tah, pager swipe) a
            // `LaunchedEffect(scale>1f)` navic abortovala rozjetou curl doanimaci
            // - obrat stranky se ztratil a stranka zustala "zoomnuta" (hlasene
            // "stranka se po otoceni sama priblizi").
            var pendingZoom = 1f
            var pendingPan = Offset.Zero
            var engaged = false
            // Prvni event se 2 prsty je "join" - prave stisknuty prst ma
            // previousPosition == currentPosition, takze calculateZoom vrati
            // sumovou odchylku z posunu centroidu. Preskocit akumulaci.
            var joined = false
            do {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!joined) {
                    joined = true
                } else if (!engaged) {
                    pendingZoom *= zoomChange
                    pendingPan += panChange
                    if (abs(pendingZoom - 1f) > 0.05f ||
                        pendingPan.getDistance() > viewConfiguration.touchSlop
                    ) {
                        engaged = true
                        // Do prvniho onGesture se narve i nasobeny predchazejici
                        // pohyb - zacatek pinchu zustava plynuly, nic se neztrati.
                        onGesture(pendingZoom, pendingPan)
                    }
                } else {
                    onGesture(zoomChange, panChange)
                }
                if (engaged) {
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                event = awaitPointerEvent()
            } while (event.changes.count { it.pressed } >= 2)
            // Konec dvouprste sekvence (pod 2 prsty). Jen pokud skutecne doslo k
            // pinchu (engaged) - nahodne prekryti prstu nehlasi zadne gesto.
            if (engaged) onGestureEnd()
        }
    }
}

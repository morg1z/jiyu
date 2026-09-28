package com.haise.jiyu.ui.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Uzká vertikální linka u levého okraje čtečky - indikátor pozice v kapitole a
 * zároveň rychlý scrubber (inspirováno spodní linkou Kotatsu). Pasivně je jen
 * tenká linka (track + accent výplň postupu shora dolů); podržení ji ztloustne a
 * následné tažení nahoru/dolů posouvá kapitolu přímo na cílovou pozici.
 *
 * Prst smí při tažení ujet ze stripu doprava - gesto drží až do zvednutí, takže
 * se scrubuje pohodlně "prstem vedle linky". Během scrubu se vedle prstu ukazuje
 * chip s cílovou pozicí ([label]).
 *
 * Strip začíná až POD horní lištou (název díla + reset + seznam kapitol) -
 * windowInsetsPadding(safeDrawing) + pevný offset za její obsah - a končí nad
 * gesture/navigacní lištou. ~32dp dotykový strip si gesto bere pro sebe -
 * tap/long-press na úplně levém okraji proto nedojde na stránku pod ním;
 * zbytek tap zóny zůstává. Bez long-pressu strip funguje jen jako vizuální
 * indikátor.
 */
@Composable
fun ReaderEdgeScrubber(
    progress: Float,
    onScrub: (Float) -> Unit,
    /** Popisek cílové pozice pro chip při scrubu - dostane frakci 0f..1f (např. "12 / 40"). */
    label: (Float) -> String,
    /** true po celou dobu scrub gesta (od long-press po zvednuti) - drzi controls
     *  viditelne, jinak by je auto-hide casovac zrusil uprostred tazeni. */
    onScrubActiveChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var stripHeightPx by remember { mutableIntStateOf(0) }

    // Pojistka proti zaseknutemu "scrub aktivni" flagu - kdyby kompozice zmizela
    // uprostred gesta (napr. controls skryte jinou cestou), onDragEnd se nezavola
    // a auto-hide by se uz nikdy nerestartoval.
    DisposableEffect(Unit) {
        onDispose { if (scrubbing) onScrubActiveChanged(false) }
    }
    val barWidth by animateDpAsState(if (scrubbing) 14.dp else 5.dp, label = "edgeScrubWidth")

    // Behem scrubu linka ukazuje pozici PRSTE (ne aktualni stranku) - jinak by
    // pri zpozdenem scrollu linka "skakala" za prstem a poutala pozornost.
    val shownFraction = (if (scrubbing) dragFraction else progress).coerceIn(0f, 1f)
    val accent = Color(0xFF8B5CF6)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(32.dp)
            // Strip začíná až POD horní lištou (název díla, reset, seznam kapitol)
            // a končí nad navigační/gesture lištou - jinak linka prosvítala pod
            // průhlednou lištou a gesta uprostřed její plochy lezla sem.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 72.dp),
    ) {
        // Track (tlumena cara na cele vysce).
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(barWidth)
                .background(Color.White.copy(alpha = if (scrubbing) 0.35f else 0.15f), RoundedCornerShape(50)),
        )
        // Vypln postupu shora dolu.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxHeight(shownFraction)
                .width(barWidth)
                .background(accent.copy(alpha = if (scrubbing) 1f else 0.85f), RoundedCornerShape(50)),
        )
        // Dotyková vrstva - long-press+drag = scrub, obycejny tap propadne (resi tap zony stranky).
        // Výška se měří TADY (vnořená plocha po paddingu), ne na vnějším Boxu - chip se
        // pozicuje vůči ní a prstem cílená frakce musí sedět přesně na drag souřadnice.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { stripHeightPx = it.height }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            scrubbing = true
                            onScrubActiveChanged(true)
                            dragFraction = (offset.y / size.height).coerceIn(0f, 1f)
                            onScrub(dragFraction)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.y / size.height).coerceIn(0f, 1f)
                            onScrub(dragFraction)
                        },
                        onDragEnd = { scrubbing = false; onScrubActiveChanged(false) },
                        onDragCancel = { scrubbing = false; onScrubActiveChanged(false) },
                    )
                },
        )
        // Chip s cílovou pozicí vedle prstu.
        if (scrubbing && stripHeightPx > 0) {
            val density = LocalDensity.current
            val chipHeight = 26.dp
            val chipY = with(density) {
                (dragFraction * stripHeightPx).toDp() - chipHeight / 2
            }.coerceIn(0.dp, with(density) { stripHeightPx.toDp() } - chipHeight)
            Text(
                text = label(dragFraction),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .absoluteOffset(x = 36.dp, y = chipY)
                    .background(Color(0xE61A1B35), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

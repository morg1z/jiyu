package com.haise.jiyu.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import compose.icons.TablerIcons
import compose.icons.tablericons.Sun

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelContent(
    text: String,
    chapterTitle: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    pageCurlEnabled: Boolean = false,
    curlStyle: String = com.haise.jiyu.settings.CurlStyleSetting.CLASSIC,
    autoNextChapter: Boolean = false,
    // Audit RD-6: pozice v novele = (index odstavce, pixel offset v nem) - obnovi se
    // z chapter.lastPageRead/lastScrollOffset a hlasi se zpet do VM
    // (onNovelProgressChanged), jinak novela nikdy nezapisuje postup/historii.
    initialIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onProgressChanged: (index: Int, offsetPx: Int, totalItems: Int) -> Unit = { _, _, _ -> },
) {
    var fontSize by remember { mutableStateOf(16f) }
    var lineSpacing by remember { mutableStateOf(1.6f) }
    var bgColorIndex by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    val bgOptions = listOf(
        Color(0xFF0A0A14) to Color(0xFFE8E8E8),
        Color(0xFF1A110A) to Color(0xFFE8D8C0),
        Color(0xFFF5F0E8) to Color(0xFF1A1A1A),
        Color.White to Color.Black,
    )
    val (bgColor, textColor) = bgOptions[bgColorIndex.coerceIn(0, bgOptions.lastIndex)]
    val paragraphs = remember(text) { text.split("\n").filter { it.isNotBlank() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        TopAppBar(
            title = { Text(chapterTitle, color = Color(0xFFE8E8E8), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            actions = {
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(TablerIcons.Sun, stringResource(R.string.settings_title), tint = Color(0xFFB0BEC5))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D1A)),
        )

        AnimatedVisibility(visible = showSettings, enter = slideInVertically(), exit = slideOutVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1B35)),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(stringResource(R.string.reader_font_size_label, fontSize.toInt()), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { if (fontSize > 10f) fontSize -= 1f }) {
                                Text(stringResource(R.string.reader_font_decrease), color = Color(0xFFE8E8E8), fontSize = 13.sp)
                            }
                            IconButton(onClick = { if (fontSize < 30f) fontSize += 1f }) {
                                Text(stringResource(R.string.reader_font_increase), color = Color(0xFFE8E8E8), fontSize = 17.sp)
                            }
                        }
                    }
                    Text(stringResource(R.string.reader_line_spacing_label, String.format("%.1f", lineSpacing)), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    Slider(
                        value = lineSpacing, onValueChange = { lineSpacing = it },
                        valueRange = 1.0f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF8B5CF6), activeTrackColor = Color(0xFF8B5CF6)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            stringResource(R.string.reader_theme_dark),
                            stringResource(R.string.reader_theme_sepia),
                            stringResource(R.string.reader_theme_paper),
                            stringResource(R.string.reader_theme_white),
                        ).forEachIndexed { i, label ->
                            TextButton(
                                onClick = { bgColorIndex = i },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (bgColorIndex == i) Color(0xFF8B5CF6) else Color(0xFFB0BEC5),
                                ),
                            ) { Text(label, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        if (pageCurlEnabled) {
            PageCurlNovelReader(
                text = displayText,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                textColor = textColor,
                bgColor = bgColor,
                onChapterBoundary = { direction ->
                    // Auto-next vypnuty = obrat za okraj kapitoly se jen vrati,
                    // preklepnuty fling nesmi hodit na jinou kapitolu ani dozadu.
                    if (autoNextChapter) {
                        if (direction == TurnDirection.NEXT) onNext() else onPrev()
                    }
                },
                curlStyle = curlStyle,
                // Curl strankuje interne - hlasi (index stranky, 0, pocet stranek).
                onProgressChanged = { idx, total -> onProgressChanged(idx, 0, total) },
            )
        } else {
            // rememberLazyListState s ulozenou pozici - bez nej se novela vzdy
            // otevre odshora a ulozeny postup se hned prepise nulou (RD-6).
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialIndex.coerceAtLeast(0),
                initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0),
            )
            // Report jen pri zmene indexu odstavce - pixelove offsety resi debounce
            // v ReaderViewModel.saveWebtoonScrollOffset; celkove pocet = odstavce +
            // koncovy nav radka (item v items() pod tim) neni odstavec - hlasi se
            // jen odstavcova cast: index posledniho odstavce = paragraphs.lastIndex,
            // a reachedEnd v VM spusti read=true az na poslednim odstavci.
            LaunchedEffect(listState, paragraphs.size) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .distinctUntilChanged()
                    .collect { (idx, off) -> onProgressChanged(idx, off, paragraphs.size) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                itemsIndexed(paragraphs) { index, paragraph: String ->
                    Text(
                        text = paragraph,
                        color = textColor,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp,
                        modifier = Modifier.padding(bottom = (fontSize * 0.75f).dp),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        Arrangement.SpaceBetween,
                    ) {
                        if (hasPrev) {
                            TextButton(onClick = onPrev) { Text(stringResource(R.string.reader_prev_novel), color = Color(0xFF34D1BF)) }
                        } else { Spacer(Modifier) }
                        if (hasNext) {
                            TextButton(onClick = onNext) { Text(stringResource(R.string.reader_next_novel), color = Color(0xFF34D1BF)) }
                        }
                    }
                }
            }
        }
    }
}

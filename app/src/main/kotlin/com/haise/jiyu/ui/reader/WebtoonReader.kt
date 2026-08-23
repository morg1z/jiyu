package com.haise.jiyu.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.haise.jiyu.R
import com.haise.jiyu.translate.TranslatedBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Vertikální webtoon reader ────────────────────────────────────────────────

@Composable
fun WebtoonReader(
    pages: List<String>,
    initialPage: Int,
    initialScrollOffset: Int = 0,
    onScrollOffsetChanged: (Int) -> Unit = {},
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    textScale: Float,
    onPageChanged: (Int) -> Unit,
    tapZoneGrid: TapZoneGrid = TapZoneGrid(),
    tapZonesEnabled: Boolean = true,
    onShowPanel: () -> Unit,
    onNavigatePrev: () -> Unit = {},
    onNavigateNext: () -> Unit = {},
    scrollSpeedMultiplier: Float = 1.0f,
    cropBorders: Boolean = false,
    volumeKeysNav: Boolean = true,
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Uzsi typ nez Exception zamerne - viz stejne misto v ReaderPager.kt.
        try { focusRequester.requestFocus() } catch (_: IllegalStateException) { }
    }

    // Zabráníme náhodnému otevření panelu při scrollování ve webtoon módu.
    // Po ukončení scrollu čekáme 150 ms, než přijmeme další tap jako záměrný.
    var wasRecentlyScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            wasRecentlyScrolling = true
        } else {
            delay(150L)
            wasRecentlyScrolling = false
        }
    }

    // Dokud probiha programove obnoveni pozice (scrollToItem nize), snapshotFlow pod tim
    // NESMI zapisovat do DB - jinak by se ulozena pozice cteni pri kazdem otevreni kapitoly
    // vynulovala, presne to hlasil uzivatel ("vzdy se otevre od zacatku").
    //
    // `isRestoringPosition` se musi nastavit na true PRI KAZDE ZMENE `pages` (tedy pri
    // kazdem prechodu na jinou kapitolu), ne jen jednou pri prvnim slozeni - `listState` tu
    // zije PO CELOU dobu, co je WebtoonReader na obrazovce (neni klicovany na kapitolu), takze
    // pri prechodu na novou kapitolu zustavala stara scroll pozice z PREDCHOZI kapitoly a nova
    // kapitola tak neotevrela nahore, ale nekde uprostred/na konci - podle toho, kam nahodou
    // stary index/offset v novem (jinak dlouhem) seznamu stranek padl (review nalez uzivatele).
    var isRestoringPosition by remember { mutableStateOf(true) }
    LaunchedEffect(pages) {
        isRestoringPosition = true
        if (pages.isNotEmpty()) {
            val target = initialPage.coerceIn(0, pages.lastIndex)
            // scrollToItem() hned po prvnim slozeni LazyColumn muze tise selhat a skoncit
            // na indexu 0 - stranky jsou obrazky s neznamou vyskou predem, takze prvni
            // layout pruchod jeste nemusi byt "usazeny" (overeno zive). Opakuje se tedy,
            // dokud se skutecne netrefi, nebo dokud to po par pokusech nevzda.
            for (attempt in 0 until 8) {
                listState.scrollToItem(target, initialScrollOffset)
                if (listState.firstVisibleItemIndex == target && listState.firstVisibleItemScrollOffset == initialScrollOffset) break
                if (attempt < 7) delay(150L)
            }
        }
        isRestoringPosition = false
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            if (isRestoringPosition) return@collect
            onPageChanged(idx)
            onScrollOffsetChanged(offset)
        }
    }

    val flingBehavior = ScrollableDefaults.flingBehavior()
    val speedFling = remember(scrollSpeedMultiplier, flingBehavior) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(flingBehavior) { performFling(initialVelocity * scrollSpeedMultiplier) }
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = speedFling,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.VolumeDown -> if (volumeKeysNav) {
                        scope.launch {
                            listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(pages.lastIndex))
                        }
                        true
                    } else false
                    Key.VolumeUp -> if (volumeKeysNav) {
                        scope.launch {
                            listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0))
                        }
                        true
                    } else false
                    else -> false
                }
            }
            .pointerInput(tapZonesEnabled, tapZoneGrid) {
                detectTapGestures(onTap = { offset ->
                    val action = if (!tapZonesEnabled) {
                        TapZoneAction.SHOW_PANEL
                    } else {
                        val col = (offset.x / size.width * 3).toInt().coerceIn(0, 2)
                        val row = (offset.y / size.height * 3).toInt().coerceIn(0, 2)
                        tapZoneGrid[row, col]
                    }
                    // Potlačení náhodného otevření panelu při scrollu
                    if (action == TapZoneAction.SHOW_PANEL && wasRecentlyScrolling) return@detectTapGestures
                    when (action) {
                        TapZoneAction.SHOW_PANEL -> onShowPanel()
                        TapZoneAction.PREV_PAGE -> scope.launch {
                            val target = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                        TapZoneAction.NEXT_PAGE -> scope.launch {
                            val target = (listState.firstVisibleItemIndex + 1).coerceAtMost(pages.lastIndex)
                            listState.animateScrollToItem(target)
                        }
                        TapZoneAction.PREV_CHAPTER -> onNavigatePrev()
                        TapZoneAction.NEXT_CHAPTER -> onNavigateNext()
                        TapZoneAction.NONE -> {}
                    }
                })
            },
    ) {
        itemsIndexed(pages) { index, pageUrl ->
            WebtoonPage(
                pageUrl = pageUrl,
                pageIndex = index,
                translateMode = translateMode,
                translatedBlocks = translatedPages[index] ?: emptyList(),
                textScale = textScale,
                cropBorders = cropBorders,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
            )
        }
    }
}

@Composable
private fun WebtoonPage(
    pageUrl: String,
    pageIndex: Int,
    translateMode: Boolean,
    translatedBlocks: List<TranslatedBlock>,
    textScale: Float,
    cropBorders: Boolean = false,
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxWidth()) {
        RetryableAsyncImage(
            url = pageUrl,
            contentDescription = stringResource(R.string.reader_page_content_desc, pageIndex + 1),
            contentScale = ContentScale.FillWidth,
            cropBorders = cropBorders,
            modifier = Modifier.fillMaxWidth(),
            imageModifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size = it },
        )
        // ContentScale.FillWidth nemá letterbox - vykreslený obrázek VŽDY přesně
        // odpovídá naměřenému `size` (žádné mezery po stranách/nahoře/dole na rozdíl
        // od MangaReaderu, kde se imageRect počítá přes imageDisplayRect), takže stačí
        // holý obdélník (0,0)..(šířka,výška) a stejný sdílený BubbleOverlayLayer jako
        // v MangaReaderu (ReaderPager.kt) - viz TranslationLayer.kt.
        if (translateMode && translatedBlocks.isNotEmpty() && size != IntSize.Zero) {
            val imageRect = remember(size) {
                with(density) { Rect(0f, 0f, size.width.toDp().value, size.height.toDp().value) }
            }
            BubbleOverlayLayer(
                blocks = translatedBlocks,
                imageRect = imageRect,
                textScale = textScale,
                pageIndex = pageIndex,
                pageUrl = pageUrl,
                flippedBubbles = flippedBubbles,
                onToggleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
            )
        }
    }
}

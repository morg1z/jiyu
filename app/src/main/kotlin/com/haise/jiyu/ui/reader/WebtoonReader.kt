package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.translate.TranslatedBlock
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.ArrowRight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Vertikální webtoon reader ────────────────────────────────────────────────
//
// Souvislý scroll přes JEDEN nebo VÍCE segmentů (kapitol) - viz [WebtoonSegment]. Mimo
// "Nekonečné čtení" je `segments` vždy jednoprvkový a chová se přesně jako dřívější plochý
// seznam `pages` (viz historie souboru). Se zapnutým nastavením ViewModel postupně přidává
// další segmenty na konec ([ReaderViewModel.appendNextWebtoonSegment]) - tenhle Composable je
// jen vykresluje jako jeden souvislý LazyColumn s tenkou "hranicí kapitoly" kartou mezi nimi.

private data class SegmentRange(val chapterId: String, val startFlat: Int, val pageCount: Int)

@Composable
fun WebtoonReader(
    segments: List<WebtoonSegment>,
    initialPage: Int,
    initialScrollOffset: Int = 0,
    onNeedMoreSegments: () -> Unit = {},
    onVisibleChapterChanged: (chapterId: String, localIndex: Int, localOffset: Int) -> Unit = { _, _, _ -> },
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    textScale: Float,
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
    // Viz RetryableAsyncImage.referer.
    referer: String? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Uzsi typ nez Exception zamerne - viz stejne misto v ReaderPager.kt.
        try { focusRequester.requestFocus() } catch (_: IllegalStateException) { }
    }

    // Ploche mapovani (globalni index v LazyColumn) -> (chapterId, lokalni index v ramci
    // segmentu) - mezi kazdou dvojici segmentu je NAVIC jedna "hranice kapitoly" polozka
    // (viz stavba LazyColumn nize), ktera do zadneho segmentu nepatri (mapFlatIndex ji
    // preskoci - vraci null).
    val segmentRanges = remember(segments) {
        var offset = 0
        segments.mapIndexed { idx, seg ->
            val range = SegmentRange(seg.chapterId, offset, seg.pages.size)
            offset += seg.pages.size
            if (idx != segments.lastIndex) offset += 1
            range
        }
    }
    val lastPageFlatIndex = remember(segments) {
        segmentRanges.lastOrNull()?.let { it.startFlat + it.pageCount - 1 } ?: 0
    }
    fun mapFlatIndex(flatIdx: Int): Pair<String, Int>? {
        for (r in segmentRanges) {
            val local = flatIdx - r.startFlat
            if (local in 0 until r.pageCount) return r.chapterId to local
        }
        return null
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
    // `isRestoringPosition` se musi nastavit na true PRI KAZDE ZMENE PRVNIHO segmentu (tedy
    // pri kazdem PLNEM prechodu na jinou "otevrenou" kapitolu), ne pri kazde zmene `segments` -
    // "Nekonecne cteni" prubezne PRIDAVA dalsi segmenty na konec BEZE ZMENY prvniho, a to
    // nesmi zpusobit skok zpatky na zacatek prvniho segmentu (proto klic jen na
    // `segments.firstOrNull()?.chapterId`, ne na cely seznam).
    var isRestoringPosition by remember { mutableStateOf(true) }
    LaunchedEffect(segments.firstOrNull()?.chapterId) {
        isRestoringPosition = true
        val firstPageCount = segments.firstOrNull()?.pages?.size ?: 0
        if (firstPageCount > 0) {
            val target = initialPage.coerceIn(0, firstPageCount - 1)
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

    LaunchedEffect(listState, segments) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            if (isRestoringPosition) return@collect
            mapFlatIndex(idx)?.let { (chapterId, localIdx) ->
                onVisibleChapterChanged(chapterId, localIdx, offset)
            }
            // Nekonecne cteni - jakmile se priblizime ke konci POSLEDNIHO nacteneho segmentu,
            // ViewModel potichu stahne a prileji dalsi kapitolu (viz appendNextWebtoonSegment) -
            // pokud uz zadna neni/neni zapnute, je to no-op.
            if (idx >= lastPageFlatIndex - 3) onNeedMoreSegments()
        }
    }

    val flingBehavior = ScrollableDefaults.flingBehavior()
    val speedFling = remember(scrollSpeedMultiplier, flingBehavior) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(flingBehavior) { performFling(initialVelocity * scrollSpeedMultiplier) }
        }
    }

    val maxFlatIndex = remember(segments) {
        (segmentRanges.lastOrNull()?.let { it.startFlat + it.pageCount - 1 } ?: 0).coerceAtLeast(0)
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
                            listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(maxFlatIndex))
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
                            val target = (listState.firstVisibleItemIndex + 1).coerceAtMost(maxFlatIndex)
                            listState.animateScrollToItem(target)
                        }
                        TapZoneAction.PREV_CHAPTER -> onNavigatePrev()
                        TapZoneAction.NEXT_CHAPTER -> onNavigateNext()
                        TapZoneAction.NONE -> {}
                    }
                })
            },
    ) {
        segments.forEachIndexed { segIdx, seg ->
            webtoonSegmentItems(
                segment = seg,
                translateMode = translateMode,
                translatedPages = translatedPages,
                textScale = textScale,
                cropBorders = cropBorders,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
                referer = referer,
            )
            if (segIdx != segments.lastIndex) {
                item(key = "boundary:${seg.chapterId}") {
                    ChapterBoundaryCard(
                        finishedChapterName = seg.chapterName,
                        nextChapterName = segments.getOrNull(segIdx + 1)?.chapterName,
                        onNavigatePrev = onNavigatePrev,
                        onNavigateNext = onNavigateNext,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.webtoonSegmentItems(
    segment: WebtoonSegment,
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    textScale: Float,
    cropBorders: Boolean,
    flippedBubbles: Set<String>,
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit,
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit,
    referer: String?,
) {
    itemsIndexed(segment.pages, key = { i, _ -> "${segment.chapterId}:$i" }) { index, pageUrl ->
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
            referer = referer,
        )
    }
}

/**
 * "Nekonečné čtení" (viz [WebtoonReader]) - tenká karta mezi dvěma souvisle napojenými
 * kapitolami. Slouží jednak jako vizuální oddělovač (kde končí jedna a začíná druhá), jednak
 * jako ruční zkratka - tlačítka volají STEJNÉ [onNavigatePrev]/[onNavigateNext], co používá
 * zbytek čtečky (spodní lišta), takže odpovídají kapitole, která právě skončila (viz
 * ReaderViewModel.onWebtoonVisibleChapterChanged - "aktivní" kapitola se aktualizuje dřív, než
 * se sem uživatel doscrolluje).
 */
@Composable
private fun ChapterBoundaryCard(
    finishedChapterName: String,
    nextChapterName: String?,
    onNavigatePrev: () -> Unit,
    onNavigateNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF111B35).copy(alpha = 0.9f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.webtoon_chapter_boundary_finished, finishedChapterName),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (nextChapterName != null) {
            Text(
                text = stringResource(R.string.webtoon_chapter_boundary_next, nextChapterName),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onNavigatePrev, modifier = Modifier.weight(1f)) {
                Icon(TablerIcons.ArrowLeft, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(stringResource(R.string.reader_prev_chapter_desc), fontSize = 12.sp)
            }
            OutlinedButton(onClick = onNavigateNext, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reader_next_chapter_desc), fontSize = 12.sp)
                Icon(TablerIcons.ArrowRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
            }
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
    referer: String? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // `size` z onSizeChanged se nastaví, jakmile Compose obrázek ZALOŽÍ (i během
    // Loading/Error stavu Coilu) - samo o sobě tedy neříká nic o tom, jestli je stránka
    // vůbec vidět. imageLoaded sleduje AsyncImagePainter.State.Success (viz ReaderImage.kt),
    // bez něj bublina plavala nad bílým/rozbitým místem, když se stránka nestihla/nešla
    // načíst (viz shouldShowTranslationOverlay).
    var imageLoaded by remember(pageUrl) { mutableStateOf(false) }
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
            onLoadedChange = { imageLoaded = it },
            referer = referer,
        )
        // ContentScale.FillWidth nemá letterbox - vykreslený obrázek VŽDY přesně
        // odpovídá naměřenému `size` (žádné mezery po stranách/nahoře/dole na rozdíl
        // od MangaReaderu, kde se imageRect počítá přes imageDisplayRect), takže stačí
        // holý obdélník (0,0)..(šířka,výška) a stejný sdílený BubbleOverlayLayer jako
        // v MangaReaderu (ReaderPager.kt) - viz TranslationLayer.kt.
        if (translateMode && size != IntSize.Zero &&
            shouldShowTranslationOverlay(hasBlocks = translatedBlocks.isNotEmpty(), imageLoaded = imageLoaded)
        ) {
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

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.haise.jiyu.R
import com.haise.jiyu.translate.TranslatedBlock
import com.haise.jiyu.util.PageSlicePlan
import com.haise.jiyu.util.PageSliceRequest
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.ArrowRight
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    // Klíčovaná chapterId, ne plochá jako u ReaderPageru/MangaPageCurlReaderu - viz komentář
    // u ReaderViewModel._translatedPagesByChapter (kolize indexů mezi segmenty, nahlášeno v auditu).
    translatedPagesByChapter: Map<String, Map<Int, List<TranslatedBlock>>>,
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
    // chapterId v callbacku - kazdy segment ma vlastni pageIndex/bubbleIndex
    // prostor, takze bez nej by tap na bublinu v odscrollanem segmentu psal do
    // aktualni kapitoly (audit RD-4/RD-10).
    onToggleBubbleFlip: (chapterId: String, pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _, _ -> },
    onEditBubble: (chapterId: String, pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit = { _, _, _, _, _, _ -> },
    // True po dobu, co ViewModel stahuje a připojuje další segment (viz
    // ReaderViewModel.appendNextWebtoonSegment) - bez indikace uživatel na konci
    // poslední stránky jen marně swipoval, než fetch doběhl (live audit: ~28 s
    // "mrtvého" scrollu na pomalém zdroji).
    isAppendingNextChapter: Boolean = false,
    /** Průběžný report pozice pro edge scrubber: (flat index první viditelné položky,
     *  celkový počet položek seznamu). Volá se ze stejného snapshotFlow jako
     *  [onVisibleChapterChanged] - zdarma, žádný druhý sběr scrollu. */
    onScrollPositionChanged: (firstFlatIndex: Int, totalItems: Int) -> Unit = { _, _ -> },
    /** Požadavek na okamžitý skok na frakci (0f..1f) celého proudu - viz ReaderEdgeScrubber.
     *  Po obsloužení se zavolá [onScrubConsumed]. */
    scrubToFraction: Float? = null,
    onScrubConsumed: () -> Unit = {},
    /** Požadavek na skok na LOKALNI index stranky v ramci aktualne viditelne kapitoly
     *  (slider v dolnim panelu). Bez nej byl slider ve webtoon rezimu mrtvy - jumpToPage
     *  kanal konzumovaly jen paged ctecky. Po obslouzeni se zavola [onJumpConsumed]. */
    jumpToPage: Int? = null,
    onJumpConsumed: () -> Unit = {},
    // Viz RetryableAsyncImage.referer.
    referer: String? = null,
) {
    // Vlastni prefetch strategie misto vychoziho (ta sklada jen ~1 polozku dopredu):
    // slozeni WebtoonPage spousti PageSlicer.plan() + Coil load, takze kdyz se sklada
    // az tesne pred viditelnym okrajem, stranka pri plynulem scrollu jeste neni hotova
    // a blikne indikator i kdyz jsou bajty davno v disk cache. Lookahead ~5 dopredu /
    // 2 zpet = dekodovane bitmapy v memory cache jeste pred doskrolovanim - viz
    // paralelni sitovy prefetch cele kapitoly v ReaderViewModel.startChapterPrefetch,
    // ktery pokryva zbytek dopredu.
    val listState = rememberLazyListState(prefetchStrategy = rememberWebtoonPrefetchStrategy())
    val scope = rememberCoroutineScope()

    // Pinch-to-zoom stav pro celý souvislý pás - na rozdíl od MangaReaderu (jedna
    // stránka na obrazovku) se tu nezoomuje jednotlivá stránka, ale celý viditelný
    // výřez LazyColumn (kolem jeho středu, viz graphicsLayer níž). Dokud je scale > 1,
    // LazyColumn si drží svou scroll pozici beze změny (userScrollEnabled = false) a
    // tažení prstem místo scrollování posouvá jen panOffset - přesně stejný vzor jako
    // v ReaderPager.kt, jen aplikovaný na celý scrollovací kontejner místo jedné stránky.
    var scale by rememberSaveable { mutableStateOf(1f) }
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }
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
            // Report pro edge scrubber i behem obnovy pozice - linka se hned nastavi
            // na ulozenou pozici, ne az po prvnim uzivatelskem scrollu.
            onScrollPositionChanged(idx, listState.layoutInfo.totalItemsCount)
            if (isRestoringPosition) return@collect
            mapFlatIndex(idx)?.let { (chapterId, localIdx) ->
                onVisibleChapterChanged(chapterId, localIdx, offset)
            }
            // Nekonecne cteni - jakmile se priblizime ke konci POSLEDNIHO nacteneho segmentu,
            // ViewModel potichu stahne a prileji dalsi kapitolu (viz appendNextWebtoonSegment) -
            // pokud uz zadna neni/neni zapnute, je to no-op.
            // Práh držen znatelne pred koncem - fetch segmentu na pomalem zdroji trva
            // i desitky sekund, 3 stranky rezervy nestacily a scroll dorazil na konec
            // driv, nez novy obsah dorazil (live audit).
            if (idx >= lastPageFlatIndex - APPEND_PREFETCH_DISTANCE) onNeedMoreSegments()
        }
    }

    // Okamzity skok na frakci celeho proudu (edge scrubber). scrollToItem je instant -
    // pri drag streamu (~60 pozadavku/s) kazda nova hodnota zrusi predchozi efekt
    // a list skace primo na prst; cokoli animovaneho by za prstem zustavalo pozadu.
    // Progress zapis bezi dal pres snapshotFlow vyse - stejne jako u normalniho scrollu.
    LaunchedEffect(scrubToFraction) {
        val fraction = scrubToFraction ?: return@LaunchedEffect
        // RD-23: scrub vydany behem 8-pokusoveho restore loopu vyse by dalsi iteraci
        // scrollToItem prepsal - pocka se na dokonceni obnovy pozice, pak se aplikuje.
        while (isRestoringPosition) delay(50L)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 1) {
            listState.scrollToItem((fraction * (total - 1)).toInt().coerceIn(0, total - 1))
        }
        onScrubConsumed()
    }

    // Skok na lokalni index stranky v ramci AKTUALNE viditelne kapitoly (slider
    // v dolnim panelu - jede pres jumpToPage, ne scrubToFraction, protoze cisluje
    // jen v ramci jedne kapitoly). Segment se pozna pres mapFlatIndex z prvni
    // viditelne polozky; kdyz ta padne na "hranici kapitoly" mezi segmenty
    // (vraci null), zkusi se sousedni stranky.
    LaunchedEffect(jumpToPage) {
        val page = jumpToPage ?: return@LaunchedEffect
        // RD-23 - viz scrubToFraction vyse (skok behem obnovy pozice by se prepsal).
        while (isRestoringPosition) delay(50L)
        val first = listState.firstVisibleItemIndex
        val range = mapFlatIndex(first)?.let { (chapterId, _) ->
            segmentRanges.firstOrNull { it.chapterId == chapterId }
        } ?: mapFlatIndex(first + 1)?.let { (chapterId, _) ->
            segmentRanges.firstOrNull { it.chapterId == chapterId }
        } ?: mapFlatIndex(first - 1)?.let { (chapterId, _) ->
            segmentRanges.firstOrNull { it.chapterId == chapterId }
        }
        if (range != null && range.pageCount > 0) {
            listState.scrollToItem(range.startFlat + page.coerceIn(0, range.pageCount - 1))
        }
        onJumpConsumed()
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

    // Placeholder vyska pro nenactene stranky (viz WebtoonPage): fixni 0.7 minej
    // realny pomer stranek (VIZBIG ~0.66, barevne/dvoustranky i 1.4), takze se celkova
    // vyska listu menila podkladama podkladama, jak se obrazky donacitaly - podklad
    // se ted odvozuje z medianu SKUTECNE namerenych pomeru v teto kapitole. Manga zdroje
    // maji pomer stranek dost konzistentni, takze median se ustali po par strankach.
    val pageAspectSamples = remember { mutableStateListOf<Float>() }
    val placeholderAspectRatio by remember {
        derivedStateOf { medianPlaceholderAspect(pageAspectSamples) }
    }

    LazyColumn(
        state = listState,
        flingBehavior = speedFling,
        // Dokud je zoomováno, tažení prstem ovládá jen panOffset (viz pointerInput
        // níž) - normální scroll by se s panováním jinak přetahoval o stejné gesto.
        userScrollEnabled = scale <= 1f,
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
            // Vlastni pinch-zoom detekce misto `detectTransformGestures` - ta v Compose
            // Foundation počítá pan/zoom už z JEDNOHO prstu (jednoprstový tah = pan se
            // zoom=1f) a jakmile překročí touch slop, VŽDY zkonzumuje position change,
            // takže by tím zkonzumovala i každé jednoprstové táhnutí určené pro scroll
            // LazyColumn (přesně stejný nález jako u MangaPageCurlReader.kt s curl
            // gestem). Tahle verze čeká, dokud nejsou dole aspoň 2 prsty, než začne
            // cokoliv číst nebo konzumovat - jednoprstové scrollování tak projde
            // nedotčené k LazyColumn.
            .pointerInput(Unit) {
                detectTwoFingerPinchZoom { zoomChange, panChange ->
                    val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale > 1f) panOffset += panChange else panOffset = Offset.Zero
                }
            }
            .pointerInput(tapZonesEnabled, tapZoneGrid) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val result = doubleTapZoomTransform(offset, size, scale)
                        scale = result.scale
                        panOffset = result.panOffset
                    },
                    onTap = { offset ->
                        val action = tapZoneAction(offset, size, tapZonesEnabled, tapZoneGrid)
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
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = panOffset.x
                translationY = panOffset.y
            },
    ) {
        segments.forEachIndexed { segIdx, seg ->
            webtoonSegmentItems(
                segment = seg,
                zoomActive = scale > 1f,
                translateMode = translateMode,
                translatedPagesByChapter = translatedPagesByChapter,
                textScale = textScale,
                cropBorders = cropBorders,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
                referer = referer,
                placeholderAspectRatio = placeholderAspectRatio,
                onPageAspectMeasured = { ratio ->
                    pageAspectSamples.add(ratio)
                    // Strop na pocet vzorku - u dlouhe kapitoly by seznam rostl bez
                    // meze; stare stranky (recompose po odscrollovani) navic hlasí
                    // pomer znovu, takze staci poslednich par desitek.
                    while (pageAspectSamples.size > MAX_ASPECT_SAMPLES) pageAspectSamples.removeAt(0)
                },
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
        if (isAppendingNextChapter) {
            item(key = "appending_next_chapter") {
                NextChapterLoadingRow()
            }
        }
    }
}

private fun LazyListScope.webtoonSegmentItems(
    segment: WebtoonSegment,
    zoomActive: Boolean,
    translateMode: Boolean,
    translatedPagesByChapter: Map<String, Map<Int, List<TranslatedBlock>>>,
    textScale: Float,
    cropBorders: Boolean,
    flippedBubbles: Set<String>,
    onToggleBubbleFlip: (chapterId: String, pageIndex: Int, bubbleIndex: Int) -> Unit,
    onEditBubble: (chapterId: String, pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit,
    referer: String?,
    placeholderAspectRatio: Float,
    onPageAspectMeasured: (Float) -> Unit,
) {
    val chapterTranslations = translatedPagesByChapter[segment.chapterId] ?: emptyMap()
    // Flip set je globalni s klicem "$chapterId:..." - pro tenhle segment se
    // projektuje na plochy "pageIndex:bubbleIndex" a callbacky se obali o
    // chapterId segmentu (audit RD-4/RD-10).
    val segmentFlipped = flippedKeysForChapter(flippedBubbles, segment.chapterId)
    itemsIndexed(segment.pages, key = { i, _ -> "${segment.chapterId}:$i" }) { index, pageUrl ->
        WebtoonPage(
            pageUrl = pageUrl,
            pageIndex = index,
            zoomActive = zoomActive,
            translateMode = translateMode,
            translatedBlocks = chapterTranslations[index] ?: emptyList(),
            textScale = textScale,
            cropBorders = cropBorders,
            flippedBubbles = segmentFlipped,
            onToggleBubbleFlip = { pi, bi -> onToggleBubbleFlip(segment.chapterId, pi, bi) },
            onEditBubble = { pi, ot, ct, x, y -> onEditBubble(segment.chapterId, pi, ot, ct, x, y) },
            referer = referer,
            placeholderAspectRatio = placeholderAspectRatio,
            onPageAspectMeasured = onPageAspectMeasured,
        )
    }
}

/** Indikace "stahuju se stranky dalsi kapitoly" na konci seznamu - viz isAppendingNextChapter. */
@Composable
private fun NextChapterLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            text = stringResource(R.string.webtoon_loading_next_chapter),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
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
    zoomActive: Boolean,
    translateMode: Boolean,
    translatedBlocks: List<TranslatedBlock>,
    textScale: Float,
    cropBorders: Boolean = false,
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit = { _, _, _, _, _ -> },
    referer: String? = null,
    placeholderAspectRatio: Float = WEBTOON_PLACEHOLDER_ASPECT_RATIO,
    onPageAspectMeasured: (Float) -> Unit = {},
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // `size` z onSizeChanged se nastaví, jakmile Compose obrázek ZALOŽÍ (i během
    // Loading/Error stavu Coilu) - samo o sobě tedy neříká nic o tom, jestli je stránka
    // vůbec vidět. imageLoaded sleduje AsyncImagePainter.State.Success (viz ReaderImage.kt),
    // bez něj bublina plavala nad bílým/rozbitým místem, když se stránka nestihla/nešla
    // načíst (viz shouldShowTranslationOverlay).
    var imageLoaded by remember(pageUrl) { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current

    // Extrémně vysoké stránky (> 8192 px): jediná bitmapa by byla ~96 MB a nad GPU
    // texture limit se ani nevykreslí - PageSlicer je rozdělí na řezy dekódované
    // po regionech v nativní kvalitě (viz PageSlicePlan.Tiled). Normální stránky
    // dostanou Single a jedou původní cestou beze změny.
    val pageSlicer = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, PageSliceEntryPoint::class.java,
        ).pageSlicer()
    }
    val slicePlan by androidx.compose.runtime.produceState<PageSlicePlan?>(
        initialValue = null, pageUrl, cropBorders, referer,
    ) {
        value = pageSlicer.plan(pageUrl, referer, cropBorders)
    }
    val tiledPlan = slicePlan as? PageSlicePlan.Tiled

    // U řezané stránky známe přesný poměr stran hned z plánu - žádný placeholder skok
    // a overlay má souřadný rámec dřív, než dojedou pixely řezů.
    LaunchedEffect(tiledPlan) {
        if (tiledPlan != null) {
            imageLoaded = true
            onPageAspectMeasured(tiledPlan.aspect)
        }
    }

    // Namereny sirka/vyska pomer realne stranky slouzi jako odhad vysky placeholdru
    // u dosud nenactenych stranek (viz placeholderAspectRatio vyse) - cim vernejsi
    // placeholder, tim mensi posuv obsahu, kdyz se stranka doloaduje.
    LaunchedEffect(imageLoaded, size) {
        if (imageLoaded && size.width > 0 && size.height > 0) {
            onPageAspectMeasured(size.width.toFloat() / size.height.toFloat())
        }
    }

    // Přesný poměr stran z hlavičky souboru - PageSlicer decoduje bounds i pro
    // neřezané stránky, takže placeholder drží PŘESNOU výšku ještě před dojezdem
    // pixelů. Bez toho se stránka po doměření "srazila" na jinou výšku a kotva
    // LazyColumn posunula čtenáře (hlášené "scroll nahoru táhne o kousek dolů").
    // Single.aspect je poměr CEHÉHO obrázku - při zapnutém ořezu okrajů (cropBorders)
    // se zobrazí až oříznutá verze s jiným poměrem, takže tam zůstává medián
    // (u Tiled je aspect už content-rect po ořezu, tam sedí i s cropem).
    val planAspect = when (val p = slicePlan) {
        is PageSlicePlan.Tiled -> p.aspect
        is PageSlicePlan.Single -> if (cropBorders) null else p.aspect
        null -> null
    }

    // Dokud stránka nemá skutečný obrázek (a tedy ani vlastní výšku), Coilův placeholder
    // nemá žádný intrinsic rozměr a Box by se v LazyColumn (viz [WebtoonSegmentPages])
    // změřil na výšku 0 - takže nenačtená stránka nezabírala žádné místo, "zmizela" ze
    // scrollu (list rovnou skočil na další, už načtenou stránku) a loading indikátor
    // uvnitř RetryableAsyncImage neměl kam se vykreslit (nahlášeno: "jsem na page 1 a
    // najednou na page 8, page 2-7 chybí a není tam loading"). Vyhrazený poměr stran po
    // dobu načítání drží rozumnou výšku, než se nahradí SKUTEČNOU výškou obrázku.
    val pageModifier = if (imageLoaded) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().aspectRatio(planAspect ?: placeholderAspectRatio)
    }

    Box(modifier = pageModifier) {
        // slicePlan == null = plán se ještě počítá (decoduje jen hlavičku/bounds). Zatím
        // nesmíme spustit RetryableAsyncImage - pro vysokou stránku by Coil začal dekódovat
        // celou ~96 MB bitmapu dřív, než plán vrátí Tiled. Placeholder drží místo.
        if (tiledPlan != null) {
            TiledWebtoonPage(
                plan = tiledPlan,
                pageUrl = pageUrl,
                referer = referer,
                cropBorders = cropBorders,
                zoomActive = zoomActive,
                onSizeChanged = { size = it },
            )
        } else if (slicePlan is PageSlicePlan.Single) {
            RetryableAsyncImage(
                url = pageUrl,
                contentDescription = stringResource(R.string.reader_page_content_desc, pageIndex + 1),
                contentScale = ContentScale.FillWidth,
                cropBorders = cropBorders,
                // fillMaxSize (ne jen fillMaxWidth) POUZE dokud platí vyhrazený poměr stran výš -
                // jinak by loading indikátor (matchParentSize v RetryableAsyncImage) zdědil
                // stejnou nulovou výšku, kterou má tenhle box vyřešit. Po načtení box zase
                // jen obaluje skutečný obrázek (fillMaxWidth, výška podle obsahu).
                modifier = if (imageLoaded) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
                imageModifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size = it },
                onLoadedChange = { imageLoaded = it },
                // Crossfade fade-uje i disk-cache hity - u predstazenych stranek scrollu
                // "dolehal" obrazek ~300ms po zobrazeni boxu (viz ReaderPager).
                disableCrossfade = true,
                referer = referer,
            )
        } else {
            // slicePlan == null = plán se ještě počítá (bounds/probe, případně download
            // zdroje pro řezy - trvá to i sekundy na pomalém CDN). Bez indikace tu visela
            // jen černá díra - před řezáním stránek tu běžel Lottie spinner od složení
            // (hlášeno: "část se nenačte, kolečko zmizí, musím čekat než se objeví").
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                ReaderPageLoadingIndicator()
            }
        }
        // ContentScale.FillWidth nemá letterbox - vykreslený obrázek VŽDY přesně
        // odpovídá naměřenému `size` (žádné mezery po stranách/nahoře/dole na rozdíl
        // od MangaReaderu, kde se imageRect počítá přes imageDisplayRect), takže stačí
        // holý obdélník (0,0)..(šířka,výška) a stejný sdílený BubbleOverlayLayer jako
        // v MangaReaderu (ReaderPager.kt) - viz TranslationLayer.kt. Pro řezanou stránku
        // je `size` rozměr celého sloupce řezů = rozměr původní stránky.
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
                cropBorders = cropBorders,
                flippedBubbles = flippedBubbles,
                onToggleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
            )
        }
    }
}

/**
 * Řezaná stránka s RUČNÍ virtualizací: vnořený `Column` v položce `LazyColumn` složí
 * všechny řezy najednou (samotný LazyColumn virtualizuje jen stránky, ne řezy), takže
 * bez viditelnostního gate by v paměti zůstala celá stránka - a ~96 MB bitmapa, před
 * kterou řezy chrání, by se vlastně dekódovala po kusech, ale najednou. Proto se z
 * pozice v okně spočítá rozsah viditelných řezů (+1 nahoru, +2 dolů - směrem čtení) a řezy mimo
 * něj jsou jen `Spacer` se správným poměrem stran - žádný decode, žádná bitmapa, jen
 * správná výška. Bitmapy odscrollovaných řezů uvolní Compose+GC hned, případně je krátce
 * podrží Coil memory cache (LRU).
 */
@Composable
private fun TiledWebtoonPage(
    plan: PageSlicePlan.Tiled,
    pageUrl: String,
    referer: String?,
    cropBorders: Boolean,
    zoomActive: Boolean,
    onSizeChanged: (IntSize) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    var visibleSlices by remember(plan) { mutableStateOf(0..0) }
    var lastCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun updateVisibleSlices(coords: LayoutCoordinates) {
        val bounds = coords.boundsInWindow()
        val range = visibleSliceRange(bounds.top, bounds.height, view.height.toFloat(), plan.slices.size)
        if (range != visibleSlices) visibleSlices = range
    }

    // Pinch-zoom posouvá stránku přes graphicsLayer předka - onGloballyPositioned na
    // transformace předků na starších Compose verzích nemusí dobíhat, takže dokud je
    // zoom aktivní, rozsah přepočítáváme každý frame (bez zoomu loop netiká - vsync
    // by jinak držel CPU vzhůru i na statické obrazovce).
    LaunchedEffect(zoomActive, plan) {
        if (!zoomActive) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            lastCoords?.takeIf { it.isAttached }?.let(::updateVisibleSlices)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged(onSizeChanged)
            // boundsInWindow → rozsah řezů ve viewportu. onGloballyPositioned se volá při
            // každém posunu, ale state se mění jen při překročení hranice řezu, takže
            // scroll nerecomposeuje každý frame.
            .onGloballyPositioned { coords ->
                lastCoords = coords
                updateVisibleSlices(coords)
            },
    ) {
        plan.slices.forEachIndexed { sliceIndex, rect ->
            val sliceAspect = rect.width().toFloat() / rect.height().toFloat()
            if (sliceIndex in visibleSlices) {
                WebtoonPageSlice(
                    pageUrl = pageUrl,
                    sliceIndex = sliceIndex,
                    sliceAspect = sliceAspect,
                    referer = referer,
                    cropBorders = cropBorders,
                )
            } else {
                // Mimo viewport: jen vyhrazené místo se správným poměrem - žádná bitmapa.
                androidx.compose.foundation.layout.Spacer(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(sliceAspect),
                )
            }
        }
    }
}

/**
 * Jeden řez extrémně vysoké stránky (viz [PageSlicePlan.Tiled]). Dekóduje se přes
 * [com.haise.jiyu.source.PageSliceFetcher] v nativní kvalitě; řez se sama drží
 * správnou výšku přes `aspectRatio`, takže se řezy skládají bez švů i před příchodem
 * pixelů. Chyba ukáže hubený řádek s "Zkusit znovu" jen pro tenhle řez.
 */
@Composable
private fun WebtoonPageSlice(
    pageUrl: String,
    sliceIndex: Int,
    sliceAspect: Float,
    referer: String?,
    cropBorders: Boolean,
) {
    val context = LocalContext.current
    var retryTrigger by remember(sliceIndex) { mutableStateOf(0) }
    var isError by remember(sliceIndex) { mutableStateOf(false) }
    var errorCause by remember(sliceIndex) { mutableStateOf<Throwable?>(null) }
    var autoRetries by remember(sliceIndex) { mutableStateOf(0) }
    // decodeRegion hlubokého řezu v progresivním JPEG dekóduje všechna data nad sebou,
    // takže trvá i sekundy - bez indikace byl řez jen černá díra (hlášeno jako "část
    // stránky se nenačte, kolečko zmizí"). Lottie z RetryableAsyncImage tu nechceme
    // (animace v každém z ~15 řezů by žrala CPU), stačí tenký progress prstenec.
    var isLoading by remember(sliceIndex) { mutableStateOf(true) }
    // Stejná politika jako RetryableAsyncImage: tiché opakování s prodlevou podle typu
    // chyby (429 čeká na Retry-After, viz pageAutoRetryDelayMs), teprve pak tlačítko.
    LaunchedEffect(isError) {
        if (isError && autoRetries < pageAutoRetryCount(errorCause)) {
            delay(pageAutoRetryDelayMs(errorCause))
            autoRetries++
            isError = false
            retryTrigger++
        }
    }
    val request = remember(pageUrl, sliceIndex, referer, cropBorders, retryTrigger) {
        ImageRequest.Builder(context)
            .data(PageSliceRequest(pageUrl, sliceIndex, referer, cropBorders))
            .memoryCacheKey("pageSlice:$pageUrl:$sliceIndex:$cropBorders")
            .build()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(sliceAspect),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                isError = state is AsyncImagePainter.State.Error
                if (state is AsyncImagePainter.State.Error) errorCause = state.result.throwable
                isLoading = state is AsyncImagePainter.State.Loading || state is AsyncImagePainter.State.Empty
            },
        )
        if (isLoading && !isError) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
        if (isError && autoRetries >= pageAutoRetryCount(errorCause)) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { isError = false; autoRetries = 0 }) {
                    Icon(TablerIcons.AlertCircle, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.common_retry), fontSize = 12.sp)
                }
            }
        }
    }
}

/** Šířka/výška typické manga/manhwa stránky na výšku - jen provizorní odhad, než dorazí skutečný obrázek (viz [WebtoonPage]). */
private const val WEBTOON_PLACEHOLDER_ASPECT_RATIO = 0.7f

/**
 * Bezpecne meze pro odhad pomeru stranek z namerenych vzorku - stranka uzsi nez 0.5
 * je u mangy temer nemozna a nad 1.6 jde o zridkavy spread/ilustraci; bez oříznutí
 * by jedna siřená barevná stránka vychýlila placeholder vsem ostatním.
 */
private const val PLACEHOLDER_ASPECT_MIN = 0.5f
private const val PLACEHOLDER_ASPECT_MAX = 1.6f

/** Kolik stranek pred koncem posledniho segmentu se spusti dotaz na dalsi kapitolu - viz snapshotFlow ve [WebtoonReader]. */
private const val APPEND_PREFETCH_DISTANCE = 6

/** Horni mez poctu vzorku pomeru stranek pro median placeholderu - viz pageAspectSamples ve [WebtoonReader]. */
private const val MAX_ASPECT_SAMPLES = 40

/**
 * Rozsah indexů řezů k dekódování: řezy protínající viewport + 1 rezerva nahoru a
 * +2 DOLŮ (viz [TiledWebtoonPage]). Asymetrie je schválná: čte se dolů a decodeRegion
 * hlubokého řezu v progresivním JPEG dekóduje všechna data nad sebou - s rezervou jen
 * +1 čtenář do černého řezu doscrolluje dřív, než decode doběhne (hlášeno: "část se
 * nenačte a musím čekat"). Každý řez navíc ~6 MB bitmapy (800x2048) - levné.
 * `pageTopInWindow` = y-souřadnice horního okraje stránky v okně
 * (záporná = stránka přečuhuje nad obrazovku). Stránka celá mimo viewport vrátí prázdný
 * rozsah - neviditelné řezy zůstávají jen vyhrazené místo (Spacer), žádný decode.
 * Internal pro JVM testy - čistá matematika bez Compose.
 */
internal fun visibleSliceRange(
    pageTopInWindow: Float,
    pageHeightPx: Float,
    viewportHeightPx: Float,
    sliceCount: Int,
): IntRange {
    if (pageHeightPx <= 0f || sliceCount <= 0) return IntRange.EMPTY
    val first = ((-pageTopInWindow / pageHeightPx) * sliceCount).toInt()
    val last = (((viewportHeightPx - pageTopInWindow) / pageHeightPx) * sliceCount).toInt()
    return (first - 1).coerceAtLeast(0)..(last + 2).coerceAtMost(sliceCount - 1)
}

/**
 * Odhad pomeru sirka/vyska pro placeholder nenactene stranky - median namerenych
 * vzorku (outlier dvoustrana/kratka ilustrace ho nevyhodi), orezany na sane meze,
 * fallback na fixni odhad dokud nic nenamereno. Viditelne v testech (internal).
 */
internal fun medianPlaceholderAspect(samples: List<Float>): Float {
    if (samples.isEmpty()) return WEBTOON_PLACEHOLDER_ASPECT_RATIO
    return samples.sorted()[samples.size / 2]
        .coerceIn(PLACEHOLDER_ASPECT_MIN, PLACEHOLDER_ASPECT_MAX)
}

// ── Look-ahead prefetch strategie LazyColumn ─────────────────────────────────

/** Kolik položek LazyColumn se komponuje dopředu za poslední viditelnou - každá
 *  položka = 1 stránka (nebo hranice kapitoly), takže ~5 stran dopředu ≈ 2-3 obrazovky. */
private const val WEBTOON_PREFETCH_AHEAD = 5

/** Kolik položek se drží složených ZA první viditelnou - návrat zpět pak čte z paměti.
 *  4 ≈ ~2 obrazovky zpět bez přeskládání; dál zpět se servíruje z Coil memory/disk cache. */
private const val WEBTOON_PREFETCH_BEHIND = 4

/**
 * Prefetch strategie pro webtoon LazyColumn: skládá položky [WEBTOON_PREFETCH_AHEAD]
 * před viewportem a [WEBTOON_PREFETCH_BEHIND] za ním. Složená položka rovnou spustí
 * `PageSlicer.plan()` a Coil load (viz WebtoonPage), takže při plynułem scrollu je
 * bitmapa v memory cache ještě než stránka vjede na obrazovku - "vsechno nactene"
 * misto spinneru na kazde nove strance. Vychozi DefaultLazyListPrefetchStrategy sklada
 * jen ~1 polozku dopredu, coz stacilo na lehke obsahy, ale na ~2270 px vysoke stranky
 * s decode byl vzdycky pozadu (live test: po jednom swipu vyletel spinner).
 *
 * schedulePrefetch na uz slozeny index je levny no-op; handly se nemusi drzet - prefetch
 * framework sam rusit nepotrebuje (zpozdene slozeni stranky, pres kterou se preflinglo,
 * jen predstahne jeji bajty do cache, coz je pozadovane chovani).
 */
@Composable
private fun rememberWebtoonPrefetchStrategy(): LazyListPrefetchStrategy = remember {
    object : LazyListPrefetchStrategy {
        override fun LazyListPrefetchScope.onScroll(
            delta: Float,
            layoutInfo: LazyListLayoutInfo,
        ) = scheduleAroundViewport(layoutInfo)

        override fun LazyListPrefetchScope.onVisibleItemsUpdated(
            layoutInfo: LazyListLayoutInfo,
        ) = scheduleAroundViewport(layoutInfo)

        override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
            // Webtoon ctecka neni vnorena do jine lazy kolekce - vola se jen zvenku.
        }

        private fun LazyListPrefetchScope.scheduleAroundViewport(layoutInfo: LazyListLayoutInfo) {
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return
            val first = visible.first().index
            val last = visible.last().index
            val upper = minOf(last + WEBTOON_PREFETCH_AHEAD, layoutInfo.totalItemsCount - 1)
            for (i in (last + 1)..upper) schedulePrefetch(i)
            val lower = maxOf(first - WEBTOON_PREFETCH_BEHIND, 0)
            for (i in (first - 1) downTo lower) schedulePrefetch(i)
        }
    }
}

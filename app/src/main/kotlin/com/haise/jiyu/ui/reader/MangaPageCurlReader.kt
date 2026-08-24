package com.haise.jiyu.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.haise.jiyu.translate.TranslatedBlock
import kotlinx.coroutines.delay

/**
 * Manga/manhwa čtečka s efektem ohýbané stránky - manga obdoba [PageCurlNovelReader],
 * propojuje rozdělení do skupin ([computePageGroups]/[MangaGroupContent], Task 7) se stavem
 * ohybu ([PageCurlState], Task 3), jeho geometrií ([computePageCurlGeometry], Task 4) a
 * vykreslením ([drawPageCurl], Task 5). Signatura je záměrně identická s [MangaReader], aby
 * je [ReaderContent] mohl volat zaměnitelně podle `pageCurlEnabled` toggle.
 *
 * Klíčová vlastnost návrhu: `pages` (a tedy i [computePageGroups] výstup) se mění při KAŽDÉM
 * přechodu na jinou kapitolu (`onNavigatePrevChapter`/`onNavigateNextChapter` -> ViewModel
 * načte nové `pages`), zatímco identita TOHOTO composable zůstává stejná (žádný `key(chapterId)`
 * o úroveň výš v `ReaderScreen`/`ReaderContent`). `currentSingleIndex`/`dragProgress` jsou
 * proto `remember`ované s klíčem `pages` (nová identita listu = nová kapitola => reset), a
 * `currentGroupIndex` se POKAŽDÉ dopočítává přímo z aktuálních `groups`/`currentSingleIndex`
 * - nikdy neuložen jako samostatný "zamrzlý" stav, který by mohl zůstat neplatný proti novým
 * `groups` po přechodu kapitoly (review nález č. 1). Gesto-detekční `pointerInput` bloky jsou
 * klíčované i na `pages` (ne jen na `groups.size`), aby korektně restartovaly při přechodu
 * kapitoly se STEJNÝM počtem skupin jako předchozí (review nález č. 2).
 *
 * Navíc: `currentSingleIndex`/`dragProgress`/`reachedEndManually` a efekt, který volá
 * `onPageChanged`, žijí ÚMYSLNĚ MIMO `key(useSpread)` (na rozdíl od `groups` a všeho z něj
 * odvozeného, co uvnitř zůstává) - stejně jako v `MangaReaderu` (`ReaderPager.kt:160-175`).
 * Otočení zařízení při zapnutém spreadu mění `useSpread`, takže by jinak Compose zahodil a
 * znovu vytvořil celý podstrom uvnitř `key()` a resetoval by tak živou pozici čtenáře zpět na
 * `initialPage` (review nález č. 3 - stejná kategorie chyby jako č. 1/2, jen jiný spouštěč:
 * rotace zařízení místo přechodu kapitoly).
 */
@Composable
fun MangaPageCurlReader(
    pages: List<String>,
    initialPage: Int,
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    reverseLayout: Boolean,
    doublePageSpread: Boolean,
    spreadPageIndices: Set<Int> = emptySet(),
    textScale: Float,
    tapZonesEnabled: Boolean,
    tapZoneGrid: TapZoneGrid = TapZoneGrid(),
    onPageChanged: (Int) -> Unit,
    onShowPanel: () -> Unit,
    onNavigatePrevChapter: () -> Unit = {},
    onNavigateNextChapter: () -> Unit = {},
    onSharePage: (String) -> Unit = {},
    pageScale: String = "fit_width",
    jumpToPage: Int? = null,
    onJumpConsumed: () -> Unit = {},
    autoNextChapter: Boolean = false,
    onAutoNextChapter: () -> Unit = {},
    cropBorders: Boolean = false,
    volumeKeysNav: Boolean = true,
    curlStyle: String = com.haise.jiyu.settings.CurlStyleSetting.CLASSIC,
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
) {
    val resolvedCurlStyle = resolveCurlStyle(curlStyle)
    // Pinch-to-zoom - nezávisí na kapitole (rememberSaveable přežije rotaci); resetuje se
    // explicitně na 1f/Offset.Zero v efektu níže vždy, když se změní stránka NEBO kapitola.
    var scale by rememberSaveable { mutableStateOf(1f) }
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    val resolvedContentScale = when (pageScale) {
        "fit_height" -> ContentScale.FillHeight
        "fit_screen" -> ContentScale.Fit
        "stretch"    -> ContentScale.FillBounds
        else         -> ContentScale.FillWidth
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useSpread = doublePageSpread && isLandscape

    var showShareSheet by remember { mutableStateOf(false) }
    var sharePageUrl by remember { mutableStateOf("") }
    if (showShareSheet) {
        SharePageBottomSheet(pageUrl = sharePageUrl, onDismiss = { showShareSheet = false })
    }

    // Musí žít MIMO `key(useSpread)` níže - `useSpread = doublePageSpread && isLandscape`, takže
    // otočení zařízení při zapnutém spreadu změní klíč a Compose zahodí a znovu vytvoří CELÝ
    // podstrom uvnitř `key()`, včetně `rememberSaveable` stavu v něm (review nález: dřív tu
    // stránka žila UVNITŘ key(useSpread), takže rotace při zapnutém spreadu resetovala
    // `currentSingleIndex` zpět na `initialPage` - uloženou "poslední přečtenou" stránku kapitoly,
    // ne živou pozici čtenáře - a `onPageChanged` pak propagoval tuhle zastaralou hodnotu do
    // trvalého stavu čtecího postupu). Stejný vzor a stejné zdůvodnění jako `MangaReader`
    // (`ReaderPager.kt:160-175`), kde je `currentSingleIndex`/`reachedEndManually` ze stejného
    // důvodu taky MIMO `key(useSpread)`.
    //
    // Klíčováno na `pages` (ne bez klíče jako v `MangaReaderu`) - nová kapitola (nová
    // instance/obsah `pages`) MUSÍ dostat nový pár stavů, starý se zahodí, jinak by gesto-
    // pointerInputy níže (klíčované taky na `pages`) po přechodu kapitoly odkazovaly na
    // OSIŘELÉ MutableState objekty z předchozí kapitoly (review nález č. 2). Klíč na `pages`
    // a fyzická poloha MIMO `key(useSpread)` řeší dva NEZÁVISLÉ problémy zároveň - kapitolu,
    // resp. rotaci - a jsou k sobě kolmé (ani jeden by sám o sobě nestačil).
    var currentSingleIndex by rememberSaveable(pages) { mutableStateOf(initialPage) }
    var dragProgress by remember(pages) { mutableStateOf(0f) }
    // Fix regrese po Critical 1 - `PageCurlState.onDragEnd()` teď spravne cte
    // `rawDragProgress` (nezaclampovany pokus o smer), ale ta hodnota se musi
    // persistovat STEJNE jako `dragProgress`, jinak by pri kazde konstrukci
    // `PageCurlState(...)` defaultovala na 0f a `onDragEnd()` by VZDY vratil
    // `Cancelled` bez ohledu na skutecny tah - otaceni tahem by bylo kompletne
    // nefunkcni (tap zony/volume keys/edge-tap by dal fungovaly, protoze jdou
    // pres `onEdgeTap`/`completeTurn`, ne pres tohle).
    var rawDragProgress by remember(pages) { mutableStateOf(0f) }
    var reachedEndManually by remember(pages) { mutableStateOf(false) }

    // Záměrně počítáno jen z `pages.size`/`currentSingleIndex` (ne z group-indexu/`groups`,
    // které žijí uvnitř `key(useSpread)` a odsud by nebyly vidět) - stejná logika jako
    // `MangaReader` (`ReaderPager.kt:169-174`), aby zůstala 1:1 srovnatelná parita chování
    // (včetně "poslední skupina" hranice u sudého spreadu - to je existující vlastnost
    // MangaReaderu, ne nová regrese zavedená tady).
    LaunchedEffect(currentSingleIndex, pages) {
        scale = 1f
        panOffset = Offset.Zero
        onPageChanged(currentSingleIndex)
        if (pages.size > 1 && currentSingleIndex < pages.size - 1) reachedEndManually = true
        if (reachedEndManually && pages.isNotEmpty() && currentSingleIndex == pages.size - 1 && autoNextChapter) {
            delay(2500)
            if (currentSingleIndex == pages.size - 1) onAutoNextChapter()
        }
    }

    // key(useSpread) zahodí a znovu vytvoří jen věci, které se MUSÍ přepočítat/znovu vytvořit
    // per-spread-mode (rozdělení do skupin a vše z něj odvozené) - stejný vzor jako v
    // MangaReaderu, kde `groups` naopak žije mimo (viz `ReaderPager.kt:150`) - tady zůstává
    // uvnitř, protože nic z curl-gest logiky výše na něm nezávisí.
    key(useSpread) {
        // `groups` závisí na CELÉM `pages` (ne jen `pages.size`) - dvě po sobě jdoucí kapitoly
        // se stejným počtem stránek by jinak sdílely stejnou instanci `groups` a stav výše
        // klíčovaný na `pages` by se recompute-oval, zatímco `groups` ne, což by je rozjelo.
        val groups = remember(pages, useSpread, spreadPageIndices) {
            computePageGroups(pages.size, useSpread, spreadPageIndices)
        }

        // Skupina (curl "stránka") odvozená VŽDY čerstvě z aktuálních `groups`/`currentSingleIndex`
        // - nikdy uložena jako samostatný stav, který by mohl zůstat neplatný proti `groups`
        // vypočítaným z nové kapitoly (review nález č. 1: stará `pageCount`/`currentPageIndex`
        // by jinak přežily přechod kapitoly zamrzlé na hodnotách staré kapitoly).
        fun liveGroupIndex(): Int {
            if (groups.isEmpty()) return 0
            val found = groups.indexOfFirst { currentSingleIndex in it }
            return (if (found < 0) 0 else found).coerceIn(0, groups.lastIndex)
        }

        val currentGroupIndex = liveGroupIndex()
        val currentIndices = groups.getOrElse(currentGroupIndex) { listOf(0) }

        LaunchedEffect(jumpToPage, pages) {
            val target = jumpToPage ?: return@LaunchedEffect
            currentSingleIndex = target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            dragProgress = 0f
            rawDragProgress = 0f
            onJumpConsumed()
        }

        fun applyTurnResult(result: PageTurnResult) {
            when (result) {
                is PageTurnResult.WithinChapter -> {
                    dragProgress = result.newState.dragProgress
                    rawDragProgress = result.newState.rawDragProgress
                    groups.getOrNull(result.newState.currentPageIndex)?.firstOrNull()?.let {
                        currentSingleIndex = it
                    }
                }
                is PageTurnResult.Cancelled -> {
                    dragProgress = result.newState.dragProgress
                    rawDragProgress = result.newState.rawDragProgress
                }
                is PageTurnResult.ChapterBoundary -> {
                    dragProgress = 0f
                    rawDragProgress = 0f
                    if (result.direction == TurnDirection.NEXT) onNavigateNextChapter() else onNavigatePrevChapter()
                }
            }
        }

        fun tryTurn(direction: TurnDirection) {
            if (scale <= 1f) {
                val live = PageCurlState(
                    currentPageIndex = liveGroupIndex(),
                    pageCount = groups.size,
                    dragProgress = dragProgress,
                    rawDragProgress = rawDragProgress,
                )
                applyTurnResult(live.onEdgeTap(direction))
            }
        }

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) { }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.A -> { tryTurn(if (reverseLayout) TurnDirection.NEXT else TurnDirection.PREV); true }
                        Key.DirectionRight, Key.D -> { tryTurn(if (reverseLayout) TurnDirection.PREV else TurnDirection.NEXT); true }
                        Key.VolumeDown -> if (volumeKeysNav) { tryTurn(if (reverseLayout) TurnDirection.PREV else TurnDirection.NEXT); true } else false
                        Key.VolumeUp -> if (volumeKeysNav) { tryTurn(if (reverseLayout) TurnDirection.NEXT else TurnDirection.PREV); true } else false
                        else -> false
                    }
                },
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            val currentLayer = rememberGraphicsLayer()
            var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            // Bug fix - Coil (síť/dekódování/rozskládání dlaždic) doběhne často AŽ PO téhle
            // první rasterizaci, takže se do zamrazené bitmapy pro ohyb "vypálil" napořád
            // prázdný/rozsypaný obrázek (nahlášeno jako tmavé čáry/kostičky). `currentLoaded`
            // jako další klíč LaunchedEffectu níž zajistí PŘERASTERIZACI, jakmile Coil skutečně
            // doběhne - i uprostřed už rozjetého gesta.
            var currentLoaded by remember(currentIndices) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        currentLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = panOffset.x, translationY = panOffset.y,
                    ),
            ) {
                MangaGroupContent(
                    indices = currentIndices, pages = pages, translateMode = translateMode,
                    translatedPages = translatedPages, reverseLayout = reverseLayout,
                    resolvedContentScale = resolvedContentScale, cropBorders = cropBorders,
                    textScale = textScale, flippedBubbles = flippedBubbles,
                    onToggleBubbleFlip = onToggleBubbleFlip, onEditBubble = onEditBubble,
                    onAllImagesLoaded = { currentLoaded = it },
                    disableCrossfade = true,
                )
            }
            LaunchedEffect(currentIndices, pages, translateMode, translatedPages, widthPx, heightPx, currentLoaded) {
                currentBitmap = currentLayer.toImageBitmap()
            }

            // Fix Important 6 - dřív se sousední ("revealed") stránka rasterizovala jen JEDNOU,
            // až v okamžiku, kdy `dragProgress` poprvé přestal být 0f (uživatel začal tahat),
            // typicky předtím, než Coil dokončil dekódování obrázku - a nikdy znovu. První tah
            // po otevření stránky tak často ukázal prázdnou/bílou plochu tam, kde měl být sused.
            // Teď se OBĚ sousední skupiny (další i předchozí) rasterizují PRŮBĚŽNĚ, jakmile je
            // jejich index znám - ne až na začátek gesta - takže Coil má čas dekódovat obrázek
            // dřív, než uživatel vůbec začne táhnout. `revealedBitmap` pak jen VYBÍRÁ z už
            // připravené dvojice podle aktuálního směru tahu.
            val nextGroupIndex = currentGroupIndex + 1
            val prevGroupIndex = currentGroupIndex - 1
            val nextIndices = groups.getOrNull(nextGroupIndex)
            val prevIndices = groups.getOrNull(prevGroupIndex)

            val nextLayer = rememberGraphicsLayer()
            var nextBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            var nextLoaded by remember(nextIndices) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        nextLayer.record { this@drawWithContent.drawContent() }
                        // bez drawContent() - jen rasterizace pro nextBitmap
                    }
                    .clearAndSetSemantics { },
            ) {
                if (nextIndices != null) {
                    MangaGroupContent(
                        indices = nextIndices, pages = pages, translateMode = translateMode,
                        translatedPages = translatedPages, reverseLayout = reverseLayout,
                        resolvedContentScale = resolvedContentScale, cropBorders = cropBorders,
                        textScale = textScale, flippedBubbles = flippedBubbles,
                        onToggleBubbleFlip = onToggleBubbleFlip, onEditBubble = onEditBubble,
                        onAllImagesLoaded = { nextLoaded = it },
                        disableCrossfade = true,
                    )
                }
            }
            LaunchedEffect(nextIndices, pages, translateMode, translatedPages, widthPx, heightPx, nextLoaded) {
                nextBitmap = if (nextIndices != null) nextLayer.toImageBitmap() else null
            }

            val prevLayer = rememberGraphicsLayer()
            var prevBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            var prevLoaded by remember(prevIndices) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        prevLayer.record { this@drawWithContent.drawContent() }
                        // bez drawContent() - jen rasterizace pro prevBitmap
                    }
                    .clearAndSetSemantics { },
            ) {
                if (prevIndices != null) {
                    MangaGroupContent(
                        indices = prevIndices, pages = pages, translateMode = translateMode,
                        translatedPages = translatedPages, reverseLayout = reverseLayout,
                        resolvedContentScale = resolvedContentScale, cropBorders = cropBorders,
                        textScale = textScale, flippedBubbles = flippedBubbles,
                        onToggleBubbleFlip = onToggleBubbleFlip, onEditBubble = onEditBubble,
                        onAllImagesLoaded = { prevLoaded = it },
                        disableCrossfade = true,
                    )
                }
            }
            LaunchedEffect(prevIndices, pages, translateMode, translatedPages, widthPx, heightPx, prevLoaded) {
                prevBitmap = if (prevIndices != null) prevLayer.toImageBitmap() else null
            }

            val revealedBitmap = when {
                dragProgress > 0f -> nextBitmap
                dragProgress < 0f -> prevBitmap
                else -> null
            }

            // Fix Important 5 - jakmile uzivatel zacne pinch-zoomovat pres 1x behem
            // rozjeteho curl-tahu, `dragProgress` musi zustat cisty, jinak by curl overlay
            // zustal trvale "zamrzly" na obrazovce po zbytek zoomovani.
            LaunchedEffect(scale > 1f) {
                if (scale > 1f) {
                    dragProgress = 0f
                    rawDragProgress = 0f
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (scale <= 1f) {
                            // Fix Important 3 - JEN drag (curl) gesto se gatuje na zoom <= 1f,
                            // stejne jako `userScrollEnabled = scale <= 1f` v `MangaReaderu`
                            // gatuje jen swipe pageru, ne tap zony. Predtim bylo i tap gesto
                            // vnorene sem, takze pri priblizeni prestaly fungovat SHOW_PANEL /
                            // predchozi-dalsi kapitola / long-press sdileni zony uplne.
                            Modifier
                                // Klíčováno i na `pages` (ne jen `groups.size`) - jinak by při
                                // přechodu na kapitolu se STEJNÝM počtem skupin jako předchozí
                                // (běžné u podobně dlouhých kapitol) `pointerInput` nerestartoval
                                // a gesta by dál čítala/zapisovala do osiřelých
                                // `currentSingleIndex`/`dragProgress` MutableState objektů zpřed
                                // přechodu, zatímco `groups` výše by už odkazovaly na novou
                                // kapitolu - navigace by tiše přestala reagovat (review nález č. 2).
                                .pointerInput(pages, groups.size, spreadPageIndices, reverseLayout) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val delta = (if (reverseLayout) -dragAmount.x else dragAmount.x) / widthPx
                                            val live = PageCurlState(
                                                currentPageIndex = liveGroupIndex(),
                                                pageCount = groups.size,
                                                dragProgress = dragProgress,
                                                rawDragProgress = rawDragProgress,
                                            )
                                            val updated = live.withDrag(live.dragProgress - delta)
                                            dragProgress = updated.dragProgress
                                            // Fix regrese po Critical 1 - `rawDragProgress` z
                                            // vysledku `withDrag` se musi ulozit zpet do
                                            // persistovaneho state, jinak by pri pusteni prstu
                                            // `onDragEnd()` cetl porad jen defaultni 0f.
                                            rawDragProgress = updated.rawDragProgress
                                        },
                                        onDragEnd = {
                                            val live = PageCurlState(
                                                currentPageIndex = liveGroupIndex(),
                                                pageCount = groups.size,
                                                dragProgress = dragProgress,
                                                rawDragProgress = rawDragProgress,
                                            )
                                            applyTurnResult(live.onDragEnd())
                                        },
                                        onDragCancel = {
                                            // Fix Important 5 - gesture node muze byt zrusen
                                            // uprostred tahu (napr. prevzeti ukazatele jinym
                                            // gesture-nodem pri prechodu do pinch-zoomu) - bez
                                            // resetu by curl overlay zustal zamrzly.
                                            dragProgress = 0f
                                            rawDragProgress = 0f
                                        },
                                    )
                                }
                        } else {
                            Modifier
                        },
                    )
                    // Fix Important 3 - tap gesta (SHOW_PANEL / predchozi-dalsi kapitola /
                    // long-press sdileni) VZDY aktivni, nezavisle na zoomu - presne jako
                    // `MangaReader` (`ReaderPager.kt`), kde tenhle pointerInput blok neni
                    // vubec gatovany na `scale`. Fix Important 4 - `onDoubleTap` doplnen
                    // identicky s `MangaReaderem` (`ReaderPager.kt:270-284`).
                    .pointerInput(pages, groups.size, spreadPageIndices, tapZonesEnabled, tapZoneGrid, reverseLayout) {
                        detectTapGestures(
                            onLongPress = {
                                val liveIndices = groups.getOrElse(liveGroupIndex()) { listOf(0) }
                                sharePageUrl = pages.getOrElse(liveIndices[0]) { "" }
                                if (sharePageUrl.isNotEmpty()) showShareSheet = true
                            },
                            onDoubleTap = { offset ->
                                if (scale > 1f) {
                                    scale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    val zoom = 2.5f
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    scale = zoom
                                    panOffset = Offset(
                                        (offset.x - cx) * (1f - zoom),
                                        (offset.y - cy) * (1f - zoom),
                                    )
                                }
                            },
                            onTap = { offset ->
                                val action = if (!tapZonesEnabled) {
                                    TapZoneAction.SHOW_PANEL
                                } else {
                                    val col = (offset.x / size.width * 3).toInt().coerceIn(0, 2)
                                    val row = (offset.y / size.height * 3).toInt().coerceIn(0, 2)
                                    tapZoneGrid[row, col]
                                }
                                when (action) {
                                    TapZoneAction.SHOW_PANEL -> onShowPanel()
                                    TapZoneAction.PREV_PAGE -> tryTurn(if (reverseLayout) TurnDirection.NEXT else TurnDirection.PREV)
                                    TapZoneAction.NEXT_PAGE -> tryTurn(if (reverseLayout) TurnDirection.PREV else TurnDirection.NEXT)
                                    TapZoneAction.PREV_CHAPTER -> onNavigatePrevChapter()
                                    TapZoneAction.NEXT_CHAPTER -> onNavigateNextChapter()
                                    TapZoneAction.NONE -> {}
                                }
                            },
                        )
                    }
                    // Vlastni pinch-zoom detekce misto `detectTransformGestures` - ta v Compose
                    // Foundation pocita pan/zoom uz z JEDNOHO prstu (jednoprstovy tah = pan se
                    // zoom=1f) a jakmile prekroci touch slop, VZDY zkonzumuje position change,
                    // bez ohledu na to, ze callback nic nedela (newScale zustane 1f). Protoze
                    // tohle beží jako posledni (nejvrchnejsi) sourozenec v retezci, konzumovalo
                    // to KAZDE jednoprstove tazeni jeste pred drag-pointerInputem vyse - curl
                    // gesto tak nikdy nevidelo zadny pohyb (onDrag se nikdy nezavolal). Novel
                    // ctecka tenhle blok vubec nema (nema pinch-zoom), proto tam curl fungoval.
                    // Tahle verze čeká, dokud nejsou dole aspoň 2 prsty, než začne cokoliv číst
                    // nebo konzumovat - jednoprstové gesto tak projde nedotčené k drag detektoru.
                    .pointerInput(Unit) {
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
                                    val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                    scale = newScale
                                    if (newScale > 1f) panOffset += panChange else panOffset = Offset.Zero
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    event = awaitPointerEvent()
                                } while (event.changes.count { it.pressed } >= 2)
                            }
                        }
                    },
            ) {
                val bitmap = currentBitmap
                if (bitmap != null && dragProgress != 0f) {
                    if (resolvedCurlStyle == CurlStyle.ROLL) {
                        // Port karacken.curl (OpenGL) knihovny - viz GLPageCurlView. Rizeno stejnym
                        // `dragProgress`, jen dragProgress>0f = tazeni na DALSI stranku ("forward").
                        com.haise.jiyu.ui.reader.glcurl.GLPageCurlView(
                            currentBitmap = bitmap,
                            prevBitmap = prevBitmap,
                            nextBitmap = nextBitmap,
                            forward = dragProgress > 0f,
                            progress = kotlin.math.abs(dragProgress),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Fix Important 9 - v RTL (`reverseLayout == true`, bezne pro manga) se
                            // strana, ze ktere se stranka odvaluje, zrcadli, aby odpovidala fyzicke
                            // strane, na ktere uzivatel gesto skutecne provadi.
                            val curlFromRight = if (reverseLayout) dragProgress < 0f else dragProgress > 0f
                            val geometry = computePageCurlGeometry(
                                pageWidth = widthPx, pageHeight = heightPx,
                                turningFromRight = curlFromRight,
                                progress = kotlin.math.abs(dragProgress),
                                style = resolvedCurlStyle,
                            )
                            if (resolvedCurlStyle == CurlStyle.WAVE) {
                                drawWaveCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                            } else {
                                drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.haise.jiyu.ui.reader

import com.haise.jiyu.util.report
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.translate.TranslatedBlock
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceFloppy
import compose.icons.tablericons.Share
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Offset není nativně Bundle-savovatelný, takže pro rememberSaveable potřebuje vlastní Saver. */
internal val OffsetSaver = Saver<Offset, List<Float>>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) },
)

/**
 * Rozdělí [pageCount] stránek do skupin - bez spreadu je každá stránka vlastní skupina,
 * se spreadem se párují po dvou, KROMĚ stránek v [spreadPageIndices] (širší-než-vyšší
 * obrázky, #29 fix), které zůstávají samy. Čistá funkce vytažená z `MangaReader` pro
 * JVM testovatelnost a znovupoužití v [MangaPageCurlReader].
 */
fun computePageGroups(pageCount: Int, useSpread: Boolean, spreadPageIndices: Set<Int>): List<List<Int>> {
    if (!useSpread) {
        return (0 until pageCount).map { listOf(it) }
    }
    val result = mutableListOf<List<Int>>()
    var i = 0
    while (i < pageCount) {
        if (i in spreadPageIndices) {
            result.add(listOf(i)); i++
        } else if (i + 1 < pageCount && (i + 1) !in spreadPageIndices) {
            result.add(listOf(i, i + 1)); i += 2
        } else {
            result.add(listOf(i)); i++
        }
    }
    return result
}

// ── Horizontální manga reader (s pinch-to-zoom) ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReader(
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
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit = { _, _, _, _, _ -> },
    // Viz RetryableAsyncImage.referer.
    referer: String? = null,
    /**
     * Stabilní identita "otevřeného obsahu" pro klíče remember/key - v nekonečném čtení
     * (viz ReaderViewModel.onPagedFlatPageChanged) `pages` roste přilepením další kapitoly,
     * což jako klíč nové identity nesmí fungovat (resetovala by se pozice/zoom při každém
     * appendu). Volající předá id PRVNÍHO segmentu; null = klíčem je `pages` list samotný
     * (klasický režim, kde nová kapitola = nová instance listu).
     */
    contentEpoch: Any? = null,
) {
    val epoch = contentEpoch ?: pages
    // Pinch-to-zoom stav — žije tady (jediný spotřebitel), ne v ReaderContent -
    // rememberSaveable, aby otočení obrazovky (config change) nezahodilo rozostřený zoom.
    var scale by rememberSaveable { mutableStateOf(1f) }
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    // Zoom se resetuje při změně stránky - obalíme předaný onPageChanged místo toho, aby
    // si to musel pamatovat volající (ReaderContent), který o scale/panOffset už neví nic.
    fun handlePageChanged(page: Int) {
        scale = 1f
        panOffset = Offset.Zero
        onPageChanged(page)
    }

    val resolvedContentScale = when (pageScale) {
        "fit_height" -> ContentScale.FillHeight
        "fit_screen" -> ContentScale.Fit
        "stretch"    -> ContentScale.FillBounds
        else         -> ContentScale.FillWidth
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useSpread = doublePageSpread && isLandscape

    // Dvoustránkové zobrazení: skupiny po 2 stránkách.
    // Stránky, které jsou samy o sobě šiřší než vysoké (#29), se nezačleňují do páru.
    val groups: List<List<Int>> = remember(pages.size, useSpread, spreadPageIndices) {
        computePageGroups(pages.size, useSpread, spreadPageIndices)
    }

    var showShareSheet by remember { mutableStateOf(false) }
    var sharePageUrl by remember { mutableStateOf("") }
    if (showShareSheet) {
        SharePageBottomSheet(pageUrl = sharePageUrl, referer = referer, onDismiss = { showShareSheet = false })
    }

    // Tracks the single page index across recompositions and spread-mode resets.
    // Lives OUTSIDE key(useSpread) so it survives the pager recreation and gives the
    // new pager its correct starting group.
    // Klic `epoch` (nova kapitola/obsah) - bez nej by stary index z predchozi kapitoly
    // zustal jako vychozi stranka nove (audit: pager state preziva zmenu kapitoly a
    // obnovuje spatnou stranku; stejny vzor jako MangaPageCurlReader). V nekonecnem
    // cteni je epoch id prvniho segmentu - append nove kapitoly identity nemeni
    // (stranky se jen prilepi na konec), takze pozice se neresetuje.
    var currentSingleIndex by rememberSaveable(epoch) { mutableStateOf(initialPage) }

    // Auto-advance to next chapter when reaching last page with autoNextChapter enabled.
    // reachedEndManually ensures we only trigger after navigating away from initial page,
    // preventing immediate jump when resuming on the last page.
    // Klic `epoch` (viz vys) - bez nej flag prezil prepnuti
    // kapitoly a kapitola obnovena na posledni strance se sama auto-advancovala dal
    // po 2,5 s bez otoceni stranky (audit RD-2; stejny vzor jako currentSingleIndex).
    var reachedEndManually by remember(epoch) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(currentSingleIndex, pages.size) {
        if (pages.size > 1 && currentSingleIndex < pages.size - 1) reachedEndManually = true
        if (reachedEndManually && pages.isNotEmpty() && currentSingleIndex == pages.size - 1 && autoNextChapter) {
            delay(2500)
            if (currentSingleIndex == pages.size - 1) onAutoNextChapter()
        }
    }

    // key(useSpread, epoch) destroys and recreates the pager whenever spread mode changes
    // (i.e. on rotation when double-page is enabled) A pri zmene kapitoly - jinak by
    // pagerState drzel currentPage z predchozi kapitoly a snapshotFlow ho zapsal jako
    // postup nove (audit - pager state preziva zmenu kapitoly). Epoch (ne `pages`) -
    // v nekonecnem cteni append meni `pages`, ale pager se nesmi preestavet: nove
    // stranky se jen objevi na konci pres pageCount. The new pager receives the
    // correct initialGroupIndex immediately — no post-hoc scrollToPage correction
    // and no visual flash to a wrong page.
    key(useSpread, epoch) {
        val initialGroupIndex = remember(groups) {
            groups.indexOfFirst { currentSingleIndex in it }.coerceAtLeast(0)
        }

        val pagerState = rememberPagerState(
            initialPage = initialGroupIndex.coerceIn(0, groups.lastIndex.coerceAtLeast(0)),
            pageCount = { groups.size },
        )
        val scope = rememberCoroutineScope()

        // Append v nekonecnem cteni zmeni `groups`/`pages` na NOVE instance - gesto
        // closury (tap pointerInput nize) by bez updatedState cetly stare a tapy by se
        // po appendu clampovaly na stary konec seznamu.
        val latestGroups by rememberUpdatedState(groups)
        val latestPages by rememberUpdatedState(pages)

        // Nekonecne cteni ZPET: ViewModel.prependPreviousWebtoonSegment vlozi predchozi
        // kapitolu na ZACATEK `pages` - plochy indexy vseho obsahu se posunou o `added`.
        // Bez posunu `currentSingleIndex` by pager zustal na stejnem cisle a ukazal o
        // `added` stranek novejsi obsah (skok o kus kapitoly dopredu). Prepend se pozna
        // podle toho, ze nova listina obsahuje starou jako SUFFIX; append dela to same
        // na konci a indexy necha v klidu.
        var prevPagesList by remember { mutableStateOf(pages) }
        androidx.compose.runtime.LaunchedEffect(pages) {
            val old = prevPagesList
            prevPagesList = pages
            val added = pages.size - old.size
            if (added > 0 && pages.subList(added, pages.size) == old) {
                currentSingleIndex += added
                // Viditelnou stranku dotahnout instantne (ne animovane) na stejny
                // obsah - currentPage je index skupiny, ne stranky.
                val gi = groups.indexOfFirst { currentSingleIndex in it }
                if (gi >= 0) pagerState.scrollToPage(gi)
            }
        }

        // Zaklad pro +/-1 navigaci (tap zony, klavesy, volume). `currentPage` se prepina
        // az v pulce prejezdu a `targetPage` se aktualizuje az kdyz animace realne
        // nastartuje - dva tapy v tesnem sledu (zapocteno i do jednoho framu) by z
        // targetPage dopadly na STEJNY cil a druhy se sezral. `navTarget` si cil
        // drzime synchronne uz v okamziku tapu (stejny princip jako webtoonNavTarget
        // ve WebtoonReaderu); uzivateluv swipe/spusteni animace cile uklidi pres
        // finally, kdyz mezitim neprisel novejsi cil.
        var navTarget by remember { mutableStateOf<Int?>(null) }
        fun navStep(delta: Int) {
            val target = ((navTarget ?: pagerState.targetPage) + delta)
                .coerceIn(0, latestGroups.lastIndex)
            navTarget = target
            scope.launch {
                try {
                    pagerState.animateScrollToPage(target)
                } finally {
                    if (navTarget == target) navTarget = null
                }
            }
        }

        // Reset zoomu pri zmene obsahu (epoch = nova kapitola/rezim) - driv to delala
        // prvni emise snapshotFlow pres handlePageChanged, tu ale guard nize zamerne
        // preskoci, aby append v nekonecnem cteni zoom neresetoval uprostred cteni.
        androidx.compose.runtime.LaunchedEffect(epoch) {
            scale = 1f
            panOffset = Offset.Zero
        }
        androidx.compose.runtime.LaunchedEffect(pagerState, groups) {
            snapshotFlow { pagerState.currentPage }.collect { groupIdx ->
                groups.getOrNull(groupIdx)?.firstOrNull()?.let {
                    // Restart efektu (append v nekonecnem cteni meni `groups`) reemituje
                    // aktualni index - bez guardu by se zbytecne resetoval zoom.
                    if (it != currentSingleIndex) {
                        currentSingleIndex = it
                        handlePageChanged(it)
                    }
                }
            }
        }

        androidx.compose.runtime.LaunchedEffect(jumpToPage) {
            val target = jumpToPage ?: return@LaunchedEffect
            navTarget = null  // okamzity skok zahazuje naplanovany cil tap-navigace
            val groupIdx = groups.indexOfFirst { target in it }.coerceAtLeast(0)
                .coerceIn(0, groups.lastIndex.coerceAtLeast(0))
            // Okamzity skok (ne animateScrollToPage) - edge scrubber posila novy cil
            // na kazdy frame tazeni a animace by za prstem nepomerne zustavala; pro
            // puvodniho volajiciho (release slideru v ReaderControls) je instant
            // skok funkcne stejny.
            pagerState.scrollToPage(groupIdx)
            onJumpConsumed()
        }

        val focusRequester = remember { FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // Uzsi typ nez Exception zamerne: requestFocus hlasi IllegalStateException, kdyz
            // modifier jeste neni pripojeny - bezny zavod pri prvni kompozici, ne defekt.
            // Cokoliv jineho uz je skutecna chyba a nesmi se spolknout.
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) { }
        }

        HorizontalPager(
            state = pagerState,
            // Stranky kolem aktualni se skladaji (a tudiz i stahuji/dekoduji pres Coil)
            // jeste PRED swipen - drive se jeji request spustil az s otocenim, takze na
            // neprecachovane strance bezel sitovy fetch az kdyz uz uzivatel chtel cist
            // (nahlasene "cekani na page"). 2 = dve dopredu i dve zpet - vraceni se
            // swipen zpet je pak instantni z memory cache misto re-decode.
            beyondViewportPageCount = 2,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.A -> { navStep(if (reverseLayout) 1 else -1); true }
                        Key.DirectionRight, Key.D -> { navStep(if (reverseLayout) -1 else 1); true }
                        Key.VolumeDown -> if (volumeKeysNav) { navStep(if (reverseLayout) -1 else 1); true } else false
                        Key.VolumeUp -> if (volumeKeysNav) { navStep(if (reverseLayout) 1 else -1); true } else false
                        else -> false
                    }
                },
            reverseLayout = reverseLayout,
            userScrollEnabled = scale <= 1f,
        ) { groupIdx ->
            val indices = groups[groupIdx]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Orez na slot stranky - pager obsah stranek sam neclipuje, takze bez
                    // nej se sdilena scale/pan transformace aplikuje i na sousedni
                    // (beyond-viewport) stranky a jejich zvetseny/posunuty obsah se
                    // vykresli pres aktualni stranku (hlasene prekryvani pri zoomu).
                    .clipToBounds()
                    // Vlastni dvouprsta pinch detekce misto `detectTransformGestures` - ta
                    // v Compose Foundation pocita pan/zoom uz z JEDNOHO prstu a jakmile
                    // prekroci touch slop, VZDY zkonzumuje position change: jednoprstovy
                    // swipe pro otoceni stranky tim nikdy nedosel k HorizontalPageru
                    // (swipe byl mrtvy) a "spinavy" rychly tap, jehoz prst o slop ujel,
                    // se sezral jako pan misto tapu (hlasene vypadavani tapu). Tato
                    // verze ceka na 2 prsty - swipe i tap prochazeji nedotcene.
                    .pointerInput(Unit) {
                        detectTwoFingerPinchZoom(
                            onGestureEnd = {
                                // Snap zbytkoveho zoomu pod ~10% zpet na 1f - jinak by
                                // pinch mohl nechat napr. scale = 1.003 (vizualne 1x),
                                // ale `scale <= 1f` gaty by pak nastejno zahodily
                                // swipe i cely tap handling bez jakekoliv indicie proc.
                                if (scale < 1.1f) { scale = 1f; panOffset = Offset.Zero }
                            },
                        ) { zoomChange, panChange ->
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) panOffset += panChange
                            else panOffset = Offset.Zero
                        }
                    }
                    // Jednoprsty PAN pri zoomu - swipe pageru je pri scale>1f vypnuty
                    // (userScrollEnabled), takze jednoprsty tah je volny a muze rovnou
                    // posouvat zvetsenou stranku. Pri scale<=1f se vetev ani nemountuje,
                    // takze tap/swipe gesta nezasahuje.
                    .then(
                        if (scale > 1f) {
                            Modifier.pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    panOffset += dragAmount
                                }
                            }
                        } else Modifier
                    )
                    // Klíčovačí `groups.size` tu schvalne neni - append v nekonecnem
                    // cteni by jinak restartoval pointerInput a zrusil prave letici tap.
                    // `pages`/`groups` se cte pres latest* (rememberUpdatedState) a klíč
                    // `epoch` v key(useSpread, epoch) vysi restart pri skutecne zmene
                    // obsahu zajisti sam.
                    .pointerInput(tapZonesEnabled, tapZoneGrid, reverseLayout) {
                        detectTapGestures(
                            onLongPress = {
                                sharePageUrl = latestPages.getOrElse(indices[0]) { "" }
                                if (sharePageUrl.isNotEmpty()) showShareSheet = true
                            },
                            onTap = { offset ->
                            val action = tapZoneAction(offset, size, tapZonesEnabled, tapZoneGrid)
                            when (action) {
                                TapZoneAction.SHOW_PANEL -> onShowPanel()
                                TapZoneAction.PREV_PAGE -> navStep(if (reverseLayout) 1 else -1)
                                TapZoneAction.NEXT_PAGE -> navStep(if (reverseLayout) -1 else 1)
                                TapZoneAction.PREV_CHAPTER -> onNavigatePrevChapter()
                                TapZoneAction.NEXT_CHAPTER -> onNavigateNextChapter()
                                TapZoneAction.NONE -> {}
                            }
                        })
                    }
                    // Aplikuje pinch transformaci na celou skupinu stránek najednou
                    // (obrázek + překladové bubliny), aby bubliny zůstaly na správném
                    // místě při zoomu, místo toho, aby zůstávaly na původní pozici.
                    // Lambda varianta (ne property-based přetížení) čte scale/panOffset až
                    // v draw fázi, ne v kompozici - detectTransformGestures je mění při KAŽDÉM
                    // pohybu prstu (desítky updatů/s), coz by jinak rekomponovalo celý Box
                    // (obrázek + všechny bubliny v translate módu) na každý takový update.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    },
            ) {
                MangaGroupContent(
                    indices = indices,
                    pages = pages,
                    translateMode = translateMode,
                    translatedPages = translatedPages,
                    reverseLayout = reverseLayout,
                    resolvedContentScale = resolvedContentScale,
                    cropBorders = cropBorders,
                    textScale = textScale,
                    flippedBubbles = flippedBubbles,
                    onToggleBubbleFlip = onToggleBubbleFlip,
                    onEditBubble = onEditBubble,
                    // Globalni crossfade(true) prehrava ~300ms fade i u stranek z DISK cache
                    // (fade preskoci jen memory-cache hit) - prefetched stranky pak na kazdem
                    // otoceni "dolehaly" misto okamziteho zobrazeni jako v Kotatsu.
                    disableCrossfade = true,
                    referer = referer,
                )
            }
        }
    }
}

@Composable
fun MangaGroupContent(
    indices: List<Int>,
    pages: List<String>,
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    reverseLayout: Boolean,
    resolvedContentScale: ContentScale,
    cropBorders: Boolean,
    textScale: Float,
    flippedBubbles: Set<String>,
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit,
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit,
    // Viz RetryableAsyncImage.onLoadedChange - curl čtečky tímhle poznají, kdy je bezpečné
    // (znovu) zamrazit skupinu do bitmapy pro ohýbání, místo aby zamrzly prázdný/rozsypaný
    // placeholder navždy.
    onAllImagesLoaded: (Boolean) -> Unit = {},
    // Viz RetryableAsyncImage.disableCrossfade - curl čtečky (jediní volající s true) tímhle
    // zabrání zamrazení bitmapy uprostřed prolínací animace.
    disableCrossfade: Boolean = false,
    // Viz RetryableAsyncImage.referer.
    referer: String? = null,
) {
    if (indices.size == 1) {
        var intrinsicSize by remember(pages[indices[0]]) { mutableStateOf<Size?>(null) }
        var imageLoaded by remember(pages[indices[0]]) { mutableStateOf(false) }
        LaunchedEffect(imageLoaded) { onAllImagesLoaded(imageLoaded) }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight
            Box(modifier = Modifier.fillMaxSize()) {
                RetryableAsyncImage(
                    url = pages[indices[0]],
                    contentDescription = stringResource(R.string.reader_page_content_desc, indices[0] + 1),
                    contentScale = resolvedContentScale,
                    cropBorders = cropBorders,
                    modifier = Modifier.fillMaxSize(),
                    onImageSize = { intrinsicSize = it },
                    onLoadedChange = { imageLoaded = it },
                    disableCrossfade = disableCrossfade,
                    referer = referer,
                )
                if (translateMode) {
                    val blocks = translatedPages[indices[0]]
                    if (shouldShowTranslationOverlay(hasBlocks = !blocks.isNullOrEmpty(), imageLoaded = imageLoaded) && blocks != null) {
                        // Fallback na celý kontejner, když ještě neznáme intrinsic velikost
                        // obrázku (Coil ji nemusí vyslat, když načte z cache) - overlay se
                        // pak vykreslí jako dřív, jen bez korekce letterboxu; jakmile
                        // velikost dorazí, přepočítá se na přesný imageRect (viz imageDisplayRect).
                        val imageRect = remember(intrinsicSize, containerWidth, containerHeight, resolvedContentScale) {
                            intrinsicSize?.let {
                                imageDisplayRect(it, Size(containerWidth.value, containerHeight.value), resolvedContentScale)
                            } ?: Rect(0f, 0f, containerWidth.value, containerHeight.value)
                        }
                        BubbleOverlayLayer(
                            blocks = blocks,
                            imageRect = imageRect,
                            textScale = textScale,
                            pageIndex = indices[0],
                            pageUrl = pages[indices[0]],
                            cropBorders = cropBorders,
                            flippedBubbles = flippedBubbles,
                            onToggleFlip = onToggleBubbleFlip,
                            onEditBubble = onEditBubble,
                        )
                    }
                }
            }
        }
    } else {
        val ordered = if (reverseLayout) indices.reversed() else indices
        val loadedFlags = remember(ordered) { mutableStateListOf(*BooleanArray(ordered.size).toTypedArray()) }
        LaunchedEffect(loadedFlags.toList()) {
            onAllImagesLoaded(loadedFlags.isNotEmpty() && loadedFlags.all { it })
        }
        Row(modifier = Modifier.fillMaxSize()) {
            ordered.forEachIndexed { i, idx ->
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) {
                    var pageIntrinsicSize by remember(pages[idx]) { mutableStateOf<Size?>(null) }
                    RetryableAsyncImage(
                        url = pages[idx],
                        contentDescription = stringResource(R.string.reader_page_content_desc, idx + 1),
                        contentScale = resolvedContentScale,
                        modifier = Modifier.fillMaxSize(),
                        onImageSize = { pageIntrinsicSize = it },
                        onLoadedChange = { loaded -> loadedFlags[i] = loaded },
                        disableCrossfade = disableCrossfade,
                        referer = referer,
                    )
                    if (translateMode) {
                        val blocks = translatedPages[idx]
                        if (shouldShowTranslationOverlay(hasBlocks = !blocks.isNullOrEmpty(), imageLoaded = loadedFlags[i]) && blocks != null) {
                            val imageRect = remember(pageIntrinsicSize, maxWidth, maxHeight, resolvedContentScale) {
                                pageIntrinsicSize?.let {
                                    imageDisplayRect(it, Size(maxWidth.value, maxHeight.value), resolvedContentScale)
                                } ?: Rect(0f, 0f, maxWidth.value, maxHeight.value)
                            }
                            BubbleOverlayLayer(
                                blocks = blocks,
                                imageRect = imageRect,
                                textScale = textScale,
                                pageIndex = idx,
                                pageUrl = pages[idx],
                                cropBorders = cropBorders,
                                flippedBubbles = flippedBubbles,
                                onToggleFlip = onToggleBubbleFlip,
                                onEditBubble = onEditBubble,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Poznámka: "Sdílet odkaz" volá jen [onDismiss] - `onSharePage` z `MangaReaderu` do sem
 * nikdy nebylo napojeno (viz `ReaderContent.kt`), takže tlačítko i dřív jen zavřelo sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePageBottomSheet(pageUrl: String, referer: String? = null, onDismiss: () -> Unit) {
    val saveContext = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.reader_share_page_chooser), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedButton(
                onClick = { onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.6f)),
            ) {
                Icon(TablerIcons.Share, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reader_share_link), color = Color(0xFF4FC3F7))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    // Scope sheetu by se zrušil hned po onDismiss() (kompozice zmizí) a uložení by
                    // se přerušilo - proto scope nezávislý na kompozici.
                    val appContext = saveContext.applicationContext
                    pageSaveScope.launch { savePageToGalleryWithFeedback(appContext, pageUrl, referer) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.6f)),
            ) {
                Icon(TablerIcons.DeviceFloppy, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reader_save_to_gallery), color = Color(0xFF8B5CF6))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private val pageSaveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

private suspend fun savePageToGalleryWithFeedback(context: android.content.Context, url: String, referer: String?) {
    val saved = try {
        saveBitmapToGallery(context, url, referer)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.report("reader:savePageToGallery")
        false
    }
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        android.widget.Toast.makeText(
            context,
            if (saved) R.string.reader_save_success else R.string.reader_save_failed,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/** `true` = uloženo. Blokující práce (decode, komprese, MediaStore) běží na IO. */
internal suspend fun saveBitmapToGallery(context: android.content.Context, url: String, referer: String? = null): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveBitmapToGalleryImpl(context, url, referer) }

private suspend fun saveBitmapToGalleryImpl(context: android.content.Context, url: String, referer: String?): Boolean {
    val bitmap: android.graphics.Bitmap? = if (url.startsWith("/") || url.startsWith("file://")) {
        val path = url.removePrefix("file://")
        android.graphics.BitmapFactory.decodeFile(path)
    } else {
        // Stejny Referer/descramble jako hlavni zobrazovaci cesta - bez nich by ulozeny
        // obrazek na zdroji s hotlink-ochranou/dlazdicovym scramblingem byl bud nedostupny,
        // nebo viditelne poskladany spatne (viz audit).
        val request = buildPageImageRequest(context, url, referer)
        val result = coil.Coil.imageLoader(context).execute(request)
        (result as? coil.request.SuccessResult)?.drawable?.let {
            (it as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }
    }
    bitmap ?: return false
    val filename = "jiyu_${System.currentTimeMillis()}.jpg"
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
            android.os.Environment.DIRECTORY_PICTURES + "/Jiyu")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    // Navratovou hodnotu compress i null stream kontrolovat - drive se pri selhani zapisu
    // stejne vratilo true a v galerii zustala prazdna 0B polozka (audit). Pri chybe radek
    // z MediaStore smazat, at nevisi.
    val written = resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
    } == true
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }
    val updateValues = android.content.ContentValues()
    updateValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, updateValues, null, null)
    return true
}

package com.haise.jiyu.ui.reader

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.haise.jiyu.translate.TranslatedBlock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
/** Polozka fronty cekajicich obratu v ROLL rezimu (viz turnQueue/settlePump v
 *  [MangaPageCurlReader]) - uklada se jen SMER, vysledek se pocita az pri
 *  dequenu z tehdajsiho indexu stranky (jinak by se retezene tapy pocitaly ze
 *  zastarale pozice a vsechny obraty by pristaly na teze strance). SETTLE_BACK =
 *  zruseny tah, ktery se ma jen doanimovat zpet naplocho. */
private enum class CurlQueuedTurn { NEXT, PREV, SETTLE_BACK }

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
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit = { _, _, _, _, _ -> },
    // Viz RetryableAsyncImage.referer.
    referer: String? = null,
    /**
     * Stabilní identita "otevřeného obsahu" pro klíče remember/DisposableEffect - v nekonečném
     * čtení (viz ReaderViewModel.onPagedFlatPageChanged) `pages` roste přilepením další kapitoly,
     * což jako klíč nové identity nesmí fungovat (resetoval by se pozice/drag stav při každém
     * appendu). Volající předá id PRVNÍHO segmentu; null = klíčem je `pages` list samotný.
     */
    contentEpoch: Any? = null,
    /**
     * Nekonečné čtení: přetažení/klik za POSLEDNÍ načtenou stránku zavolá tohle
     * (ReaderViewModel.appendNextWebtoonSegment) místo tvrdého přepnutí kapitoly -
     * navigace by zahodila seskládaný proud segmentů. null = klasický režim (hranice
     * knihy = onNavigatePrev/NextChapter).
     */
    onNeedMorePages: (() -> Unit)? = null,
) {
    val epoch = contentEpoch ?: pages
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
        SharePageBottomSheet(pageUrl = sharePageUrl, referer = referer, onDismiss = { showShareSheet = false })
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
    // Klíčováno na `epoch` (= `pages` v klasickém režimu) - nová kapitola (nová
    // identita obsahu) MUSÍ dostat nový pár stavů, starý se zahodí, jinak by gesto-
    // pointerInputy níže po přechodu kapitoly odkazovaly na OSIŘELÉ MutableState
    // objekty z předchozí kapitoly (review nález č. 2). Klíč na `epoch` a fyzická
    // poloha MIMO `key(useSpread)` řeší dva NEZÁVISLÉ problémy zároveň - kapitolu,
    // resp. rotaci - a jsou k sobě kolmé (ani jeden by sám o sobě nestačil). V
    // nekonečném čtení je epoch id prvního segmentu - append pozici neresetuje.
    var currentSingleIndex by rememberSaveable(epoch) { mutableStateOf(initialPage) }
    var dragProgress by remember(epoch) { mutableStateOf(0f) }
    // Fix regrese po Critical 1 - `PageCurlState.onDragEnd()` teď spravne cte
    // `rawDragProgress` (nezaclampovany pokus o smer), ale ta hodnota se musi
    // persistovat STEJNE jako `dragProgress`, jinak by pri kazde konstrukci
    // `PageCurlState(...)` defaultovala na 0f a `onDragEnd()` by VZDY vratil
    // `Cancelled` bez ohledu na skutecny tah - otaceni tahem by bylo kompletne
    // nefunkcni (tap zony/volume keys/edge-tap by dal fungovaly, protoze jdou
    // pres `onEdgeTap`/`completeTurn`, ne pres tohle).
    var rawDragProgress by remember(epoch) { mutableStateOf(0f) }
    var reachedEndManually by remember(epoch) { mutableStateOf(false) }

    // Záměrně počítáno jen z `pages.size`/`currentSingleIndex` (ne z group-indexu/`groups`,
    // které žijí uvnitř `key(useSpread)` a odsud by nebyly vidět) - stejná logika jako
    // `MangaReader` (`ReaderPager.kt:169-174`), aby zůstala 1:1 srovnatelná parita chování
    // (včetně "poslední skupina" hranice u sudého spreadu - to je existující vlastnost
    // MangaReaderu, ne nová regrese zavedená tady).
    // Klic `epoch` (ne `pages`) - v nekonecnem cteni by kazdy append znovu resetoval
    // zoom a refireoval onPageChanged, prestoze se pozice nezmenila.
    LaunchedEffect(currentSingleIndex, epoch) {
        scale = 1f
        panOffset = Offset.Zero
        onPageChanged(currentSingleIndex)
    }
    // Auto-advance drží klíč `pages.size` (ne jen epoch/currentSingleIndex): v nekonečném
    // čtení append prodlouží seznam, "poslední stránka" se posune a čekající dojetí k
    // onAutoNextChapter se má zrušit přepočtem s NOVÝM seznamem.
    LaunchedEffect(currentSingleIndex, epoch, pages.size) {
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

        // Append v nekonecnem cteni zmeni `pages`/`groups` na NOVE instance - gesto
        // closury (pointerInput bloky nize) by bez updatedState cetly stare odkazy a
        // hranice tahu/tapu by se pocitaly z pocatecniho poctu stranek. Klíč
        // pointerInputu je proto jen `epoch` (skutecna zmena obsahu = restart s novymi
        // delegaty), append uz gesto neprerusuje.
        val latestGroups by rememberUpdatedState(groups)
        val latestPages by rememberUpdatedState(pages)

        // Skupina (curl "stránka") odvozená VŽDY čerstvě z aktuálních `groups`/`currentSingleIndex`
        // - nikdy uložena jako samostatný stav, který by mohl zůstat neplatný proti `groups`
        // vypočítaným z nové kapitoly (review nález č. 1: stará `pageCount`/`currentPageIndex`
        // by jinak přežily přechod kapitoly zamrzlé na hodnotách staré kapitoly).
        fun liveGroupIndex(): Int {
            if (latestGroups.isEmpty()) return 0
            val found = latestGroups.indexOfFirst { currentSingleIndex in it }
            return (if (found < 0) 0 else found).coerceIn(0, latestGroups.lastIndex)
        }

        val currentGroupIndex = liveGroupIndex()
        val currentIndices = groups.getOrElse(currentGroupIndex) { listOf(0) }

        // Dojeti ohybu po pusteni prstu / po tapu - ekvivalent `AnimateCounter` z originalniho
        // PlayLikeCurl dema (~300 ms; dokonceni obratu = Decelerate -> LinearOutSlowInEasing,
        // zruseni = AccelerateDecelerate -> FastOutSlowInEasing). Animuje se jen `dragProgress`,
        // ktery uz GL overlay/Canvas renderer normalne sleduje - `applyTurnResult` (prepnuti
        // indexu + reset) se zavola az na KONCI animace, takze rolujici trubicka fyzicky
        // doleti pred prezumpci obsahu stranky. Spousti se jen pro ROLL - CLASSIC zustava na
        // drivejsim okamzitem snapu. Novy tah/zoom/jump/kapitola job prerusi.
        val coroutineScope = rememberCoroutineScope()
        var settleJob by remember { mutableStateOf<Job?>(null) }
        // Dokonceny, ale jeste NEAPLIKOVANY obrat stranky z rozjete ROLL settle
        // animace - drag (uzivatel popadl stranku uprostred dojeti) ho musi
        // flushnout (aplikovat synchronne pres flushPendingSettle nize), jinak
        // by se jeho stranka ztratila. Tapy se NEFLUSHUJI - ty se radi do
        // turnQueue a kazda dostane vlastni doanimaci (puvodni vizual, zadny
        // instantni proskok na cilovou stranku).
        var pendingSettleResult by remember { mutableStateOf<PageTurnResult?>(null) }
        // Fronta smeru cekajicich obratu (NE hotovych vysledku - indexy by se
        // pocitaly ze zastarale stranky a retezene obraty by vsechny pristaly na
        // teze strance). Pri dequenu se vysledek spocita z TEHDY aktualniho
        // indexu, takze retezeni funguje spravne.
        val turnQueue = remember { ArrayDeque<CurlQueuedTurn>() }
        // `coroutineScope`/`settleJob` ziji UVNITR key(useSpread), zatimco `dragProgress`
        // VNE (remember(epoch)) - zahozeni podstromu (rotace se spreadem, odchod ze
        // ctecky) zabije rozjetou settle korutinu uprostred animate() a bez resetu by
        // `dragProgress` zustal navzdy nenulovy = trubicka/ohyb zamrznou na displeji
        // (hlasene "zasekly curl efekt" u Page rollu). onDispose proto vycisti i
        // stav ohybu.
        DisposableEffect(epoch) {
            onDispose {
                turnQueue.clear()
                pendingSettleResult = null
                settleJob?.cancel()
                dragProgress = 0f
                rawDragProgress = 0f
            }
        }

        // Klic `epoch` (ne `pages`) - jinak by se append v nekonecnem cteni pokusil
        // znovu aplikovat stary jump cil, kdyby ho jeste nestihl onJumpConsumed vycistit.
        LaunchedEffect(jumpToPage, epoch) {
            turnQueue.clear()
            pendingSettleResult = null
            settleJob?.cancel()
            val target = jumpToPage ?: return@LaunchedEffect
            currentSingleIndex = target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            dragProgress = 0f
            rawDragProgress = 0f
            onJumpConsumed()
        }

        fun applyTurnResult(result: PageTurnResult) {
            when (result) {
                is PageTurnResult.WithinChapter -> {
                    // Dokonceny obrat musi vzdy dosednout na Fit Page - deterministicky
                    // reset zoomu/panu primo tady (ne jen pres LaunchedEffect(currentSingleIndex),
                    // ktery bezi az po skladebe a mezitim se muze stat cokoliv).
                    scale = 1f
                    panOffset = Offset.Zero
                    dragProgress = result.newState.dragProgress
                    rawDragProgress = result.newState.rawDragProgress
                    latestGroups.getOrNull(result.newState.currentPageIndex)?.firstOrNull()?.let {
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
                    // Hranice = tvrdy prechod - cekajici obraty z fronty by se
                    // aplikovaly az na NOVOU kapitolu/kontext (spatne indexy).
                    turnQueue.clear()
                    pendingSettleResult = null
                    if (result.direction == TurnDirection.NEXT) {
                        // Nekonecne cteni: za koncem posledniho znameho segmentu se
                        // misto tvrdeho prepnuti kapitoly jen dolnatahne dalsi - po
                        // appendu je "hranice" pryc a tah pokracuje do ni plynule.
                        // PREV zustava navigaci - predchozi kapitoly se na zacatek
                        // nikdy nepredkladaji (stejne jako ve webtoon scrollu).
                        if (onNeedMorePages != null) onNeedMorePages() else onNavigateNextChapter()
                    } else {
                        onNavigatePrevChapter()
                    }
                }
            }
        }

        // Flush pending obratu (viz deklarace vyse) - aplikuje ho synchronne.
        // Pouziva ho JEN drag (prst prebira stranku uprostred dojeti - doanimace
        // se da dokoncit nejpresneji okamzitym dolozeni). Tapy jdou pres
        // turnQueue - zadny snap.
        fun flushPendingSettle() {
            val pending = pendingSettleResult ?: return
            pendingSettleResult = null
            settleJob?.cancel()
            applyTurnResult(pending)
        }

        /**
         * Zpracuje frontu cekajicich obratu JEDNU settle animaci po druhe - kazdy
         * obrat doanimuje pres ohyb jako v puvodnim demu (zadny instantni proskok
         * na cilovou stranku). Vysledek se pocita az pri dequenu z TEHDY
         * aktualniho indexu, takze retezene tapy preklapi za sebou spravne
         * (a tap na posledni strane v kazde kapitole spadne na ChapterBoundary
         * -> navigace/dolnatazeni dalsi kapitoly).
         */
        fun settlePump() {
            if (settleJob?.isActive == true) return
            settleJob = coroutineScope.launch {
                try {
                    while (true) {
                        val queued = turnQueue.removeFirstOrNull() ?: break
                        val res = when (queued) {
                            CurlQueuedTurn.SETTLE_BACK ->
                                PageTurnResult.Cancelled(
                                    PageCurlState(liveGroupIndex(), latestGroups.size, 0f, 0f)
                                )
                            else -> PageCurlState(liveGroupIndex(), latestGroups.size, 0f, 0f)
                                .onEdgeTap(if (queued == CurlQueuedTurn.NEXT) TurnDirection.NEXT else TurnDirection.PREV)
                        }
                        // Hranice kapitoly se neanimeuje (pres ni neni kam ohnout) -
                        // aplikuje se rovnou (navigace / dolnatazeni segmentu).
                        if (res is PageTurnResult.ChapterBoundary) {
                            applyTurnResult(res)
                            continue
                        }
                        val target = when (res) {
                            is PageTurnResult.WithinChapter ->
                                if (res.newState.currentPageIndex > liveGroupIndex()) 1f else -1f
                            else -> 0f
                        }
                        pendingSettleResult = res
                        try {
                            animate(
                                initialValue = dragProgress,
                                targetValue = target,
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = if (target == 0f) FastOutSlowInEasing else LinearOutSlowInEasing,
                                ),
                            ) { value, _ -> dragProgress = value }
                            pendingSettleResult = null
                            applyTurnResult(res)
                        } finally {
                            // Cancel uprostred `animate` cestou, ktera pending nevycistila,
                            // by jinak nechal `dragProgress` zamrzly v puli ohybu a curl
                            // overlay by visel na displeji natrvalo. Cesty co pending
                            // vynulovaly pred cancelem (drag-flush, zoom, jump, cancel)
                            // stav uz samy ukazidily - finally je preskoci.
                            if (pendingSettleResult === res) {
                                pendingSettleResult = null
                                dragProgress = 0f
                                rawDragProgress = 0f
                            }
                        }
                    }
                } finally {
                    settleJob = null
                    // Polozka pridana mezi `break` a `settleJob = null` by jinak zustala stat.
                    if (turnQueue.isNotEmpty()) settlePump()
                }
            }
        }

        /**
         * Pro [CurlStyle.ROLL] se dokonceny obrat zaradi do [turnQueue] a [settlePump]
         * ho doanimuje - rychle tapy tak jedou plynule za sebou (puvodni vizual)
         * misto aby se zahazovaly nebo proskakovaly. Pro CLASSIC (a na hranici
         * kapitoly, kde se zadny ohyb nevykresluje) zustava okamzite `applyTurnResult`.
         */
        fun settleAndApply(result: PageTurnResult) {
            if (resolvedCurlStyle != CurlStyle.ROLL || result is PageTurnResult.ChapterBoundary) {
                applyTurnResult(result)
                return
            }
            turnQueue.addLast(
                when (result) {
                    is PageTurnResult.WithinChapter ->
                        if (result.newState.currentPageIndex > liveGroupIndex()) CurlQueuedTurn.NEXT
                        else CurlQueuedTurn.PREV
                    is PageTurnResult.Cancelled -> CurlQueuedTurn.SETTLE_BACK
                    is PageTurnResult.ChapterBoundary -> return  // unreachable - vetev vyse
                }
            )
            settlePump()
        }

        fun tryTurn(direction: TurnDirection) {
            if (scale <= 1f) {
                val live = PageCurlState(
                    currentPageIndex = liveGroupIndex(),
                    pageCount = latestGroups.size,
                    dragProgress = dragProgress,
                    rawDragProgress = rawDragProgress,
                )
                settleAndApply(live.onEdgeTap(direction))
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
                    referer = referer,
                )
            }
            // Klic `epoch` (ne `pages`) - append v nekonecnem cteni obsah vykreslene
            // skupiny nemeni (stranky se prilepi za konec), takze re-rasterizovat je
            // zbytecne; zmena kapitoly se chytne pres epoch+currentIndices.
            LaunchedEffect(currentIndices, epoch, translateMode, translatedPages, widthPx, heightPx, currentLoaded) {
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
                        referer = referer,
                    )
                }
            }
            LaunchedEffect(nextIndices, epoch, translateMode, translatedPages, widthPx, heightPx, nextLoaded) {
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
                        referer = referer,
                    )
                }
            }
            LaunchedEffect(prevIndices, epoch, translateMode, translatedPages, widthPx, heightPx, prevLoaded) {
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
                    // Abort rozjeteho obratu = zahodit pending+frontu, ne flushnout -
                    // pres stranku se ted zoomuje a preklopeni by se ztratilo pod gestem.
                    turnQueue.clear()
                    pendingSettleResult = null
                    settleJob?.cancel()
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
                                // Klíč `epoch` (ne `pages`/`groups.size`) - append v
                                // nekonecnem cteni meni `pages` a driv tu restartoval
                                // gesto -> prave bezici tap/drag se zrusil (hlasene
                                // "vypadavani" tapu/tahu). `pages`/`groups` se cte pres
                                // latest* (rememberUpdatedState), takze append se projevi
                                // bez restartu; skutecnou zmenu obsahu (novy epoch = novy
                                // MutableState delegat pro `currentSingleIndex`/`dragProgress`)
                                // restartuje klíč epoch sam - osiřelé delegáty z předchozí
                                // kapitoly tim nemuzou vzniknout (review nález č. 2).
                                .pointerInput(epoch, reverseLayout) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            // Preruseni beziciho dojeti (settle) - uzivatel
                                            // popadl stranku uprostred animace. Nejdriv
                                            // flushnout pending obrat (jinak by se jeho
                                            // stranka ztratila a tah pokracoval ze stare)
                                            // a zahodit frontu - prst prebira rizeni.
                                            flushPendingSettle()
                                            turnQueue.clear()
                                            settleJob?.cancel()
                                            val delta = (if (reverseLayout) -dragAmount.x else dragAmount.x) / widthPx
                                            val live = PageCurlState(
                                                currentPageIndex = liveGroupIndex(),
                                                pageCount = latestGroups.size,
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
                                                pageCount = latestGroups.size,
                                                dragProgress = dragProgress,
                                                rawDragProgress = rawDragProgress,
                                            )
                                            settleAndApply(live.onDragEnd())
                                        },
                                        onDragCancel = {
                                            // Fix Important 5 - gesture node muze byt zrusen
                                            // uprostred tahu (napr. prevzeti ukazatele jinym
                                            // gesture-nodem pri prechodu do pinch-zoomu) - bez
                                            // resetu by curl overlay zustal zamrzly.
                                            turnQueue.clear()
                                            pendingSettleResult = null
                                            settleJob?.cancel()
                                            dragProgress = 0f
                                            rawDragProgress = 0f
                                        },
                                    )
                                }
                        } else {
                            // Zoomovano (scale>1): curl-tah je vypnuty, jednoprsty
                            // tah tak muze rovnou panovat zvetsenou stranku - nemusi
                            // se panovat obema prsty.
                            Modifier.pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    panOffset += dragAmount
                                }
                            }
                        },
                    )
                    // Fix Important 3 - tap gesta (SHOW_PANEL / predchozi-dalsi kapitola /
                    // long-press sdileni) VZDY aktivni, nezavisle na zoomu - presne jako
                    // `MangaReader` (`ReaderPager.kt`), kde tenhle pointerInput blok neni
                    // vubec gatovany na `scale`.
                    // Klíč `epoch` (ne `pages`) - append v nekonecnem cteni driv restartoval
                    // detektor a sezral prave letici tap. `pages`/`groups` se cte pres
                    // latest*; `epoch` restart zaridi cerstve delegaty pri zmene obsahu.
                    .pointerInput(epoch, tapZonesEnabled, tapZoneGrid, reverseLayout) {
                        detectTapGestures(
                            onLongPress = {
                                val liveIndices = latestGroups.getOrElse(liveGroupIndex()) { listOf(0) }
                                sharePageUrl = latestPages.getOrElse(liveIndices[0]) { "" }
                                if (sharePageUrl.isNotEmpty()) showShareSheet = true
                            },
                            onTap = { offset ->
                                val action = tapZoneAction(offset, size, tapZonesEnabled, tapZoneGrid)
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
                        detectTwoFingerPinchZoom(
                            onGestureEnd = {
                                // Snap zbytkoveho zoomu pod ~10% zpet na 1f - pinch
                                // mohl skoncit na napr. 1.003 (vizualne 1x), ale
                                // `scale <= 1f` gaty (tah, tryTurn) by pak byly
                                // nastejno mrtve bez jakekoliv indicie proc.
                                if (scale < 1.1f) { scale = 1f; panOffset = Offset.Zero }
                            },
                        ) { zoomChange, panChange ->
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) panOffset += panChange else panOffset = Offset.Zero
                        }
                    },
            ) {
                val bitmap = currentBitmap
                if (resolvedCurlStyle == CurlStyle.ROLL) {
                    // Port karacken.curl (OpenGL) knihovny - viz GLPageCurlView. Rizeno stejnym
                    // `dragProgress`, jen dragProgress>0f = tazeni na DALSI stranku ("forward").
                    // Surface je mountnuty PERMANENTNE (stejne jako PageSurfaceView v originalnim
                    // PlayLikeCurl) - EGL init trva ~200-500 ms a mount az pri `dragProgress != 0f`
                    // znamenal, ze cela 300ms doanimace doběhla driv, nez GL vykreslil prvni frame
                    // = zadny efekt, jen skok stranky. V klidu renderer jen cisti na transparent
                    // (prosvita ziva stranka pod nim) a drzi textury pred-nahrane.
                    if (bitmap != null) {
                        com.haise.jiyu.ui.reader.glcurl.GLPageCurlView(
                            currentBitmap = bitmap,
                            prevBitmap = prevBitmap,
                            nextBitmap = nextBitmap,
                            forward = dragProgress > 0f,
                            progress = kotlin.math.abs(dragProgress),
                            // RTL (`reverseLayout`, bezne pro mangu): ohyb se zrcadli, aby
                            // odpovidal fyzicke strane tahu - "dalsi" se tam tahne doprava a
                            // stranka se musi loupout zleva, ne zprava jako v LTR.
                            mirrored = reverseLayout,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else if (bitmap != null && dragProgress != 0f) {
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
                        drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                    }
                }
            }
        }
    }
}

package com.haise.jiyu.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.haise.jiyu.util.findActivity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.translate.TranslatedBlock
import kotlinx.coroutines.launch

/**
 * Sestaví jednu "obrazovku" čtečky (kapitolu) - horní/spodní panel ([ReaderControls]),
 * pager ([MangaReader]/[WebtoonReader], viz [ReaderPager]/[WebtoonReader.kt]) a téma overlay.
 * Sám o sobě drží jen lokální UI stav, který nikam jinam nepatří (jas, viditelnost
 * glosáře) - viditelnost ovládacích prvků ([controlsVisible]) žije v [ReaderViewModel]
 * (auto-hide časovač), pinch-to-zoom žije v [MangaReader] (viz [ReaderPager]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderContent(
    pages: List<String>,
    initialPage: Int,
    currentPage: Int,
    translateMode: Boolean,
    translationProgress: TranslationProgress?,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    // Jen pro WebtoonReader - viz ReaderViewModel._translatedPagesByChapter.
    translatedPagesByChapter: Map<String, Map<Int, List<TranslatedBlock>>> = emptyMap(),
    batchTranslating: Boolean,
    batchProgress: TranslationProgress?,
    showOriginal: Boolean,
    reverseLayout: Boolean,
    // Efektivni webtoon mod VCETNE per-manga override z detailu titulu
    // (ReaderViewModel.isWebtoonMode) - ne surove settings.readingMode, jinak je
    // "Webtoon" volba u titulu mrtva (audit RD-1).
    webtoonMode: Boolean,
    chapterTitle: String,
    mangaTitle: String = "",
    onOpenManga: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    hasPrevChapter: Boolean = false,
    hasNextChapter: Boolean,
    controlsVisible: Boolean,
    onToggleControlsVisible: () -> Unit,
    onToggleTranslate: () -> Unit,
    onTranslateAll: () -> Unit,
    onCancelBatch: () -> Unit,
    onToggleShowOriginal: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onNavigatePrev: () -> Unit,
    onNavigateNext: () -> Unit,
    sourceLanguage: String,
    targetLanguage: String,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    tapZonesEnabled: Boolean,
    tapZoneGrid: TapZoneGrid = TapZoneGrid(),
    textScale: Float,
    doublePageSpread: Boolean,
    readerTheme: String = "dark",
    isOfflineChapter: Boolean = false,
    chapterProgress: Float = 0f,
    spreadPageIndices: Set<Int> = emptySet(),
    onSharePage: (String) -> Unit = {},
    onSleepTimerClick: () -> Unit = {},
    panelMode: Boolean = false,
    onTogglePanelMode: () -> Unit = {},
    oledMode: Boolean = false,
    incognitoMode: Boolean = false,
    onToggleIncognito: () -> Unit = {},
    onAdvancedSheetVisibilityChanged: (Boolean) -> Unit = {},
    /** Edge scrubber hlasi zacatek/konec scrub gesta - auto-hide controls nesmi
     *  schovat lišty uprostred tazeni (viz ReaderViewModel.onEdgeScrubActive). */
    onEdgeScrubActive: (Boolean) -> Unit = {},
    sessionElapsed: Long = 0L,
    webtoonScrollSpeed: Float = 1.0f,
    pageScale: String = "fit_width",
    jumpToPage: Int? = null,
    onJumpToPage: (Int) -> Unit = {},
    onJumpConsumed: () -> Unit = {},
    allChapters: List<ChapterEntity> = emptyList(),
    currentChapterId: String? = null,
    onJumpToChapter: (String) -> Unit = {},
    onResetChapter: () -> Unit = {},
    webtoonSegments: List<WebtoonSegment> = emptyList(),
    onNeedMoreWebtoonSegments: () -> Unit = {},
    onWebtoonVisibleChapterChanged: (chapterId: String, localIndex: Int, localOffset: Int) -> Unit = { _, _, _ -> },
    webtoonAppendingNextChapter: Boolean = false,
    autoNextChapter: Boolean = false,
    onAutoNextChapter: () -> Unit = {},
    cropBorders: Boolean = false,
    webtoonScrollOffset: Int = 0,
    volumeKeysNav: Boolean = true,
    readerOrientation: String = "free",
    onSetReaderOrientation: (String) -> Unit = {},
    glossary: List<GlossaryEntity> = emptyList(),
    onAddGlossaryEntry: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onRemoveGlossaryEntry: (GlossaryEntity) -> Unit = {},
    onToggleGlossaryProtectExact: (GlossaryEntity) -> Unit = {},
    chapterComments: List<com.haise.jiyu.source.comments.ChapterComment> = emptyList(),
    commentsLoading: Boolean = false,
    commentsSupported: Boolean = false,
    onShowComments: () -> Unit = {},
    flippedBubbles: Set<String> = emptySet(),
    // chapterId v callbacku - v nekonecnem webtoon scrollu muze bublina patrit
    // odscrollanemu segmentu jine kapitoly, nez je aktualni (audit RD-4/RD-10).
    onToggleBubbleFlip: (chapterId: String, pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _, _ -> },
    onEditBubble: (chapterId: String, pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit = { _, _, _, _, _, _ -> },
    onDeviceWarningText: String? = null,
    pageCurlEnabled: Boolean = false,
    curlStyle: String = com.haise.jiyu.settings.CurlStyleSetting.CLASSIC,
    // Viz RetryableAsyncImage.referer.
    referer: String? = null,
    /** "Nekonečné čtení" pro stránkované režimy (pager i curl) - seskládá
     *  [webtoonSegments] na jeden plochý proud stránek; reporty stránek jdou
     *  přes [onPagedFlatPageChanged] (plochý index -> chapterId+lokální index
     *  řeší ViewModel). Webtoon má vlastní segmentový kanál, sem se nemíchá. */
    infiniteScrollEnabled: Boolean = false,
    onPagedFlatPageChanged: (Int) -> Unit = {},
) {
    var showGlossarySheet by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }

    // Stav pro edge scrubber ve webtoon modu - LazyColumn je interni v WebtoonReaderu,
    // takze pozice/scrub se predava callbackem (reportuje flat index celeho proudu,
    // tedy pres vsechny napojene segmenty "Nekonecneho cteni").
    var webtoonFlatIndex by remember { mutableStateOf(0) }
    var webtoonItemCount by remember { mutableStateOf(0) }
    var webtoonScrubTarget by remember { mutableStateOf<Float?>(null) }
    val isWebtoon = webtoonMode

    // Přednačítání stránek řeší ReaderViewModel.prefetchPagesFrom (jedno místo, stejný
    // cache klíč včetně cropBorders). Druhá paralelní fronta tady stahovala stejné
    // stránky znovu - Coil běžící requesty nekoalescuje, takže se per-host fronta
    // plnila duplicitami a viditelná stránka na ně čekala.

    // Jas obrazovky; -1f = systémový výchozí (okno se nezmění dokud uživatel nepohne sliderem).
    // rememberSaveable - jinak by se rotace obrazovky (config change) vrátila na systémový jas.
    var brightness by rememberSaveable { mutableStateOf(-1f) }
    val view = LocalView.current
    LaunchedEffect(brightness) {
        if (brightness >= 0f) {
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = view.context.findActivity()?.window ?: return@onDispose
            window.attributes = window.attributes.apply { screenBrightness = -1f }
        }
    }

    val themeOverlay = if (oledMode) Color.Transparent else when (readerTheme) {
        "sepia" -> Color(0xFFB8860B).copy(alpha = 0.12f)
        "paper" -> Color(0xFFFFFAF0).copy(alpha = 0.06f)
        else    -> Color.Transparent
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val effectiveTranslateMode = translateMode && !showOriginal
        // Paged/curl ctecky ukazuji vzdy jen AKTUALNI kapitolu - bubble callbacky se
        // tu obali jejim currentChapterId a flip set se projektuje na
        // "pageIndex:bubbleIndex" klice te kapitoly (viz ReaderViewModel.flipKeyFor
        // / flippedKeysForChapter). Webtoon si chapterId resi per segment sam.
        val pagedChapterId = currentChapterId ?: ""
        // ── Nekonečné čtení ve stránkovaném režimu ─────────────────────────
        // Se zapnutym infiniteScroll paged ctecky nedostanou jen `pages` aktualni
        // kapitoly, ale seskladanou listu pres vsechny napojene segmenty (hlasi
        // pak plochy index, mapovani na kapitolu dela VM.onPagedFlatPageChanged).
        // `epoch` = id PRVNÍHO segmentu - appendy meni `pages`, ale epoch drzi
        // stabilni, takze vnitřní stav ctecek (pozice/zoom/drag) se neresetuje.
        val useInfinitePaged = infiniteScrollEnabled && !isWebtoon && webtoonSegments.isNotEmpty()
        val pagedEpoch: Any = if (useInfinitePaged) webtoonSegments.first().chapterId else pages
        val pagedPages = if (useInfinitePaged) {
            remember(webtoonSegments) { webtoonSegments.flatMap { it.pages } }
        } else pages
        // Plochy offset segmentu aktualni kapitoly - pro prepocty lokalni<->plochy
        // index (initialPage/currentPage jsou porad lokalni vuci currentChapter).
        val curSegStart = if (useInfinitePaged) {
            segmentStartFlatIndex(webtoonSegments, pagedChapterId).coerceAtLeast(0)
        } else 0
        val pagedInitialPage = if (useInfinitePaged) {
            (curSegStart + initialPage).coerceIn(0, (pagedPages.size - 1).coerceAtLeast(0))
        } else initialPage
        // Prekladove bloky klicovane plochym indexem - per-chapter mapa se
        // promitne pres offsety segmentu (stejny princip jako WebtoonReader,
        // jen bez "hranicnich" polozek - paged proud je plne bezskvy).
        val pagedTranslated = if (useInfinitePaged) {
            remember(translatedPagesByChapter, webtoonSegments) {
                flattenTranslatedPages(translatedPagesByChapter, webtoonSegments)
            }
        } else translatedPages
        // Flip klice "$page:$bubble" premapovane na plochy index - jinak by se
        // lokalni indexy opakujici se v kazde kapitole krizily mezi segmenty.
        val pagedFlipped = if (useInfinitePaged) {
            remember(flippedBubbles, webtoonSegments) {
                flattenFlippedKeys(flippedBubbles, webtoonSegments)
            }
        } else flippedKeysForChapter(flippedBubbles, pagedChapterId)
        // spreadPageIndices patri AKTUALNI kapitole (lokalni indexy) - promizi
        // na jeji segment; ostatni kapitoly detekci nemaji (bezi jen u stazenych).
        val pagedSpread = if (useInfinitePaged) {
            remember(spreadPageIndices, curSegStart) {
                spreadPageIndices.mapTo(LinkedHashSet()) { it + curSegStart }
            }
        } else spreadPageIndices
        val pagedOnPageChanged: (Int) -> Unit =
            if (useInfinitePaged) onPagedFlatPageChanged else onPageChanged
        // Plochy index stranky -> (chapterId, lokalni index) pro bubble callbacky.
        val pagedLocateChapter = { flatIdx: Int ->
            if (useInfinitePaged) {
                locatePagedLocal(webtoonSegments, flatIdx) ?: (pagedChapterId to flatIdx)
            } else pagedChapterId to flatIdx
        }
        // Horni lista / slider / scrubber ukazuji pozici v cele nekonecne knize,
        // ne jen v aktualni kapitole.
        val pagedPageCount = if (useInfinitePaged) pagedPages.size else pages.size
        val pagedCurrentPage = if (useInfinitePaged) {
            (curSegStart + currentPage).coerceIn(0, (pagedPageCount - 1).coerceAtLeast(0))
        } else currentPage
        if (isWebtoon) {
            // Prazdne webtoonSegments (volajici je jeste nepredava) = spadni zpatky na jeden
            // segment postaveny z `pages`/`currentChapterId`/`chapterTitle` - stejne chovani
            // jako pred zavedenim segmentu.
            val effectiveWebtoonSegments = webtoonSegments.ifEmpty {
                listOf(WebtoonSegment(currentChapterId ?: "", chapterTitle, pages))
            }
            WebtoonReader(
                segments = effectiveWebtoonSegments,
                initialPage = initialPage,
                initialScrollOffset = webtoonScrollOffset,
                onNeedMoreSegments = onNeedMoreWebtoonSegments,
                onVisibleChapterChanged = onWebtoonVisibleChapterChanged,
                translateMode = effectiveTranslateMode,
                translatedPagesByChapter = translatedPagesByChapter,
                textScale = textScale,
                tapZoneGrid = tapZoneGrid,
                tapZonesEnabled = tapZonesEnabled,
                onShowPanel = onToggleControlsVisible,
                onNavigatePrev = onNavigatePrev,
                onNavigateNext = onNavigateNext,
                scrollSpeedMultiplier = webtoonScrollSpeed,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
                isAppendingNextChapter = webtoonAppendingNextChapter,
                onScrollPositionChanged = { flatIdx, total ->
                    webtoonFlatIndex = flatIdx
                    webtoonItemCount = total
                },
                scrubToFraction = webtoonScrubTarget,
                onScrubConsumed = { webtoonScrubTarget = null },
                jumpToPage = jumpToPage,
                onJumpConsumed = onJumpConsumed,
                referer = referer,
            )
        } else if (pageCurlEnabled) {
            MangaPageCurlReader(
                pages = pagedPages,
                initialPage = pagedInitialPage,
                translateMode = effectiveTranslateMode,
                translatedPages = pagedTranslated,
                reverseLayout = reverseLayout,
                doublePageSpread = doublePageSpread,
                spreadPageIndices = pagedSpread,
                textScale = textScale,
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
                onPageChanged = pagedOnPageChanged,
                onShowPanel = onToggleControlsVisible,
                onNavigatePrevChapter = onNavigatePrev,
                onNavigateNextChapter = onNavigateNext,
                onSharePage = onSharePage,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpConsumed = onJumpConsumed,
                autoNextChapter = autoNextChapter,
                // V nekonecnem cteni se "auto-advance" nechova jako tvrdy skok na dalsi
                // kapitolu (ten by zahodil seskladany proud segmentu) - jen se zkusi
                // dolnatahnout; pokud uz append leti / dalsi kapitola neni, je to no-op.
                onAutoNextChapter = if (useInfinitePaged) ({ onNeedMoreWebtoonSegments() }) else onAutoNextChapter,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
                curlStyle = curlStyle,
                flippedBubbles = pagedFlipped,
                onToggleBubbleFlip = { pi, bi -> val (cid, li) = pagedLocateChapter(pi); onToggleBubbleFlip(cid, li, bi) },
                onEditBubble = { pi, ot, ct, x, y -> val (cid, li) = pagedLocateChapter(pi); onEditBubble(cid, li, ot, ct, x, y) },
                referer = referer,
                contentEpoch = pagedEpoch,
                // Nekonecne cteni: tah za konec seskladaneho proudu dolnatahne dalsi
                // kapitolu misto tvrde navigace (ta by resetovala segmenty na jednu).
                onNeedMorePages = if (useInfinitePaged) ({ onNeedMoreWebtoonSegments() }) else null,
            )
        } else {
            MangaReader(
                pages = pagedPages,
                initialPage = pagedInitialPage,
                translateMode = effectiveTranslateMode,
                translatedPages = pagedTranslated,
                reverseLayout = reverseLayout,
                doublePageSpread = doublePageSpread,
                spreadPageIndices = pagedSpread,
                textScale = textScale,
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
                onPageChanged = pagedOnPageChanged,
                onShowPanel = onToggleControlsVisible,
                onNavigatePrevChapter = onNavigatePrev,
                onNavigateNextChapter = onNavigateNext,
                onSharePage = onSharePage,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpConsumed = onJumpConsumed,
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = if (useInfinitePaged) ({ onNeedMoreWebtoonSegments() }) else onAutoNextChapter,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
                flippedBubbles = pagedFlipped,
                onToggleBubbleFlip = { pi, bi -> val (cid, li) = pagedLocateChapter(pi); onToggleBubbleFlip(cid, li, bi) },
                referer = referer,
                contentEpoch = pagedEpoch,
            )
        }

        // Téma čtečky — barevný overlay přes stránky
        if (themeOverlay != Color.Transparent) {
            Box(modifier = Modifier.fillMaxSize().background(themeOverlay))
        }

        // ── Edge scrubber (levy okraj) ────────────────────────────────────────
        // Viditelny jen spolu s controls (tap na screen) - stejny fade jako
        // horni/dolni lista, jinak jen mlel nad strankou. Progrese a cil scrubu
        // se lisi podle rezimu: paged ctecky (pager i curl) jedou pres page index
        // a existujici jumpToPage kanal, webtoon pres flat index LazyColumn.
        // Zobrazuje se jen kdyz je co posouvat - jedna stranka nema co scrubovat.
        // U nekonecneho paged cteni "kapitola" znamena cely seskladany proud.
        val scrubTotal = if (isWebtoon) webtoonItemCount else pagedPageCount
        if (scrubTotal > 1) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                ReaderEdgeScrubber(
                    progress = if (isWebtoon) {
                        webtoonFlatIndex / (scrubTotal - 1).toFloat()
                    } else {
                        pagedCurrentPage / (scrubTotal - 1).toFloat()
                    },
                    onScrub = { fraction ->
                        if (isWebtoon) webtoonScrubTarget = fraction
                        else onJumpToPage((fraction * (scrubTotal - 1)).toInt())
                    },
                    label = { fraction ->
                        "${(fraction * (scrubTotal - 1)).toInt() + 1} / $scrubTotal"
                    },
                    onScrubActiveChanged = onEdgeScrubActive,
                )
            }
        }

        // ── Overlay ovládání ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ReaderTopBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    currentPage = pagedCurrentPage,
                    pageCount = if (isWebtoon) webtoonItemCount else pagedPageCount,
                    isOfflineChapter = isOfflineChapter,
                    sessionElapsed = sessionElapsed,
                    chapterProgress = chapterProgress,
                    allChapters = allChapters,
                    currentChapterId = currentChapterId,
                    onOpenManga = onOpenManga,
                    onJumpToChapter = onJumpToChapter,
                    onResetChapter = onResetChapter,
                )

                ReaderBottomPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    onSourceLanguageChange = onSourceLanguageChange,
                    onTargetLanguageChange = onTargetLanguageChange,
                    onShowGlossary = { showGlossarySheet = true },
                    onShowComments = { showCommentsSheet = true; onShowComments() },
                    commentsSupported = commentsSupported,
                    pageCount = if (isWebtoon) webtoonItemCount else pagedPageCount,
                    currentPage = pagedCurrentPage,
                    onJumpToPage = onJumpToPage,
                    brightness = brightness,
                    onBrightnessChange = { brightness = it },
                    readerOrientation = readerOrientation,
                    onSetReaderOrientation = onSetReaderOrientation,
                    translateMode = translateMode,
                    isTranslating = translationProgress != null,
                    onToggleTranslate = onToggleTranslate,
                    batchTranslating = batchTranslating,
                    batchProgress = batchProgress,
                    showOriginal = showOriginal,
                    onToggleShowOriginal = onToggleShowOriginal,
                    onTranslateAll = onTranslateAll,
                    onCancelBatch = onCancelBatch,
                    translationProgress = translationProgress,
                    hasPrevChapter = hasPrevChapter,
                    onNavigatePrev = onNavigatePrev,
                    hasNextChapter = hasNextChapter,
                    onNavigateNext = onNavigateNext,
                    onNavigateHome = onNavigateHome,
                    panelMode = panelMode,
                    onTogglePanelMode = onTogglePanelMode,
                    onSleepTimerClick = onSleepTimerClick,
                    incognitoMode = incognitoMode,
                    onToggleIncognito = onToggleIncognito,
                    onAdvancedSheetVisibilityChanged = onAdvancedSheetVisibilityChanged,
                )
            }
        }

        // Nekonecne cteni (paged) - indikace stahovani dalsi kapitoly. Pager/curl
        // nema "footer" polozku jako LazyColumn ve webtoonu, takze jen maly chip
        // u dolniho okraje; zmizi sam, az se segment prilepi (flag klesne).
        if (useInfinitePaged && webtoonAppendingNextChapter) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.webtoon_loading_next_chapter),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }

        if (onDeviceWarningText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF6D28D9).copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = onDeviceWarningText,
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showGlossarySheet) {
        GlossaryBottomSheet(
            glossary = glossary,
            targetLanguage = targetLanguage,
            onAdd = onAddGlossaryEntry,
            onRemove = onRemoveGlossaryEntry,
            onToggleProtectExact = onToggleGlossaryProtectExact,
            onDismiss = { showGlossarySheet = false },
        )
    }

    if (showCommentsSheet) {
        ChapterCommentsBottomSheet(
            comments = chapterComments,
            loading = commentsLoading,
            onDismiss = { showCommentsSheet = false },
        )
    }

}

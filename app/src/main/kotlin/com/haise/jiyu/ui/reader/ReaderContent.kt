package com.haise.jiyu.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.settings.ReadingMode
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
    batchTranslating: Boolean,
    batchProgress: TranslationProgress?,
    showOriginal: Boolean,
    reverseLayout: Boolean,
    readingMode: String,
    chapterTitle: String,
    mangaTitle: String = "",
    onOpenManga: () -> Unit = {},
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
    autoNextChapter: Boolean = false,
    onAutoNextChapter: () -> Unit = {},
    cropBorders: Boolean = false,
    webtoonScrollOffset: Int = 0,
    onWebtoonScrollOffset: (Int) -> Unit = {},
    volumeKeysNav: Boolean = true,
    readerOrientation: String = "free",
    onSetReaderOrientation: (String) -> Unit = {},
    glossary: List<GlossaryEntity> = emptyList(),
    onAddGlossaryEntry: (String, String) -> Unit = { _, _ -> },
    onRemoveGlossaryEntry: (GlossaryEntity) -> Unit = {},
    flippedBubbles: Set<String> = emptySet(),
    onToggleBubbleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
    onDeviceWarningText: String? = null,
    pageCurlEnabled: Boolean = false,
    curlStyle: String = com.haise.jiyu.settings.CurlStyleSetting.CLASSIC,
) {
    var showGlossarySheet by remember { mutableStateOf(false) }

    // Přednačtení stránek do Coil cache
    val preloadContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(currentPage, pages) {
        if (pages.isEmpty()) return@LaunchedEffect
        (currentPage + 1..currentPage + 3).mapNotNull { pages.getOrNull(it) }
            .filter { !it.startsWith("file://") }
            .forEach { url ->
                val req = coil.request.ImageRequest.Builder(preloadContext).data(url).build()
                coil.Coil.imageLoader(preloadContext).enqueue(req)
            }
    }

    // Jas obrazovky; -1f = systémový výchozí (okno se nezmění dokud uživatel nepohne sliderem).
    // rememberSaveable - jinak by se rotace obrazovky (config change) vrátila na systémový jas.
    var brightness by rememberSaveable { mutableStateOf(-1f) }
    val view = LocalView.current
    LaunchedEffect(brightness) {
        if (brightness >= 0f) {
            val window = (view.context as android.app.Activity).window
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as android.app.Activity).window
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
        if (readingMode == ReadingMode.WEBTOON) {
            WebtoonReader(
                pages = pages,
                initialPage = initialPage,
                initialScrollOffset = webtoonScrollOffset,
                onScrollOffsetChanged = onWebtoonScrollOffset,
                translateMode = effectiveTranslateMode,
                translatedPages = translatedPages,
                textScale = textScale,
                onPageChanged = onPageChanged,
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
            )
        } else if (pageCurlEnabled) {
            MangaPageCurlReader(
                pages = pages,
                initialPage = initialPage,
                translateMode = effectiveTranslateMode,
                translatedPages = translatedPages,
                reverseLayout = reverseLayout,
                doublePageSpread = doublePageSpread,
                spreadPageIndices = spreadPageIndices,
                textScale = textScale,
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
                onPageChanged = onPageChanged,
                onShowPanel = onToggleControlsVisible,
                onNavigatePrevChapter = onNavigatePrev,
                onNavigateNextChapter = onNavigateNext,
                onSharePage = onSharePage,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpConsumed = onJumpConsumed,
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = onAutoNextChapter,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
                curlStyle = curlStyle,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
                onEditBubble = onEditBubble,
            )
        } else {
            MangaReader(
                pages = pages,
                initialPage = initialPage,
                translateMode = effectiveTranslateMode,
                translatedPages = translatedPages,
                reverseLayout = reverseLayout,
                doublePageSpread = doublePageSpread,
                spreadPageIndices = spreadPageIndices,
                textScale = textScale,
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
                onPageChanged = onPageChanged,
                onShowPanel = onToggleControlsVisible,
                onNavigatePrevChapter = onNavigatePrev,
                onNavigateNextChapter = onNavigateNext,
                onSharePage = onSharePage,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpConsumed = onJumpConsumed,
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = onAutoNextChapter,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = onToggleBubbleFlip,
            )
        }

        // Téma čtečky — barevný overlay přes stránky
        if (themeOverlay != Color.Transparent) {
            Box(modifier = Modifier.fillMaxSize().background(themeOverlay))
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
                    currentPage = currentPage,
                    pageCount = pages.size,
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
                    pageCount = pages.size,
                    currentPage = currentPage,
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
                    panelMode = panelMode,
                    onTogglePanelMode = onTogglePanelMode,
                    onSleepTimerClick = onSleepTimerClick,
                    incognitoMode = incognitoMode,
                    onToggleIncognito = onToggleIncognito,
                    onAdvancedSheetVisibilityChanged = onAdvancedSheetVisibilityChanged,
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
            onDismiss = { showGlossarySheet = false },
        )
    }

}

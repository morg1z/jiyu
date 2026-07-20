package com.haise.jiyu.ui.reader

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.haise.jiyu.R
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.translate.PositionedTranslationBlock
import com.haise.jiyu.translate.TranslatedBlock
import com.haise.jiyu.translate.layoutTranslationBlocks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Offset není nativně Bundle-savovatelný, takže pro rememberSaveable potřebuje vlastní Saver. */
private val OffsetSaver = Saver<Offset, List<Float>>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel = hiltViewModel()) {
    val pages               by viewModel.pages.collectAsState()
    val loading             by viewModel.loading.collectAsState()
    val translateMode       by viewModel.translateMode.collectAsState()
    val translationProgress by viewModel.translationProgress.collectAsState()
    val translatedPages     by viewModel.translatedPages.collectAsState()
    val batchTranslating    by viewModel.batchTranslating.collectAsState()
    val batchProgress       by viewModel.batchProgress.collectAsState()
    val showOriginal        by viewModel.showOriginal.collectAsState()
    val reverseLayout       by viewModel.reverseLayout.collectAsState()
    val readingMode         by viewModel.readingMode.collectAsState()
    val initialPage         by viewModel.initialPage.collectAsState()
    val currentPage         by viewModel.currentPage.collectAsState()
    val hasPrevChapter      by viewModel.hasPrevChapter.collectAsState()
    val hasNextChapter      by viewModel.hasNextChapter.collectAsState()
    val chapterTitle        by viewModel.chapterTitle.collectAsState()
    val sourceLanguage      by viewModel.sourceLanguage.collectAsState()
    val targetLanguage      by viewModel.targetLanguage.collectAsState()
    val tapZonesEnabled     by viewModel.tapZonesEnabled.collectAsState()
    val readerTextScale     by viewModel.readerTextScale.collectAsState()
    val doublePageSpread    by viewModel.doublePageSpread.collectAsState()
    val translationError    by viewModel.translationError.collectAsState()
    val fullscreenEnabled   by viewModel.fullscreenEnabled.collectAsState()
    val readerTheme         by viewModel.readerTheme.collectAsState()
    val isOfflineChapter    by viewModel.isOfflineChapter.collectAsState()
    val chapterProgress     by viewModel.chapterProgress.collectAsState()
    val spreadPageIndices   by viewModel.spreadPageIndices.collectAsState()
    val sleepTimerRemaining  by viewModel.sleepTimerRemaining.collectAsState()
    val panelMode            by viewModel.panelMode.collectAsState()
    val oledMode             by viewModel.oledMode.collectAsState()
    val incognitoMode        by viewModel.incognitoMode.collectAsState()
    val sessionElapsed       by viewModel.sessionElapsed.collectAsState()
    val tapZoneGrid          by viewModel.tapZoneGrid.collectAsState()
    val webtoonScrollSpeed   by viewModel.webtoonScrollSpeed.collectAsState()
    val isNovelSource        by viewModel.isNovelSource.collectAsState()
    val novelText            by viewModel.novelText.collectAsState()
    val novelTranslateMode   by viewModel.novelTranslateMode.collectAsState()
    val novelTranslatedText  by viewModel.novelTranslatedText.collectAsState()
    val novelTranslating     by viewModel.novelTranslating.collectAsState()
    val glossary             by viewModel.glossary.collectAsState()
    val pageScale            by viewModel.pageScale.collectAsState()
    val jumpToPage           by viewModel.jumpToPage.collectAsState()
    val allChapters          by viewModel.allChaptersFlow.collectAsState()
    val autoNextChapter      by viewModel.autoNextChapter.collectAsState()
    val cropBorders          by viewModel.cropBorders.collectAsState()
    val webtoonScrollOffset  by viewModel.webtoonScrollOffset.collectAsState()
    val volumeKeysNav        by viewModel.volumeKeysNav.collectAsState()
    val keepScreenOn         by viewModel.keepScreenOn.collectAsState()
    val readerOrientation    by viewModel.readerOrientation.collectAsState()

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    val activity = LocalView.current.context as Activity

    // Sleep timer dialog
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text(stringResource(R.string.reader_sleep_timer_title), color = Color.White) },
            text = {
                Column {
                    Text(stringResource(R.string.reader_sleep_timer_close_after), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        15 to stringResource(R.string.reader_sleep_timer_15min),
                        30 to stringResource(R.string.reader_sleep_timer_30min),
                        45 to stringResource(R.string.reader_sleep_timer_45min),
                        60 to stringResource(R.string.reader_sleep_timer_1h),
                    ).forEach { (min, label) ->
                        TextButton(onClick = {
                            viewModel.startSleepTimer(min) { activity.finish() }
                            showSleepTimerDialog = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(label, color = Color.White) }
                    }
                    if (viewModel.sleepTimerRemaining.value != null) {
                        TextButton(onClick = { viewModel.cancelSleepTimer(); showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.reader_sleep_timer_cancel), color = Color(0xFFEF9A9A))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimerDialog = false }) { Text(stringResource(R.string.common_close), color = Color(0xFFB0BEC5)) } },
            containerColor = Color(0xFF1A1B35),
        )
    }

    // Fullscreen immersive (conditionally)
    val view = LocalView.current
    DisposableEffect(fullscreenEnabled) {
        val ctrl = WindowCompat.getInsetsController((view.context as Activity).window, view)
        if (fullscreenEnabled) {
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { ctrl.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(keepScreenOn) {
        val window = (view.context as Activity).window
        if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(readerOrientation) {
        val act = view.context as Activity
        act.requestedOrientation = when (readerOrientation) {
            "portrait"  -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else        -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    LaunchedEffect(translationError) {
        if (translationError != null) {
            delay(4_000L)
            viewModel.clearTranslationError()
        }
    }

    val bgColor = if (oledMode) Color.Black else when (readerTheme) {
        "sepia" -> Color(0xFF1A0E05)
        "paper" -> Color(0xFF1A1510)
        else    -> Color.Black
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val shareChooserTitle = stringResource(R.string.reader_share_page_chooser)
    val incognitoBadgeText = stringResource(R.string.reader_incognito_badge)

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading      -> JiyuLoadingIndicator()
            isNovelSource -> NovelContent(
                text = novelText,
                chapterTitle = chapterTitle,
                hasPrev = hasPrevChapter,
                hasNext = hasNextChapter,
                onPrev = { viewModel.navigatePrev() },
                onNext = { viewModel.navigateNext() },
                translateMode = novelTranslateMode,
                translatedText = novelTranslatedText,
                translating = novelTranslating,
                onToggleTranslate = { viewModel.toggleNovelTranslate() },
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                onSourceLanguageChange = { viewModel.setSourceLanguage(it) },
                onTargetLanguageChange = { viewModel.setTargetLanguage(it) },
                glossary = glossary,
                onAddGlossaryEntry = { source, target -> viewModel.addGlossaryEntry(source, target) },
                onRemoveGlossaryEntry = { viewModel.removeGlossaryEntry(it) },
            )
            pages.isEmpty() -> Text(stringResource(R.string.reader_chapter_load_failed), color = Color.White)
            else -> ReaderContent(
                pages = pages,
                initialPage = initialPage,
                currentPage = currentPage,
                translateMode = translateMode,
                translationProgress = translationProgress,
                translatedPages = translatedPages,
                batchTranslating = batchTranslating,
                batchProgress = batchProgress,
                showOriginal = showOriginal,
                reverseLayout = reverseLayout,
                readingMode = readingMode,
                chapterTitle = chapterTitle,
                hasPrevChapter = hasPrevChapter,
                hasNextChapter = hasNextChapter,
                onToggleTranslate = { viewModel.toggleTranslate() },
                onTranslateAll = { viewModel.translateAllPages() },
                onCancelBatch = { viewModel.cancelBatchTranslation() },
                onToggleShowOriginal = { viewModel.toggleShowOriginal() },
                onPageChanged = { viewModel.onPageChanged(it) },
                onNavigatePrev = { viewModel.navigatePrev() },
                onNavigateNext = { viewModel.navigateNext() },
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                onSourceLanguageChange = { viewModel.setSourceLanguage(it) },
                onTargetLanguageChange = { viewModel.setTargetLanguage(it) },
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
                textScale = readerTextScale,
                doublePageSpread = doublePageSpread,
                readerTheme = readerTheme,
                isOfflineChapter = isOfflineChapter,
                chapterProgress = chapterProgress,
                spreadPageIndices = spreadPageIndices,
                onSharePage = { pageUrl ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, pageUrl)
                    }
                    context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                },
                onSleepTimerClick = { showSleepTimerDialog = true },
                panelMode = panelMode,
                onTogglePanelMode = { viewModel.togglePanelMode() },
                oledMode = oledMode,
                incognitoMode = incognitoMode,
                onToggleIncognito = { viewModel.toggleIncognito() },
                sessionElapsed = sessionElapsed,
                webtoonScrollSpeed = webtoonScrollSpeed,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpToPage = { viewModel.jumpToPage(it) },
                onJumpConsumed = { viewModel.clearJump() },
                allChapters = allChapters,
                onJumpToChapter = { viewModel.jumpToChapter(it) },
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = { viewModel.navigateNext() },
                cropBorders = cropBorders,
                webtoonScrollOffset = webtoonScrollOffset,
                onWebtoonScrollOffset = { viewModel.saveWebtoonScrollOffset(it) },
                volumeKeysNav = volumeKeysNav,
                readerOrientation = readerOrientation,
                onSetReaderOrientation = { viewModel.setReaderOrientation(it) },
                glossary = glossary,
                onAddGlossaryEntry = { source, target -> viewModel.addGlossaryEntry(source, target) },
                onRemoveGlossaryEntry = { viewModel.removeGlossaryEntry(it) },
            )
        }

        // Incognito badge
        if (incognitoMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF6D28D9).copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    incognitoBadgeText,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Sleep timer badge
        if (sleepTimerRemaining != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF4A1580).copy(alpha = 0.85f))
                    .clickable { showSleepTimerDialog = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                val rem = sleepTimerRemaining!!
                Text(
                    "💤 ${rem / 60}:${(rem % 60).toString().padStart(2, '0')}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        AnimatedVisibility(
            visible = translationError != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFB71C1C).copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(translationError.orEmpty(), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NovelContent(
    text: String,
    chapterTitle: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    translateMode: Boolean = false,
    translatedText: String? = null,
    translating: Boolean = false,
    onToggleTranslate: () -> Unit = {},
    sourceLanguage: String = "Auto",
    targetLanguage: String = "Czech",
    onSourceLanguageChange: (String) -> Unit = {},
    onTargetLanguageChange: (String) -> Unit = {},
    glossary: List<com.haise.jiyu.data.db.entity.GlossaryEntity> = emptyList(),
    onAddGlossaryEntry: (String, String) -> Unit = { _, _ -> },
    onRemoveGlossaryEntry: (com.haise.jiyu.data.db.entity.GlossaryEntity) -> Unit = {},
) {
    var fontSize by remember { mutableStateOf(16f) }
    var lineSpacing by remember { mutableStateOf(1.6f) }
    var bgColorIndex by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showLangSettings by remember { mutableStateOf(false) }
    var showGlossarySheet by remember { mutableStateOf(false) }

    val bgOptions = listOf(
        Color(0xFF0A0A14) to Color(0xFFE8E8E8),
        Color(0xFF1A110A) to Color(0xFFE8D8C0),
        Color(0xFFF5F0E8) to Color(0xFF1A1A1A),
        Color.White to Color.Black,
    )
    val (bgColor, textColor) = bgOptions[bgColorIndex.coerceIn(0, bgOptions.lastIndex)]
    val displayText = if (translateMode && translatedText != null) translatedText else text
    val paragraphs = remember(displayText) { displayText.split("\n").filter { it.isNotBlank() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        androidx.compose.material3.TopAppBar(
            title = { Text(chapterTitle, color = Color(0xFFE8E8E8), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            actions = {
                IconButton(onClick = { showLangSettings = !showLangSettings }) {
                    if (translating) {
                        JiyuLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            TablerIcons.Language,
                            stringResource(R.string.reader_translate_chapter_desc),
                            tint = if (translateMode) Color(0xFF8B5CF6) else Color(0xFFB0BEC5),
                        )
                    }
                }
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(TablerIcons.Sun, stringResource(R.string.settings_title), tint = Color(0xFFB0BEC5))
                }
            },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D1A)),
        )

        AnimatedVisibility(visible = showLangSettings, enter = slideInVertically(), exit = slideOutVertically()) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF1A1B35)),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        var showSourceMenu by remember { mutableStateOf(false) }
                        var showTargetMenu by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                sourceLanguage,
                                color = Color(0xFF4FC3F7),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showSourceMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }, modifier = Modifier.background(Color(0xFF1A2340))) {
                                com.haise.jiyu.source.LanguageMap.displayNames.forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang, color = Color.White, fontSize = 13.sp) }, onClick = { onSourceLanguageChange(lang); showSourceMenu = false })
                                }
                            }
                        }
                        Text(" → ", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        Box {
                            Text(
                                targetLanguage,
                                color = Color(0xFF8B5CF6),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showTargetMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = showTargetMenu, onDismissRequest = { showTargetMenu = false }, modifier = Modifier.background(Color(0xFF1A2340))) {
                                com.haise.jiyu.source.LanguageMap.displayNames.filter { it != "Auto" }.forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang, color = Color.White, fontSize = 13.sp) }, onClick = { onTargetLanguageChange(lang); showTargetMenu = false })
                                }
                            }
                        }
                        TextButton(onClick = { showGlossarySheet = true }) {
                            Text(stringResource(R.string.reader_glossary_button), color = Color(0xFF8B5CF6))
                        }
                        TextButton(onClick = { onToggleTranslate(); showLangSettings = false }) {
                            Text(stringResource(if (translateMode) R.string.reader_original_toggle else R.string.reader_translate_toggle), color = Color(0xFF34D1BF))
                        }
                    }
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

        AnimatedVisibility(visible = showSettings, enter = slideInVertically(), exit = slideOutVertically()) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF1A1B35)),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(stringResource(R.string.reader_font_size_label, fontSize.toInt()), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { if (fontSize > 10f) fontSize -= 1f }, modifier = Modifier.size(36.dp)) {
                                Text(stringResource(R.string.reader_font_decrease), color = Color(0xFFE8E8E8), fontSize = 13.sp)
                            }
                            IconButton(onClick = { if (fontSize < 30f) fontSize += 1f }, modifier = Modifier.size(36.dp)) {
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
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = if (bgColorIndex == i) Color(0xFF8B5CF6) else Color(0xFFB0BEC5),
                                ),
                            ) { Text(label, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(paragraphs) { paragraph: String ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
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
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
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
    sessionElapsed: Long = 0L,
    webtoonScrollSpeed: Float = 1.0f,
    pageScale: String = "fit_width",
    jumpToPage: Int? = null,
    onJumpToPage: (Int) -> Unit = {},
    onJumpConsumed: () -> Unit = {},
    allChapters: List<com.haise.jiyu.data.db.entity.ChapterEntity> = emptyList(),
    onJumpToChapter: (String) -> Unit = {},
    autoNextChapter: Boolean = false,
    onAutoNextChapter: () -> Unit = {},
    cropBorders: Boolean = false,
    webtoonScrollOffset: Int = 0,
    onWebtoonScrollOffset: (Int) -> Unit = {},
    volumeKeysNav: Boolean = true,
    readerOrientation: String = "free",
    onSetReaderOrientation: (String) -> Unit = {},
    glossary: List<com.haise.jiyu.data.db.entity.GlossaryEntity> = emptyList(),
    onAddGlossaryEntry: (String, String) -> Unit = { _, _ -> },
    onRemoveGlossaryEntry: (com.haise.jiyu.data.db.entity.GlossaryEntity) -> Unit = {},
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showGlossarySheet by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) { delay(3_000L); controlsVisible = false }
    }

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
            val window = (view.context as Activity).window
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as Activity).window
            window.attributes = window.attributes.apply { screenBrightness = -1f }
        }
    }

    // Pinch-to-zoom stav — sdílený přes celý reader, resetuje se při změně stránky.
    // rememberSaveable, aby otočení obrazovky (config change) nezahodilo rozostřený zoom.
    var scale by rememberSaveable { mutableStateOf(1f) }
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

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
                onShowPanel = { controlsVisible = !controlsVisible },
                onNavigatePrev = onNavigatePrev,
                onNavigateNext = onNavigateNext,
                scrollSpeedMultiplier = webtoonScrollSpeed,
                cropBorders = cropBorders,
                volumeKeysNav = volumeKeysNav,
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
                scale = scale,
                panOffset = panOffset,
                onScaleChange = { scale = it },
                onPanChange = { panOffset = it },
                onPageChanged = { page ->
                    scale = 1f; panOffset = Offset.Zero
                    onPageChanged(page)
                },
                onShowPanel = { controlsVisible = !controlsVisible },
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
                // Top lišta
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Předchozí kapitola
                        IconButton(
                            onClick = onNavigatePrev,
                            enabled = hasPrevChapter,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                TablerIcons.ArrowBack,
                                contentDescription = stringResource(R.string.reader_prev_chapter_desc),
                                tint = if (hasPrevChapter) Color.White else Color.White.copy(alpha = 0.25f),
                            )
                        }

                        // Název + stránka
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chapterTitle,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (isOfflineChapter) {
                                    Icon(
                                        TablerIcons.WifiOff,
                                        contentDescription = stringResource(R.string.reader_offline_desc),
                                        tint = Color(0xFF4FC3F7),
                                        modifier = Modifier.size(13.dp).padding(start = 4.dp),
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "${currentPage + 1} / ${pages.size}",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                )
                                val sessionMinutes = sessionElapsed / 60000
                                val sessionSeconds = (sessionElapsed % 60000) / 1000
                                if (sessionMinutes > 0) {
                                    Text(
                                        text = "· ${sessionMinutes}:${sessionSeconds.toString().padStart(2, '0')}",
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }

                        // Panel mode toggle (#38)
                        IconButton(onClick = onTogglePanelMode, modifier = Modifier.size(40.dp)) {
                            Icon(
                                TablerIcons.LayoutRows,
                                contentDescription = stringResource(R.string.reader_panel_mode_desc),
                                tint = if (panelMode) Color(0xFFCE93D8) else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Sleep timer (#42)
                        IconButton(onClick = onSleepTimerClick, modifier = Modifier.size(40.dp)) {
                            Icon(
                                TablerIcons.Moon,
                                contentDescription = stringResource(R.string.reader_sleep_timer_title),
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Chapter picker
                        if (allChapters.isNotEmpty()) {
                            var showChapterSheet by remember { mutableStateOf(false) }
                            IconButton(onClick = { showChapterSheet = true }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    TablerIcons.ListCheck,
                                    contentDescription = stringResource(R.string.reader_pick_chapter_desc),
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (showChapterSheet) {
                                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                ModalBottomSheet(
                                    onDismissRequest = { showChapterSheet = false },
                                    sheetState = sheetState,
                                    containerColor = Color(0xFF111B35),
                                ) {
                                    Text(
                                        text = stringResource(R.string.reader_chapters_sheet_title, allChapters.size),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    )
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(bottom = 32.dp),
                                    ) {
                                        items(allChapters, key = { it.id }) { chapter ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onJumpToChapter(chapter.id); showChapterSheet = false }
                                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = chapter.name,
                                                        color = if (chapter.read) Color.White.copy(alpha = 0.45f) else Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                if (chapter.read) {
                                                    Icon(
                                                        TablerIcons.Check,
                                                        contentDescription = null,
                                                        tint = Color(0xFF8B5CF6).copy(alpha = 0.6f),
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                        }
                                    }
                                }
                            }
                        }

                        // Incognito mode
                        IconButton(onClick = onToggleIncognito, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (incognitoMode) TablerIcons.EyeOff else TablerIcons.Eye,
                                contentDescription = stringResource(if (incognitoMode) R.string.reader_incognito_off_desc else R.string.reader_incognito_on_desc),
                                tint = if (incognitoMode) Color(0xFFCE93D8) else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Překlad
                        val isTranslating = translationProgress != null
                        IconButton(onClick = onToggleTranslate, modifier = Modifier.size(40.dp)) {
                            Icon(
                                TablerIcons.Language,
                                contentDescription = stringResource(when {
                                    isTranslating -> R.string.reader_stop_translation_desc
                                    translateMode -> R.string.reader_hide_translation_desc
                                    else          -> R.string.reader_translate_chapter_action_desc
                                }),
                                tint = when {
                                    isTranslating -> Color(0xFFFFB74D)
                                    translateMode -> Color(0xFF4FC3F7)
                                    else          -> Color.White
                                },
                            )
                        }

                        // Další kapitola
                        IconButton(
                            onClick = onNavigateNext,
                            enabled = hasNextChapter,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                TablerIcons.ArrowRight,
                                contentDescription = stringResource(R.string.reader_next_chapter_desc),
                                tint = if (hasNextChapter) Color.White else Color.White.copy(alpha = 0.25f),
                            )
                        }
                    }
                    // Postup v rámci manga (počet kapitol)
                    if (chapterProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { chapterProgress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Color(0xFF8B5CF6).copy(alpha = 0.6f),
                            trackColor = Color.Transparent,
                        )
                    }
                }

                // Brightness + language picker + translation progress
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    // Výběr zdrojového a cílového jazyka překladu
                    var showSourceMenu by remember { mutableStateOf(false) }
                    var showTargetMenu by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.reader_translation_label), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Box {
                            Text(
                                text = sourceLanguage,
                                color = Color(0xFF4FC3F7),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showSourceMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            DropdownMenu(
                                expanded = showSourceMenu,
                                onDismissRequest = { showSourceMenu = false },
                                modifier = Modifier.background(Color(0xFF1A2340)),
                            ) {
                                com.haise.jiyu.source.LanguageMap.displayNames.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang, color = if (lang == sourceLanguage) Color(0xFF4FC3F7) else Color.White, fontSize = 13.sp) },
                                        onClick = { onSourceLanguageChange(lang); showSourceMenu = false },
                                    )
                                }
                            }
                        }
                        Text(" → ", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        Box {
                            Text(
                                text = targetLanguage,
                                color = Color(0xFF81C784),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showTargetMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            DropdownMenu(
                                expanded = showTargetMenu,
                                onDismissRequest = { showTargetMenu = false },
                                modifier = Modifier.background(Color(0xFF1A2340)),
                            ) {
                                com.haise.jiyu.source.LanguageMap.displayNames.filter { it != "Auto" }.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang, color = if (lang == targetLanguage) Color(0xFF81C784) else Color.White, fontSize = 13.sp) },
                                        onClick = { onTargetLanguageChange(lang); showTargetMenu = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.reader_glossary_button),
                            color = Color(0xFF8B5CF6),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .clickable { showGlossarySheet = true }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }

                    // Page scrubber
                    if (pages.size > 1) {
                        var sliderPage by remember(currentPage) { mutableStateOf(currentPage.toFloat()) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${(sliderPage + 1).toInt()}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.width(28.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Slider(
                                value = sliderPage,
                                onValueChange = { sliderPage = it },
                                onValueChangeFinished = { onJumpToPage(sliderPage.toInt()) },
                                valueRange = 0f..(pages.size - 1).toFloat(),
                                steps = 0,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF8B5CF6),
                                    activeTrackColor = Color(0xFF8B5CF6),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                                ),
                            )
                            Text(
                                text = "${pages.size}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.width(28.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }

                    // Slider jasu
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(TablerIcons.Moon, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        Slider(
                            value = if (brightness < 0f) 0.5f else brightness,
                            onValueChange = { brightness = it },
                            valueRange = 0.05f..1f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .semantics { contentDescription = "Jas obrazovky" },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF4FC3F7),
                                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                            ),
                        )
                        Icon(TablerIcons.Sun, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                    }

                    // Orientace + volume klávesy
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.reader_orientation_label), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        listOf(
                            "free" to stringResource(R.string.reader_orientation_auto),
                            "portrait" to stringResource(R.string.reader_orientation_portrait),
                            "landscape" to stringResource(R.string.reader_orientation_landscape),
                        ).forEach { (value, label) ->
                            TextButton(
                                onClick = { onSetReaderOrientation(value) },
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = if (readerOrientation == value) Color(0xFF8B5CF6) else Color.White.copy(alpha = 0.45f),
                                ),
                            ) { Text(label, fontSize = 11.sp) }
                        }
                    }

                    // Hromadný překlad — tlačítko + progress + přepínač originál/překlad
                    if (translateMode && !batchTranslating) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.reader_translation_word), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = !showOriginal,
                                    onCheckedChange = { onToggleShowOriginal() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF4FC3F7),
                                        checkedTrackColor = Color(0xFF4FC3F7).copy(alpha = 0.4f),
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                                    ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.reader_original_toggle), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }

                    if (batchTranslating) {
                        batchProgress?.let { progress ->
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.reader_translate_all_progress, progress.done, progress.total), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = onCancelBatch, modifier = Modifier.size(28.dp)) {
                                    Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_cancel), tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
                                }
                            }
                            LinearProgressIndicator(
                                progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                color = Color(0xFFFFB74D),
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                        }
                    } else if (!translateMode) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = onTranslateAll,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.6f)),
                        ) {
                            Icon(TablerIcons.Language, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.reader_translate_all_button), color = Color(0xFF4FC3F7), fontSize = 13.sp)
                        }
                    }

                    // Progress překladu aktuální stránky (pokud aktivní)
                    if (translationProgress != null) {
                        translationProgress.let { progress ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.reader_translating_progress, progress.done, progress.total), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text("${(progress.done * 100f / progress.total).toInt()} %", color = Color(0xFF4FC3F7), style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Color(0xFF4FC3F7), trackColor = Color.White.copy(alpha = 0.2f))
                        }
                    }
                }
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

// ── Slovník AI překladu - rychlý přístup přímo z čtečky ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlossaryBottomSheet(
    glossary: List<com.haise.jiyu.data.db.entity.GlossaryEntity>,
    targetLanguage: String,
    onAdd: (String, String) -> Unit,
    onRemove: (com.haise.jiyu.data.db.entity.GlossaryEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var sourceText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.reader_glossary_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                stringResource(R.string.reader_glossary_desc),
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = sourceText,
                    onValueChange = { sourceText = it },
                    placeholder = { Text(stringResource(R.string.reader_original_toggle), color = Color(0xFFB0BEC5), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    ),
                )
                Text("→", color = Color(0xFFB0BEC5))
                TextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    placeholder = { Text(stringResource(R.string.reader_glossary_translation_placeholder), color = Color(0xFFB0BEC5), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    ),
                )
            }
            TextButton(onClick = {
                if (sourceText.isNotBlank() && targetText.isNotBlank()) {
                    onAdd(sourceText, targetText)
                    sourceText = ""
                    targetText = ""
                }
            }) { Text(stringResource(R.string.reader_glossary_add_button, targetLanguage), color = Color(0xFF8B5CF6)) }

            if (glossary.isEmpty()) {
                Text(stringResource(R.string.reader_glossary_empty), color = Color(0xFFB0BEC5), fontSize = 13.sp)
            } else {
                glossary.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${entry.sourceTerm} → ${entry.targetTerm}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(entry) }, modifier = Modifier.size(24.dp)) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_remove), tint = Color(0xFFB0BEC5), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Horizontální manga reader (s pinch-to-zoom) ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaReader(
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
    scale: Float,
    panOffset: Offset,
    onScaleChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
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
) {
    val saveContext = androidx.compose.ui.platform.LocalContext.current
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
        if (!useSpread) {
            pages.indices.map { listOf(it) }
        } else {
            val result = mutableListOf<List<Int>>()
            var i = 0
            while (i < pages.size) {
                if (i in spreadPageIndices) {
                    result.add(listOf(i)); i++
                } else if (i + 1 < pages.size && (i + 1) !in spreadPageIndices) {
                    result.add(listOf(i, i + 1)); i += 2
                } else {
                    result.add(listOf(i)); i++
                }
            }
            result
        }
    }

    val saveScope = rememberCoroutineScope()
    var showShareSheet by remember { mutableStateOf(false) }
    var sharePageUrl by remember { mutableStateOf("") }
    if (showShareSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showShareSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF111B35),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.reader_share_page_chooser), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
                OutlinedButton(
                    onClick = { onSharePage(sharePageUrl); showShareSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.6f)),
                ) {
                    Icon(TablerIcons.Share, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reader_share_link), color = Color(0xFF4FC3F7))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val url = sharePageUrl
                        saveScope.launch { saveBitmapToGallery(saveContext, url) }
                        showShareSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.6f)),
                ) {
                    Icon(TablerIcons.DeviceFloppy, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reader_save_to_gallery), color = Color(0xFF8B5CF6))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // Tracks the single page index across recompositions and spread-mode resets.
    // Lives OUTSIDE key(useSpread) so it survives the pager recreation and gives the
    // new pager its correct starting group.
    var currentSingleIndex by rememberSaveable { mutableStateOf(initialPage) }

    // Auto-advance to next chapter when reaching last page with autoNextChapter enabled.
    // reachedEndManually ensures we only trigger after navigating away from initial page,
    // preventing immediate jump when resuming on the last page.
    var reachedEndManually by remember { mutableStateOf(false) }
    LaunchedEffect(currentSingleIndex, pages.size) {
        if (pages.size > 1 && currentSingleIndex < pages.size - 1) reachedEndManually = true
        if (reachedEndManually && pages.isNotEmpty() && currentSingleIndex == pages.size - 1 && autoNextChapter) {
            delay(2500)
            if (currentSingleIndex == pages.size - 1) onAutoNextChapter()
        }
    }

    // key(useSpread) destroys and recreates the pager whenever spread mode changes
    // (i.e. on rotation when double-page is enabled). The new pager receives the
    // correct initialGroupIndex immediately — no post-hoc scrollToPage correction
    // and no visual flash to a wrong page.
    key(useSpread) {
        val initialGroupIndex = remember(groups) {
            groups.indexOfFirst { currentSingleIndex in it }.coerceAtLeast(0)
        }

        val pagerState = rememberPagerState(
            initialPage = initialGroupIndex.coerceIn(0, groups.lastIndex.coerceAtLeast(0)),
            pageCount = { groups.size },
        )
        val scope = rememberCoroutineScope()

        LaunchedEffect(pagerState, groups) {
            snapshotFlow { pagerState.currentPage }.collect { groupIdx ->
                groups.getOrNull(groupIdx)?.firstOrNull()?.let {
                    currentSingleIndex = it
                    onPageChanged(it)
                }
            }
        }

        LaunchedEffect(jumpToPage) {
            val target = jumpToPage ?: return@LaunchedEffect
            val groupIdx = groups.indexOfFirst { target in it }.coerceAtLeast(0)
                .coerceIn(0, groups.lastIndex.coerceAtLeast(0))
            pagerState.animateScrollToPage(groupIdx)
            onJumpConsumed()
        }

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { try { focusRequester.requestFocus() } catch (_: Exception) {} }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.A -> {
                            val target = (pagerState.currentPage + if (reverseLayout) 1 else -1).coerceIn(0, groups.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(target) }
                            true
                        }
                        Key.DirectionRight, Key.D -> {
                            val target = (pagerState.currentPage + if (reverseLayout) -1 else 1).coerceIn(0, groups.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(target) }
                            true
                        }
                        Key.VolumeDown -> if (volumeKeysNav) {
                            val target = (pagerState.currentPage + if (reverseLayout) -1 else 1).coerceIn(0, groups.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(target) }
                            true
                        } else false
                        Key.VolumeUp -> if (volumeKeysNav) {
                            val target = (pagerState.currentPage + if (reverseLayout) 1 else -1).coerceIn(0, groups.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(target) }
                            true
                        } else false
                        else -> false
                    }
                },
            reverseLayout = reverseLayout,
            userScrollEnabled = scale <= 1f,
        ) { groupIdx ->
            val indices = groups[groupIdx]
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            onScaleChange(newScale)
                            if (newScale > 1f) onPanChange(panOffset + pan)
                            else onPanChange(Offset.Zero)
                        }
                    }
                    .pointerInput(tapZonesEnabled, tapZoneGrid, reverseLayout, groups.size) {
                        detectTapGestures(
                            onLongPress = {
                                sharePageUrl = pages.getOrElse(indices[0]) { "" }
                                if (sharePageUrl.isNotEmpty()) showShareSheet = true
                            },
                            onDoubleTap = { offset ->
                                if (scale > 1f) {
                                    onScaleChange(1f)
                                    onPanChange(Offset.Zero)
                                } else {
                                    val zoom = 2.5f
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    onScaleChange(zoom)
                                    onPanChange(Offset(
                                        (offset.x - cx) * (1f - zoom),
                                        (offset.y - cy) * (1f - zoom),
                                    ))
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
                                TapZoneAction.PREV_PAGE -> {
                                    val target = (pagerState.currentPage + if (reverseLayout) 1 else -1).coerceIn(0, groups.lastIndex)
                                    scope.launch { pagerState.animateScrollToPage(target) }
                                }
                                TapZoneAction.NEXT_PAGE -> {
                                    val target = (pagerState.currentPage + if (reverseLayout) -1 else 1).coerceIn(0, groups.lastIndex)
                                    scope.launch { pagerState.animateScrollToPage(target) }
                                }
                                TapZoneAction.PREV_CHAPTER -> onNavigatePrevChapter()
                                TapZoneAction.NEXT_CHAPTER -> onNavigateNextChapter()
                                TapZoneAction.NONE -> {}
                            }
                        })
                    },
            ) {
                if (indices.size == 1) {
                    RetryableAsyncImage(
                        url = pages[indices[0]],
                        contentDescription = stringResource(R.string.reader_page_content_desc, indices[0] + 1),
                        contentScale = resolvedContentScale,
                        cropBorders = cropBorders,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = panOffset.x,
                                translationY = panOffset.y,
                            ),
                    )
                    if (translateMode) {
                        val blocks = translatedPages[indices[0]]
                        if (!blocks.isNullOrEmpty()) {
                            val positioned = remember(blocks) { layoutTranslationBlocks(blocks) }
                            positioned.forEach { pos -> TranslationOverlay(pos, textScale) }
                        }
                    }
                } else {
                    val ordered = if (reverseLayout) indices.reversed() else indices
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = panOffset.x,
                                translationY = panOffset.y,
                            ),
                    ) {
                        ordered.forEach { idx ->
                            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) {
                                RetryableAsyncImage(
                                    url = pages[idx],
                                    contentDescription = stringResource(R.string.reader_page_content_desc, idx + 1),
                                    contentScale = resolvedContentScale,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (translateMode) {
                                    val blocks = translatedPages[idx]
                                    if (!blocks.isNullOrEmpty()) {
                                        val positioned = remember(blocks) { layoutTranslationBlocks(blocks) }
                                        positioned.forEach { pos -> TranslationOverlay(pos, textScale) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Vertikální webtoon reader ────────────────────────────────────────────────

@Composable
private fun WebtoonReader(
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
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focusRequester.requestFocus() } catch (_: Exception) {} }

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

    LaunchedEffect(pages, initialPage) {
        if (pages.isNotEmpty() && (initialPage > 0 || initialScrollOffset > 0)) {
            listState.scrollToItem(
                initialPage.coerceIn(0, pages.lastIndex),
                initialScrollOffset,
            )
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            onPageChanged(idx)
            onScrollOffsetChanged(offset)
        }
    }

    val flingBehavior = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    val speedFling = remember(scrollSpeedMultiplier, flingBehavior) {
        object : androidx.compose.foundation.gestures.FlingBehavior {
            override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float =
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
        if (translateMode && translatedBlocks.isNotEmpty() && size != IntSize.Zero) {
            val positioned = remember(translatedBlocks) { layoutTranslationBlocks(translatedBlocks) }
            positioned.forEach { pos ->
                with(density) {
                    // .coerceAtLeast(0.dp): záporná šířka/výška z neobvyklého OCR boxu by jinak
                    // spadla na IllegalArgumentException přímo v Compose layout fázi (mimo dosah
                    // try/catch kolem překladu).
                    //
                    // TRANSLATION_BOX_BLEED: OCR box je občas o chlup těsnější než skutečný
                    // vizuální rozsah textu (antialiasing, mírně pootočené písmo v bublině) -
                    // bez malého přesahu pak po stranách prosvítal kousek originálu.
                    val boxWidth = (size.width * (pos.rightF - pos.leftF)).toInt().toDp().coerceAtLeast(0.dp) + TRANSLATION_BOX_BLEED * 2
                    val maxHeight = (size.height * (pos.maxBottomF - pos.minTopF)).toInt().toDp().coerceAtLeast(0.dp) + TRANSLATION_BOX_BLEED * 2
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (size.width * pos.leftF).toInt().toDp() - TRANSLATION_BOX_BLEED,
                                y = (size.height * pos.minTopF).toInt().toDp() - TRANSLATION_BOX_BLEED,
                            )
                            .widthIn(max = boxWidth)
                            .background(TRANSLATION_BOX_COLOR, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AutoFitTranslatedText(
                            text = pos.block.translatedText,
                            boxWidth = boxWidth,
                            maxHeight = maxHeight,
                            textScale = textScale,
                        )
                    }
                }
            }
        }
    }
}

// ── Stránka s možností opětovného načtení při selhání ────────────────────────

@Composable
private fun RetryableAsyncImage(
    url: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    cropBorders: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryTrigger by remember(url) { mutableStateOf(0) }
    var isError by remember(url) { mutableStateOf(false) }

    Box(modifier = modifier) {
        val request = remember(url, retryTrigger, cropBorders) {
            ImageRequest.Builder(context)
                .data(url)
                .apply { if (cropBorders) transformations(CropBordersTransformation()) }
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = imageModifier,
            onState = { state -> isError = state is AsyncImagePainter.State.Error },
        )
        if (isError) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(TablerIcons.AlertCircle, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.reader_page_load_failed), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { isError = false; retryTrigger++ }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}

// ── Translation overlay ──────────────────────────────────────────────────────

/** Malý přesah kolem přeloženého boxu, aby nikde neprosvítal kousek originálu za okrajem OCR boxu. */
private val TRANSLATION_BOX_BLEED = 2.dp

/** Téměř neprůhledné (ne 100%, kvůli měkčímu vzhledu štítku) - vyšší než dřívějších 92 % kvůli viditelnému "duchu" originálu skrz box. */
private val TRANSLATION_BOX_COLOR = Color.White.copy(alpha = 0.98f)

@Composable
private fun BoxWithConstraintsScope.TranslationOverlay(pos: PositionedTranslationBlock, textScale: Float = 1f) {
    // OCR bounding box je v zásadě vždy leftF<=rightF/topF<=bottomF, ale nejde o zaručený
    // invariant (různé OCR modely, rotace/mirror snímků atd.) - záporná šířka/výška předaná
    // do Modifier.width()/height() spadne na IllegalArgumentException přímo v Compose layout
    // fázi, mimo dosah jakéhokoliv try/catch kolem překladu, a appka tvrdě spadne.
    val left = maxWidth  * pos.leftF - TRANSLATION_BOX_BLEED
    val top  = maxHeight * pos.minTopF - TRANSLATION_BOX_BLEED
    val w    = (maxWidth  * (pos.rightF     - pos.leftF)).coerceAtLeast(0.dp) + TRANSLATION_BOX_BLEED * 2
    val maxH = (maxHeight * (pos.maxBottomF - pos.minTopF)).coerceAtLeast(0.dp) + TRANSLATION_BOX_BLEED * 2

    Box(
        modifier = Modifier
            .offset(x = left, y = top)
            .widthIn(max = w)
            .background(TRANSLATION_BOX_COLOR, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        AutoFitTranslatedText(
            text = pos.block.translatedText,
            boxWidth = w,
            maxHeight = maxH,
            textScale = textScale,
        )
    }
}

/**
 * Přeložený text (čeština) bývá delší než originál (JP/KR/EN) - bez úpravy velikosti
 * písma by buď přetekl přes sousední bublinu, nebo by ho Text s overflow=Ellipsis tvrdě
 * uřízl. Místo pevné velikosti fontu tady najdeme největší velikost, která se ještě
 * vejde do [maxHeight] při dané [boxWidth] (měřeno přes TextMeasurer), a teprve tu
 * vykreslíme - box tak roste/mrští se podle skutečné potřeby textu, ne naopak.
 */
@Composable
private fun AutoFitTranslatedText(
    text: String,
    boxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    textScale: Float,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val baseFontSp = 11f * textScale
    val minFontSp = 6f * textScale
    val widthPx = with(density) { boxWidth.roundToPx() }.coerceAtLeast(1)
    val maxHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

    val fontSp = remember(text, widthPx, maxHeightPx, baseFontSp) {
        var fs = baseFontSp
        while (fs > minFontSp) {
            val measured = textMeasurer.measure(
                text = text,
                style = TextStyle(fontSize = fs.sp, lineHeight = (fs * 1.25f).sp),
                constraints = Constraints(maxWidth = widthPx),
            )
            if (measured.size.height <= maxHeightPx) break
            fs -= 0.5f
        }
        fs.coerceAtLeast(minFontSp)
    }

    Text(
        text = text,
        color = Color.Black,
        fontSize = fontSp.sp,
        lineHeight = (fontSp * 1.25f).sp,
    )
}

private suspend fun saveBitmapToGallery(context: android.content.Context, url: String) {
    val bitmap: android.graphics.Bitmap? = if (url.startsWith("/") || url.startsWith("file://")) {
        val path = url.removePrefix("file://")
        android.graphics.BitmapFactory.decodeFile(path)
    } else {
        val request = coil.request.ImageRequest.Builder(context).data(url).build()
        val result = coil.Coil.imageLoader(context).execute(request)
        (result as? coil.request.SuccessResult)?.drawable?.let {
            (it as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }
    }
    bitmap ?: return
    val filename = "jiyu_${System.currentTimeMillis()}.jpg"
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/Jiyu")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val updateValues = android.content.ContentValues()
        updateValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, updateValues, null, null)
    }
}

package com.haise.jiyu.ui.reader

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.translate.TranslatedBlock
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
    val tapZoneLeft          by viewModel.tapZoneLeftFraction.collectAsState()
    val tapZoneRight         by viewModel.tapZoneRightFraction.collectAsState()
    val webtoonScrollSpeed   by viewModel.webtoonScrollSpeed.collectAsState()
    val isNovelSource        by viewModel.isNovelSource.collectAsState()
    val novelText            by viewModel.novelText.collectAsState()

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    val activity = LocalView.current.context as Activity

    // Sleep timer dialog
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Časovač spánku", color = Color.White) },
            text = {
                Column {
                    Text("Zavřít čtečku po:", color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    listOf(15 to "15 minut", 30 to "30 minut", 45 to "45 minut", 60 to "1 hodina").forEach { (min, label) ->
                        TextButton(onClick = {
                            viewModel.startSleepTimer(min) { activity.finish() }
                            showSleepTimerDialog = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(label, color = Color.White) }
                    }
                    if (viewModel.sleepTimerRemaining.value != null) {
                        TextButton(onClick = { viewModel.cancelSleepTimer(); showSleepTimerDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("Zrušit časovač", color = Color(0xFFEF9A9A))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimerDialog = false }) { Text("Zavřít", color = Color(0xFFB0BEC5)) } },
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

    // Obrazovka nezhasne, dokud se čte - nic nezkazí čtení hůř než timeout uprostřed kapitoly
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading      -> CircularProgressIndicator(color = Color(0xFF8B5CF6))
            isNovelSource -> NovelContent(
                text = novelText,
                chapterTitle = chapterTitle,
                hasPrev = hasPrevChapter,
                hasNext = hasNextChapter,
                onPrev = { viewModel.navigatePrev() },
                onNext = { viewModel.navigateNext() },
            )
            pages.isEmpty() -> Text("Kapitolu se nepodařilo načíst.", color = Color.White)
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
                    context.startActivity(Intent.createChooser(intent, "Sdílet stránku"))
                },
                onSleepTimerClick = { showSleepTimerDialog = true },
                panelMode = panelMode,
                onTogglePanelMode = { viewModel.togglePanelMode() },
                oledMode = oledMode,
                incognitoMode = incognitoMode,
                onToggleIncognito = { viewModel.toggleIncognito() },
                sessionElapsed = sessionElapsed,
                tapZoneLeft = tapZoneLeft,
                tapZoneRight = tapZoneRight,
                webtoonScrollSpeed = webtoonScrollSpeed,
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
                    "INKOGNITO",
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
        androidx.compose.material3.TopAppBar(
            title = { Text(chapterTitle, color = Color(0xFFE8E8E8), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            actions = {
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.BrightnessHigh, "Nastavení", tint = Color(0xFFB0BEC5))
                }
            },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D1A)),
        )

        AnimatedVisibility(visible = showSettings, enter = slideInVertically(), exit = slideOutVertically()) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF1A1B35)),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("Velikost: ${fontSize.toInt()}sp", color = Color(0xFFB0BEC5), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { if (fontSize > 10f) fontSize -= 1f }, modifier = Modifier.size(36.dp)) {
                                Text("A-", color = Color(0xFFE8E8E8), fontSize = 13.sp)
                            }
                            IconButton(onClick = { if (fontSize < 30f) fontSize += 1f }, modifier = Modifier.size(36.dp)) {
                                Text("A+", color = Color(0xFFE8E8E8), fontSize = 17.sp)
                            }
                        }
                    }
                    Text("Řádkování: ${String.format("%.1f", lineSpacing)}x", color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    Slider(
                        value = lineSpacing, onValueChange = { lineSpacing = it },
                        valueRange = 1.0f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF8B5CF6), activeTrackColor = Color(0xFF8B5CF6)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("🌙 Tmavé", "🟤 Sépiové", "📄 Papír", "☀️ Bílé").forEachIndexed { i, label ->
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
                        TextButton(onClick = onPrev) { Text("← Předchozí", color = Color(0xFF34D1BF)) }
                    } else { Spacer(Modifier) }
                    if (hasNext) {
                        TextButton(onClick = onNext) { Text("Další →", color = Color(0xFF34D1BF)) }
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
    tapZoneLeft: Float = 0.3f,
    tapZoneRight: Float = 0.3f,
    webtoonScrollSpeed: Float = 1.0f,
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
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
                translateMode = effectiveTranslateMode,
                translatedPages = translatedPages,
                textScale = textScale,
                onPageChanged = onPageChanged,
                onTap = { controlsVisible = !controlsVisible },
                scrollSpeedMultiplier = webtoonScrollSpeed,
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
                tapZoneLeft = tapZoneLeft,
                tapZoneRight = tapZoneRight,
                scale = scale,
                panOffset = panOffset,
                onScaleChange = { scale = it },
                onPanChange = { panOffset = it },
                onPageChanged = { page ->
                    // Reset zoom při přechodu na jinou stránku
                    scale = 1f; panOffset = Offset.Zero
                    onPageChanged(page)
                },
                onTap = { controlsVisible = !controlsVisible },
                onSharePage = onSharePage,
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
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Předchozí kapitola",
                                tint = if (hasPrevChapter) Color.White else Color.White.copy(alpha = 0.25f),
                            )
                        }

                        // Název + stránka
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
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
                                        Icons.Filled.WifiOff,
                                        contentDescription = "Offline",
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
                        IconButton(onClick = onTogglePanelMode) {
                            Icon(
                                Icons.Filled.ViewDay,
                                contentDescription = "Panel po panelu",
                                tint = if (panelMode) Color(0xFFCE93D8) else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Sleep timer (#42)
                        IconButton(onClick = onSleepTimerClick) {
                            Icon(
                                Icons.Filled.Bedtime,
                                contentDescription = "Časovač spánku",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Incognito mode
                        IconButton(onClick = onToggleIncognito) {
                            Icon(
                                if (incognitoMode) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (incognitoMode) "Vypnout inkognito" else "Inkognito",
                                tint = if (incognitoMode) Color(0xFFCE93D8) else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Překlad
                        val isTranslating = translationProgress != null
                        IconButton(onClick = onToggleTranslate) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = when {
                                    isTranslating -> "Zastavit překlad"
                                    translateMode -> "Skrýt překlad"
                                    else          -> "Přeložit kapitolu"
                                },
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
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Další kapitola",
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
                        Text("Překlad:", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
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
                    }

                    // Slider jasu
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BrightnessLow, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
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
                        Icon(Icons.Filled.BrightnessHigh, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                    }

                    // Hromadný překlad — tlačítko + progress + přepínač originál/překlad
                    if (translateMode && !batchTranslating) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Překlad", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
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
                                Text("Originál", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }

                    if (batchTranslating) {
                        batchProgress?.let { progress ->
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Překlad všech… ${progress.done}/${progress.total}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = onCancelBatch, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Zrušit", tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
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
                            Icon(Icons.Filled.Translate, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Přeložit vše", color = Color(0xFF4FC3F7), fontSize = 13.sp)
                        }
                    }

                    // Progress překladu aktuální stránky (pokud aktivní)
                    if (translationProgress != null) {
                        translationProgress.let { progress ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Překládám… ${progress.done}/${progress.total}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text("${(progress.done * 100f / progress.total).toInt()} %", color = Color(0xFF4FC3F7), style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Color(0xFF4FC3F7), trackColor = Color.White.copy(alpha = 0.2f))
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
    tapZoneLeft: Float = 0.3f,
    tapZoneRight: Float = 0.3f,
    scale: Float,
    panOffset: Offset,
    onScaleChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onSharePage: (String) -> Unit = {},
) {
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
                Text("Sdílet stránku", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
                OutlinedButton(
                    onClick = { onSharePage(sharePageUrl); showShareSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.6f)),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sdílet odkaz", color = Color(0xFF4FC3F7))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // Sleduje aktuální JEDNOTLIVOU stránku (ne index skupiny) přes rememberSaveable,
    // aby po rotaci s aktivním dvoustránkovým zobrazením šlo dopočítat správnou
    // novou skupinu - jinak by pagerState obnovil svůj starý číselný index, který
    // pod novým seskupením znamená úplně jinou (nebo mimo rozsah) dvojici stránek.
    var currentSingleIndex by rememberSaveable { mutableStateOf(initialPage) }

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

    // Korekce po zmene seskupeni (typicky rotace obrazovky) - viz komentar u currentSingleIndex.
    LaunchedEffect(useSpread) {
        val target = groups.indexOfFirst { currentSingleIndex in it }.coerceAtLeast(0)
        if (target in groups.indices && target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
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
                    else -> false
                }
            },
        reverseLayout = reverseLayout,
        userScrollEnabled = scale <= 1f, // Zablokuj swipe při zoomu
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
                .pointerInput(tapZonesEnabled, tapZoneLeft, tapZoneRight, reverseLayout, groups.size) {
                    detectTapGestures(
                        onLongPress = {
                            sharePageUrl = pages.getOrElse(indices[0]) { "" }
                            if (sharePageUrl.isNotEmpty()) showShareSheet = true
                        },
                        onTap = { offset ->
                        if (!tapZonesEnabled) { onTap(); return@detectTapGestures }
                        val fraction = offset.x / size.width
                        val delta = when {
                            fraction < tapZoneLeft  -> if (reverseLayout) 1 else -1
                            fraction > 1f - tapZoneRight -> if (reverseLayout) -1 else 1
                            else -> 0
                        }
                        if (delta == 0) {
                            onTap()
                        } else {
                            val target = (pagerState.currentPage + delta).coerceIn(0, groups.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(target) }
                        }
                    })
                },
        ) {
            if (indices.size == 1) {
                AsyncImage(
                    model = pages[indices[0]],
                    contentDescription = "Stránka ${indices[0] + 1}",
                    contentScale = ContentScale.Fit,
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
                    translatedPages[indices[0]]?.forEach { block -> TranslationOverlay(block, textScale) }
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
                            AsyncImage(
                                model = pages[idx],
                                contentDescription = "Stránka ${idx + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (translateMode) {
                                translatedPages[idx]?.forEach { block -> TranslationOverlay(block, textScale) }
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
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    textScale: Float,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    scrollSpeedMultiplier: Float = 1.0f,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(pages, initialPage) {
        if (pages.isNotEmpty() && initialPage > 0) {
            listState.scrollToItem(initialPage.coerceIn(0, pages.lastIndex))
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onPageChanged(it) }
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
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        itemsIndexed(pages) { index, pageUrl ->
            WebtoonPage(
                pageUrl = pageUrl,
                pageIndex = index,
                translateMode = translateMode,
                translatedBlocks = translatedPages[index] ?: emptyList(),
                textScale = textScale,
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
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = pageUrl,
            contentDescription = "Stránka ${pageIndex + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size = it },
        )
        if (translateMode && translatedBlocks.isNotEmpty() && size != IntSize.Zero) {
            translatedBlocks.forEach { block ->
                with(density) {
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (size.width * block.leftF).toInt().toDp(),
                                y = (size.height * block.topF).toInt().toDp(),
                            )
                            .width((size.width * (block.rightF - block.leftF)).toInt().toDp())
                            .height((size.height * (block.bottomF - block.topF)).toInt().toDp())
                            .background(Color.Black.copy(alpha = 0.82f))
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = block.translatedText,
                            color = Color.White,
                            fontSize = (10 * textScale).sp,
                            lineHeight = (13 * textScale).sp,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── Translation overlay ──────────────────────────────────────────────────────

@Composable
private fun BoxWithConstraintsScope.TranslationOverlay(block: TranslatedBlock, textScale: Float = 1f) {
    val left = maxWidth  * block.leftF
    val top  = maxHeight * block.topF
    val w    = maxWidth  * (block.rightF  - block.leftF)
    val h    = maxHeight * (block.bottomF - block.topF)

    Box(
        modifier = Modifier
            .offset(x = left, y = top)
            .width(w)
            .height(h)
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = block.translatedText,
            color = Color.White,
            fontSize = (10 * textScale).sp,
            lineHeight = (13 * textScale).sp,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

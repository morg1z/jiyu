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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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

    // Fullscreen immersive
    val view = LocalView.current
    DisposableEffect(Unit) {
        val ctrl = WindowCompat.getInsetsController((view.context as Activity).window, view)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading      -> CircularProgressIndicator(color = Color(0xFF8B5CF6))
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
            )
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
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) { delay(3_000L); controlsVisible = false }
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
            )
        } else {
            MangaReader(
                pages = pages,
                initialPage = initialPage,
                translateMode = effectiveTranslateMode,
                translatedPages = translatedPages,
                reverseLayout = reverseLayout,
                doublePageSpread = doublePageSpread,
                textScale = textScale,
                tapZonesEnabled = tapZonesEnabled,
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
            )
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
                            Text(
                                text = chapterTitle,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${currentPage + 1} / ${pages.size}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
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

@Composable
private fun MangaReader(
    pages: List<String>,
    initialPage: Int,
    translateMode: Boolean,
    translatedPages: Map<Int, List<TranslatedBlock>>,
    reverseLayout: Boolean,
    doublePageSpread: Boolean,
    textScale: Float,
    tapZonesEnabled: Boolean,
    scale: Float,
    panOffset: Offset,
    onScaleChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useSpread = doublePageSpread && isLandscape

    // Dvoustránkové zobrazení: skupiny po 2 stránkách na šířku (jen na landscape)
    val groups: List<List<Int>> = remember(pages.size, useSpread) {
        if (!useSpread) pages.indices.map { listOf(it) }
        else pages.indices.chunked(2)
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

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
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
                .pointerInput(tapZonesEnabled, reverseLayout, groups.size) {
                    detectTapGestures(onTap = { offset ->
                        if (!tapZonesEnabled) { onTap(); return@detectTapGestures }
                        val fraction = offset.x / size.width
                        val delta = when {
                            fraction < 0.3f -> if (reverseLayout) 1 else -1
                            fraction > 0.7f -> if (reverseLayout) -1 else 1
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

    LazyColumn(
        state = listState,
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

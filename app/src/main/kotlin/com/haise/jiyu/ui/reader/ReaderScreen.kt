package com.haise.jiyu.ui.reader

import com.haise.jiyu.ui.components.JiyuLoadingIndicator

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import com.haise.jiyu.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onFindSource: () -> Unit = {},
    onOpenManga: (String) -> Unit = {},
    onNavigateHome: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val pages               by viewModel.pages.collectAsStateWithLifecycle()
    val pageReferer         by viewModel.pageReferer.collectAsStateWithLifecycle()
    val comickUnavailable   by viewModel.comickUnavailable.collectAsStateWithLifecycle()
    val loading             by viewModel.loading.collectAsStateWithLifecycle()
    val translateMode       by viewModel.translateMode.collectAsStateWithLifecycle()
    val translationProgress by viewModel.translationProgress.collectAsStateWithLifecycle()
    val translatedPages     by viewModel.translatedPages.collectAsStateWithLifecycle()
    val batchTranslating    by viewModel.batchTranslating.collectAsStateWithLifecycle()
    val batchProgress       by viewModel.batchProgress.collectAsStateWithLifecycle()
    val showOriginal        by viewModel.showOriginal.collectAsStateWithLifecycle()
    val reverseLayout       by viewModel.reverseLayout.collectAsStateWithLifecycle()
    val readingMode         by viewModel.readingMode.collectAsStateWithLifecycle()
    val initialPage         by viewModel.initialPage.collectAsStateWithLifecycle()
    val currentPage         by viewModel.currentPage.collectAsStateWithLifecycle()
    val hasPrevChapter      by viewModel.hasPrevChapter.collectAsStateWithLifecycle()
    val hasNextChapter      by viewModel.hasNextChapter.collectAsStateWithLifecycle()
    val chapterTitle        by viewModel.chapterTitle.collectAsStateWithLifecycle()
    val mangaTitle           by viewModel.mangaTitle.collectAsStateWithLifecycle()
    val mangaId              by viewModel.mangaId.collectAsStateWithLifecycle()
    val currentChapterId     by viewModel.currentChapterId.collectAsStateWithLifecycle()
    val sourceLanguage      by viewModel.sourceLanguage.collectAsStateWithLifecycle()
    val targetLanguage      by viewModel.targetLanguage.collectAsStateWithLifecycle()
    val tapZonesEnabled     by viewModel.tapZonesEnabled.collectAsStateWithLifecycle()
    val readerTextScale     by viewModel.readerTextScale.collectAsStateWithLifecycle()
    val doublePageSpread    by viewModel.doublePageSpread.collectAsStateWithLifecycle()
    val translationError    by viewModel.translationError.collectAsStateWithLifecycle()
    val fallbackNotice     by viewModel.fallbackNotice.collectAsStateWithLifecycle()
    val fullscreenEnabled   by viewModel.fullscreenEnabled.collectAsStateWithLifecycle()
    val readerTheme         by viewModel.readerTheme.collectAsStateWithLifecycle()
    val isOfflineChapter    by viewModel.isOfflineChapter.collectAsStateWithLifecycle()
    val chapterProgress     by viewModel.chapterProgress.collectAsStateWithLifecycle()
    val spreadPageIndices   by viewModel.spreadPageIndices.collectAsStateWithLifecycle()
    val sleepTimerRemaining  by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val panelMode            by viewModel.panelMode.collectAsStateWithLifecycle()
    val oledMode             by viewModel.oledMode.collectAsStateWithLifecycle()
    val pageCurlEnabled      by viewModel.pageCurlEnabled.collectAsStateWithLifecycle()
    val curlStyle            by viewModel.curlStyle.collectAsStateWithLifecycle()
    val incognitoMode        by viewModel.incognitoMode.collectAsStateWithLifecycle()
    val sessionElapsed       by viewModel.sessionElapsed.collectAsStateWithLifecycle()
    val tapZoneGrid          by viewModel.tapZoneGrid.collectAsStateWithLifecycle()
    val webtoonScrollSpeed   by viewModel.webtoonScrollSpeed.collectAsStateWithLifecycle()
    val isNovelSource        by viewModel.isNovelSource.collectAsStateWithLifecycle()
    val isApiKeyConfigured = viewModel.isApiKeyConfigured
    val novelText            by viewModel.novelText.collectAsStateWithLifecycle()
    val novelTranslateMode   by viewModel.novelTranslateMode.collectAsStateWithLifecycle()
    val novelTranslatedText  by viewModel.novelTranslatedText.collectAsStateWithLifecycle()
    val novelTranslating     by viewModel.novelTranslating.collectAsStateWithLifecycle()
    val glossary             by viewModel.glossary.collectAsStateWithLifecycle()
    val chapterComments       by viewModel.chapterComments.collectAsStateWithLifecycle()
    val commentsLoading       by viewModel.commentsLoading.collectAsStateWithLifecycle()
    val commentsSupported     by viewModel.commentsSupported.collectAsStateWithLifecycle()
    val pageScale            by viewModel.pageScale.collectAsStateWithLifecycle()
    val jumpToPage           by viewModel.jumpToPage.collectAsStateWithLifecycle()
    val allChapters          by viewModel.allChaptersFlow.collectAsStateWithLifecycle()
    val autoNextChapter      by viewModel.autoNextChapter.collectAsStateWithLifecycle()
    val cropBorders          by viewModel.cropBorders.collectAsStateWithLifecycle()
    val webtoonScrollOffset  by viewModel.webtoonScrollOffset.collectAsStateWithLifecycle()
    val volumeKeysNav        by viewModel.volumeKeysNav.collectAsStateWithLifecycle()
    val keepScreenOn         by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val readerOrientation    by viewModel.readerOrientation.collectAsStateWithLifecycle()
    val controlsVisible      by viewModel.controlsVisible.collectAsStateWithLifecycle()
    val flippedBubbles       by viewModel.flippedBubbles.collectAsStateWithLifecycle()
    val webtoonSegments      by viewModel.webtoonSegments.collectAsStateWithLifecycle()

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    // Ručně opravovaná bublina: (index stránky, původní text, aktuální překlad). Původní text
    // je identita bubliny napříč přepočty - viz manualEditId.
    var bubbleEdit by remember { mutableStateOf<BubbleEditState?>(null) }
    val activity = LocalView.current.context as Activity

    // Čtečku zavírá až tenhle sběratel, ne lambda předaná do časovače. Ta totiž putovala do
    // singletonu, který ji držel po celou dobu odpočtu i poté, co uživatel ze čtečky odešel -
    // a spolu s ní i celou Activity. Takhle je Activity potřeba jen ve chvíli, kdy odpočet
    // opravdu doběhne, a to už tady nikdo neposlouchá, pokud čtečka mezitím zmizela.
    LaunchedEffect(Unit) {
        viewModel.sleepTimerFinished.collect { activity.finish() }
    }

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
                            viewModel.startSleepTimer(min)
                            showSleepTimerDialog = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(label, color = Color.White) }
                    }
                    // Sbíraná hodnota z ř. 82, ne syrové StateFlow.value - to se přečte jen
                    // jednou při složení a Compose se pak nedozví, že se časovač změnil,
                    // takže tlačítko "zrušit" v otevřeném dialogu nereagovalo na spuštění
                    // ani doběhnutí časovače.
                    if (sleepTimerRemaining != null) {
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

    // Fullscreen immersive (podle nastavení čtečky); mimo čtečku appka lišty
    // schovává vždy (viz MainActivity), takže po odchodu je necháváme schované
    val view = LocalView.current
    DisposableEffect(fullscreenEnabled) {
        val ctrl = WindowCompat.getInsetsController((view.context as Activity).window, view)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreenEnabled) ctrl.hide(WindowInsetsCompat.Type.systemBars())
        else ctrl.show(WindowInsetsCompat.Type.systemBars())
        onDispose { ctrl.hide(WindowInsetsCompat.Type.systemBars()) }
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

    LaunchedEffect(fallbackNotice) {
        if (fallbackNotice != null) {
            delay(4_000L)
            viewModel.clearFallbackNotice()
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
                onAddGlossaryEntry = { source, target, protectExact -> viewModel.addGlossaryEntry(source, target, protectExact) },
                onToggleGlossaryProtectExact = { viewModel.toggleGlossaryProtectExact(it) },
                onRemoveGlossaryEntry = { viewModel.removeGlossaryEntry(it) },
                pageCurlEnabled = pageCurlEnabled,
                curlStyle = curlStyle,
            )
            comickUnavailable -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.detail_comick_read_unavailable),
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(onClick = onFindSource, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.reader_comick_find_source))
                }
            }
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
                mangaTitle = mangaTitle,
                onOpenManga = { mangaId?.let(onOpenManga) },
                onNavigateHome = onNavigateHome,
                hasPrevChapter = hasPrevChapter,
                hasNextChapter = hasNextChapter,
                controlsVisible = controlsVisible,
                onToggleControlsVisible = { viewModel.toggleControlsVisible() },
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
                onAdvancedSheetVisibilityChanged = { viewModel.onAdvancedSheetVisibilityChanged(it) },
                sessionElapsed = sessionElapsed,
                webtoonScrollSpeed = webtoonScrollSpeed,
                pageScale = pageScale,
                jumpToPage = jumpToPage,
                onJumpToPage = { viewModel.jumpToPage(it) },
                onJumpConsumed = { viewModel.clearJump() },
                allChapters = allChapters,
                currentChapterId = currentChapterId,
                onJumpToChapter = { viewModel.jumpToChapter(it) },
                onResetChapter = { currentChapterId?.let { viewModel.jumpToChapter(it) } },
                webtoonSegments = webtoonSegments,
                onNeedMoreWebtoonSegments = { viewModel.appendNextWebtoonSegment() },
                onWebtoonVisibleChapterChanged = { id, localIndex, localOffset ->
                    viewModel.onWebtoonVisibleChapterChanged(id, localIndex, localOffset)
                },
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = { viewModel.navigateNext() },
                cropBorders = cropBorders,
                webtoonScrollOffset = webtoonScrollOffset,
                volumeKeysNav = volumeKeysNav,
                readerOrientation = readerOrientation,
                onSetReaderOrientation = { viewModel.setReaderOrientation(it) },
                glossary = glossary,
                onAddGlossaryEntry = { source, target, protectExact -> viewModel.addGlossaryEntry(source, target, protectExact) },
                onToggleGlossaryProtectExact = { viewModel.toggleGlossaryProtectExact(it) },
                onRemoveGlossaryEntry = { viewModel.removeGlossaryEntry(it) },
                chapterComments = chapterComments,
                commentsLoading = commentsLoading,
                commentsSupported = commentsSupported,
                onShowComments = { viewModel.loadChapterComments() },
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = { pageIndex, bubbleIndex -> viewModel.toggleBubbleFlip(pageIndex, bubbleIndex) },
                onEditBubble = { pageIndex, originalText, currentText, offsetXDp, offsetYDp ->
                    bubbleEdit = BubbleEditState(pageIndex, originalText, currentText, offsetXDp, offsetYDp)
                },
                onDeviceWarningText = if (!isApiKeyConfigured && translateMode) stringResource(R.string.reader_on_device_warning) else null,
                pageCurlEnabled = pageCurlEnabled,
                curlStyle = curlStyle,
                referer = pageReferer,
            )
        }

        bubbleEdit?.let { edit ->
            BubbleEditDialog(
                originalText = edit.originalText,
                currentText = edit.currentText,
                initialOffsetXDp = edit.offsetXDp,
                initialOffsetYDp = edit.offsetYDp,
                onDismiss = { bubbleEdit = null },
                onSave = { newText, offsetXDp, offsetYDp ->
                    viewModel.saveBubbleEdit(edit.pageIndex, edit.originalText, newText, offsetXDp, offsetYDp)
                    bubbleEdit = null
                },
                onRetranslatePage = {
                    viewModel.retranslatePage(edit.pageIndex)
                    bubbleEdit = null
                },
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

        AnimatedVisibility(
            visible = fallbackNotice != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF37474F).copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(fallbackNotice.orEmpty(), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Rucni oprava prekladu jedne bubliny.
 *
 * Puvodni text je vidiet jen pro orientaci a neda se menit - je to identita bubliny napric
 * prepocty (viz manualEditId), takze zmena by opravu odpojila od bubliny, ke ktere patri.
 *
 * Prazdne pole opravu ZRUSI a vrati strojovy preklad - proto tu neni tlacitko "smazat" navic.
 */
/** Stav otevřeného [BubbleEditDialog] - viz [BubbleOverlayLayer.onEditBubble]. */
private data class BubbleEditState(
    val pageIndex: Int,
    val originalText: String,
    val currentText: String,
    val offsetXDp: Float,
    val offsetYDp: Float,
)

@Composable
private fun BubbleEditDialog(
    originalText: String,
    currentText: String,
    initialOffsetXDp: Float = 0f,
    initialOffsetYDp: Float = 0f,
    onDismiss: () -> Unit,
    onSave: (text: String, offsetXDp: Float, offsetYDp: Float) -> Unit,
    onRetranslatePage: () -> Unit = {},
) {
    var text by remember(originalText) { mutableStateOf(currentText) }
    var offsetX by remember(originalText) { mutableFloatStateOf(initialOffsetXDp) }
    var offsetY by remember(originalText) { mutableFloatStateOf(initialOffsetYDp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_edit_bubble_title)) },
        text = {
            Column {
                Text(
                    text = originalText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.reader_edit_bubble_label)) },
                    supportingText = { Text(stringResource(R.string.reader_edit_bubble_hint)) },
                )
                // Rucni oprava vys resi JEDNU bublinu - tohle je pro pripad, kdy je spatne
                // vic bublin naraz (zacykleny/spatnojazycny model vystup, viz
                // TranslationMerge) a rucni oprava kazde zvlast by byla otravna. Stejny
                // long-press gesto, ktere uz otevrelo tenhle dialog (viz ReaderScreen.kt) -
                // zadny novy gesto navic.
                TextButton(onClick = onRetranslatePage, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.reader_retranslate_page))
                }

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.reader_bubble_position_label), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
                    BubblePositionPad(
                        offsetXDp = offsetX,
                        offsetYDp = offsetY,
                        onOffsetChange = { newX, newY -> offsetX = newX; offsetY = newY },
                    )
                    TextButton(onClick = { offsetX = 0f; offsetY = 0f }) {
                        Text(stringResource(R.string.reader_bubble_position_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, offsetX, offsetY) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Malý čtvercový "touchpad" pro ruční doladění pozice bubliny (viz
 * [com.haise.jiyu.translate.TranslatedBlock.offsetXDp]/`offsetYDp`) - tečka uprostřed = beze
 * změny, tažením se posune a hodnota se čte přímo jako Dp posun (1:1 s tažením, ořízlé na
 * [PAD_RADIUS_DP] na obě strany). Schválně UVNITŘ modálního dialogu, ne přímo tažením po
 * stránce - `AlertDialog` blokuje interakci s podkladem pod sebou, takže tažení "za bublinou"
 * na skutečné stránce by muselo řešit souběh s pinch-zoom/tap gesty
 * WebtoonReaderu/MangaReaderu (viz plán, položka 11 - přesně tenhle střet plán sám čeká).
 * Tenhle návrh se mu úplně vyhne za cenu, že chybí živý náhled bubliny při tažení - jen
 * relativní posun tečky v padu.
 */
@Composable
private fun BubblePositionPad(
    offsetXDp: Float,
    offsetYDp: Float,
    onOffsetChange: (offsetXDp: Float, offsetYDp: Float) -> Unit,
) {
    val density = LocalDensity.current
    val currentOffsetX = rememberUpdatedState(offsetXDp)
    val currentOffsetY = rememberUpdatedState(offsetYDp)
    Box(
        modifier = Modifier
            .size(PAD_SIZE_DP)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val dxDp = with(density) { dragAmount.x.toDp().value }
                    val dyDp = with(density) { dragAmount.y.toDp().value }
                    val newX = (currentOffsetX.value + dxDp).coerceIn(-PAD_RADIUS_DP, PAD_RADIUS_DP)
                    val newY = (currentOffsetY.value + dyDp).coerceIn(-PAD_RADIUS_DP, PAD_RADIUS_DP)
                    onOffsetChange(newX, newY)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .align(Alignment.Center)
                .offset(x = offsetXDp.dp, y = offsetYDp.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private val PAD_SIZE_DP = 96.dp
private const val PAD_RADIUS_DP = 48f

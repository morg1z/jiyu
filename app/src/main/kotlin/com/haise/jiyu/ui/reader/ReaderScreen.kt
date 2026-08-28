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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val pages               by viewModel.pages.collectAsState()
    val pageReferer         by viewModel.pageReferer.collectAsState()
    val comickUnavailable   by viewModel.comickUnavailable.collectAsState()
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
    val mangaTitle           by viewModel.mangaTitle.collectAsState()
    val mangaId              by viewModel.mangaId.collectAsState()
    val currentChapterId     by viewModel.currentChapterId.collectAsState()
    val sourceLanguage      by viewModel.sourceLanguage.collectAsState()
    val targetLanguage      by viewModel.targetLanguage.collectAsState()
    val tapZonesEnabled     by viewModel.tapZonesEnabled.collectAsState()
    val readerTextScale     by viewModel.readerTextScale.collectAsState()
    val doublePageSpread    by viewModel.doublePageSpread.collectAsState()
    val translationError    by viewModel.translationError.collectAsState()
    val fallbackNotice     by viewModel.fallbackNotice.collectAsState()
    val fullscreenEnabled   by viewModel.fullscreenEnabled.collectAsState()
    val readerTheme         by viewModel.readerTheme.collectAsState()
    val isOfflineChapter    by viewModel.isOfflineChapter.collectAsState()
    val chapterProgress     by viewModel.chapterProgress.collectAsState()
    val spreadPageIndices   by viewModel.spreadPageIndices.collectAsState()
    val sleepTimerRemaining  by viewModel.sleepTimerRemaining.collectAsState()
    val panelMode            by viewModel.panelMode.collectAsState()
    val oledMode             by viewModel.oledMode.collectAsState()
    val pageCurlEnabled      by viewModel.pageCurlEnabled.collectAsState()
    val curlStyle            by viewModel.curlStyle.collectAsState()
    val incognitoMode        by viewModel.incognitoMode.collectAsState()
    val sessionElapsed       by viewModel.sessionElapsed.collectAsState()
    val tapZoneGrid          by viewModel.tapZoneGrid.collectAsState()
    val webtoonScrollSpeed   by viewModel.webtoonScrollSpeed.collectAsState()
    val isNovelSource        by viewModel.isNovelSource.collectAsState()
    val isApiKeyConfigured = viewModel.isApiKeyConfigured
    val novelText            by viewModel.novelText.collectAsState()
    val novelTranslateMode   by viewModel.novelTranslateMode.collectAsState()
    val novelTranslatedText  by viewModel.novelTranslatedText.collectAsState()
    val novelTranslating     by viewModel.novelTranslating.collectAsState()
    val glossary             by viewModel.glossary.collectAsState()
    val chapterComments       by viewModel.chapterComments.collectAsState()
    val commentsLoading       by viewModel.commentsLoading.collectAsState()
    val commentsSupported     by viewModel.commentsSupported.collectAsState()
    val pageScale            by viewModel.pageScale.collectAsState()
    val jumpToPage           by viewModel.jumpToPage.collectAsState()
    val allChapters          by viewModel.allChaptersFlow.collectAsState()
    val autoNextChapter      by viewModel.autoNextChapter.collectAsState()
    val cropBorders          by viewModel.cropBorders.collectAsState()
    val webtoonScrollOffset  by viewModel.webtoonScrollOffset.collectAsState()
    val volumeKeysNav        by viewModel.volumeKeysNav.collectAsState()
    val keepScreenOn         by viewModel.keepScreenOn.collectAsState()
    val readerOrientation    by viewModel.readerOrientation.collectAsState()
    val controlsVisible      by viewModel.controlsVisible.collectAsState()
    val flippedBubbles       by viewModel.flippedBubbles.collectAsState()
    val webtoonSegments      by viewModel.webtoonSegments.collectAsState()

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    // Ručně opravovaná bublina: (index stránky, původní text, aktuální překlad). Původní text
    // je identita bubliny napříč přepočty - viz manualEditId.
    var bubbleEdit by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
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
                onAddGlossaryEntry = { source, target -> viewModel.addGlossaryEntry(source, target) },
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
                onAddGlossaryEntry = { source, target -> viewModel.addGlossaryEntry(source, target) },
                onRemoveGlossaryEntry = { viewModel.removeGlossaryEntry(it) },
                chapterComments = chapterComments,
                commentsLoading = commentsLoading,
                commentsSupported = commentsSupported,
                onShowComments = { viewModel.loadChapterComments() },
                flippedBubbles = flippedBubbles,
                onToggleBubbleFlip = { pageIndex, bubbleIndex -> viewModel.toggleBubbleFlip(pageIndex, bubbleIndex) },
                onEditBubble = { pageIndex, originalText, currentText ->
                    bubbleEdit = Triple(pageIndex, originalText, currentText)
                },
                onDeviceWarningText = if (!isApiKeyConfigured && translateMode) stringResource(R.string.reader_on_device_warning) else null,
                pageCurlEnabled = pageCurlEnabled,
                curlStyle = curlStyle,
                referer = pageReferer,
            )
        }

        bubbleEdit?.let { (pageIndex, originalText, currentText) ->
            BubbleEditDialog(
                originalText = originalText,
                currentText = currentText,
                onDismiss = { bubbleEdit = null },
                onSave = { newText ->
                    viewModel.saveBubbleEdit(pageIndex, originalText, newText)
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
@Composable
private fun BubbleEditDialog(
    originalText: String,
    currentText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(originalText) { mutableStateOf(currentText) }
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

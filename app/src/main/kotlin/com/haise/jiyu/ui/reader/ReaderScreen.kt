package com.haise.jiyu.ui.reader

import com.haise.jiyu.ui.components.JiyuLoadingIndicator

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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.haise.jiyu.util.findActivity
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
    val reverseLayout       by viewModel.reverseLayout.collectAsStateWithLifecycle()
    // Per-manga "Webtoon" override z detailu titulu - ReaderContent musi cist tuhle
    // kombinovanou hodnotu, ne surove settings.readingMode, jinak je override mrtvy
    // (audit RD-1).
    val isWebtoonMode       by viewModel.isWebtoonMode.collectAsStateWithLifecycle()
    val currentPage         by viewModel.currentPage.collectAsStateWithLifecycle()
    val hasPrevChapter      by viewModel.hasPrevChapter.collectAsStateWithLifecycle()
    val hasNextChapter      by viewModel.hasNextChapter.collectAsStateWithLifecycle()
    val chapterTitle        by viewModel.chapterTitle.collectAsStateWithLifecycle()
    val mangaTitle           by viewModel.mangaTitle.collectAsStateWithLifecycle()
    val mangaId              by viewModel.mangaId.collectAsStateWithLifecycle()
    val currentChapterId     by viewModel.currentChapterId.collectAsStateWithLifecycle()
    val tapZonesEnabled     by viewModel.tapZonesEnabled.collectAsStateWithLifecycle()
    val doublePageSpread    by viewModel.doublePageSpread.collectAsStateWithLifecycle()
    val comickParent        by viewModel.comickParent.collectAsStateWithLifecycle()
    val sourceSwitchRows    by viewModel.sourceSwitchRows.collectAsStateWithLifecycle()
    val sourceScanning      by viewModel.sourceScanning.collectAsStateWithLifecycle()
    val sourceSwitching     by viewModel.sourceSwitching.collectAsStateWithLifecycle()
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
    val novelText            by viewModel.novelText.collectAsStateWithLifecycle()
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
    val webtoonSegments      by viewModel.webtoonSegments.collectAsStateWithLifecycle()
    val webtoonAppending     by viewModel.webtoonAppendingNext.collectAsStateWithLifecycle()
    val webtoonPrepending    by viewModel.webtoonPrependingPrev.collectAsStateWithLifecycle()
    val webtoonEpoch         by viewModel.webtoonEpoch.collectAsStateWithLifecycle()
    // "Nekonecne cteni" - sdilene nastaveni webtoonu i paged rezimu (segmenty se
    // seskládaji na jeden plochy proud stranek, viz ReaderContent.useInfinitePaged).
    val infiniteScrollEnabled by viewModel.infiniteScrollEnabled.collectAsStateWithLifecycle()
    // Zbyvajici nastaveni pro sheet ctecky (ozubene kolo v horni liste).
    val readingMode          by viewModel.readingMode.collectAsStateWithLifecycle()
    val readingDirection     by viewModel.readingDirection.collectAsStateWithLifecycle()
    val mangaDirectionOverride by viewModel.mangaDirectionOverride.collectAsStateWithLifecycle()
    val skipReadChapters     by viewModel.skipReadChapters.collectAsStateWithLifecycle()

    var showSleepTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showReaderSettingsSheet by rememberSaveable { mutableStateOf(false) }

    // Otevreny sheet nastaveni ctecky drzi controls viditelne (stejny hold jako
    // prekladovy sheet ve spodni liste) - jinak by po 5s auto-hide schoval listy
    // i s otevrenym sheetem pod sebou.
    LaunchedEffect(showReaderSettingsSheet) {
        viewModel.onAdvancedSheetVisibilityChanged(showReaderSettingsSheet)
    }
    // Ručně opravovaná bublina: (index stránky, původní text, aktuální překlad). Původní text
    // je identita bubliny napříč přepočty - viz manualEditId.
    val activity = LocalView.current.context.findActivity()

    // Čtečku zavírá až tenhle sběratel, ne lambda předaná do časovače. Ta totiž putovala do
    // singletonu, který ji držel po celou dobu odpočtu i poté, co uživatel ze čtečky odešel -
    // a spolu s ní i celou Activity. Takhle je Activity potřeba jen ve chvíli, kdy odpočet
    // opravdu doběhne, a to už tady nikdo neposlouchá, pokud čtečka mezitím zmizela.
    LaunchedEffect(Unit) {
        viewModel.sleepTimerFinished.collect { activity?.finish() }
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
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val ctrl = WindowCompat.getInsetsController(window, view)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreenEnabled) ctrl.hide(WindowInsetsCompat.Type.systemBars())
        else ctrl.show(WindowInsetsCompat.Type.systemBars())
        onDispose { ctrl.hide(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(keepScreenOn) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(readerOrientation) {
        val act = view.context.findActivity() ?: return@DisposableEffect onDispose {}
        act.requestedOrientation = when (readerOrientation) {
            "portrait"  -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else        -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
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
                pageCurlEnabled = pageCurlEnabled,
                curlStyle = curlStyle,
                autoNextChapter = autoNextChapter,
                // Audit RD-6: obnoveni a persist postupu i u novel (drive se nikdy
                // nezapisoval lastPageRead/lastScrollOffset/read/historie).
                initialIndex = currentPage,
                initialScrollOffset = webtoonScrollOffset,
                onProgressChanged = { idx, off, total -> viewModel.onNovelProgressChanged(idx, off, total) },
            )
            comickUnavailable -> {
                // Uzivatel nemusi wall potvrzovat rucne - nevyresolvovana ComicK kapitola
                // se automaticky prehodi do vyberu zdroje (resolver sam otevre nejlepsi
                // kandidata; s resolvedMangaId na titulu jen overi zapamatovany zdroj).
                // Auto-pokus bezi jednou: navrat ze sweepu bez resolvovani (back) ukaze
                // wall s tlacitkem misto zacykleni navigace.
                var autoResolveAttempted by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!autoResolveAttempted) {
                        autoResolveAttempted = true
                        onFindSource()
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.detail_comick_read_unavailable),
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(onClick = onFindSource, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.reader_comick_find_source))
                    }
                }
            }
            pages.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.reader_chapter_load_failed), color = Color.White)
                // Akce podle typu chyby (Vyřešit ověření Cloudflare, nová adresa zdroje...).
                val chapterAction by viewModel.chapterErrorAction.collectAsStateWithLifecycle()
                val action = chapterAction
                if (action != null && action !is com.haise.jiyu.util.ErrorAction.OpenSourceWeb) {
                    Button(onClick = { viewModel.performChapterErrorAction() }, modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            when (action) {
                                is com.haise.jiyu.util.ErrorAction.SolveCloudflare ->
                                    stringResource(R.string.error_action_solve_cloudflare)
                                is com.haise.jiyu.util.ErrorAction.UseNewDomain ->
                                    stringResource(R.string.error_action_use_new_domain, action.host)
                                else -> ""
                            },
                        )
                    }
                }
            }
            else -> ReaderContent(
                pages = pages,
                // Seed = ziva pozice (currentPage), ne ulozene initialPage: pri prepnuti
                // reader modu (paged<->webtoon) se nova ctecka zkomponuje a ma chytit
                // aktualni stranku, ne stranku pri otevreni kapitoly (audit RD-3).
                // Pri prvnim otevreni kapitoly se hodnoty rovnaji (loadChapter je
                // nastavuje stejne), takze se tim nic nemeni.
                initialPage = currentPage,
                currentPage = currentPage,
                reverseLayout = reverseLayout,
                webtoonMode = isWebtoonMode,
                chapterTitle = chapterTitle,
                mangaTitle = mangaTitle,
                onOpenManga = { mangaId?.let(onOpenManga) },
                onNavigateHome = onNavigateHome,
                hasPrevChapter = hasPrevChapter,
                hasNextChapter = hasNextChapter,
                controlsVisible = controlsVisible,
                onToggleControlsVisible = { viewModel.toggleControlsVisible() },
                onPageChanged = { viewModel.onPageChanged(it) },
                onNavigatePrev = { viewModel.navigatePrev() },
                onNavigateNext = { viewModel.navigateNext() },
                tapZonesEnabled = tapZonesEnabled,
                tapZoneGrid = tapZoneGrid,
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
                onEdgeScrubActive = { viewModel.onEdgeScrubActive(it) },
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
                // Prepinac zdroju - jen u agregovaneho ComicK titulu (rodic
                // nalezeny); jinak null a tlacitko se neukaze.
                sourceSwitch = comickParent?.let {
                    SourceSwitchState(
                        rows = sourceSwitchRows,
                        scanning = sourceScanning,
                        switching = sourceSwitching,
                        onOpen = { viewModel.openSourceSwitcher() },
                        onSelect = { row -> viewModel.switchSource(row) },
                        onSheetVisibilityChange = { viewModel.onSourceSheetVisibilityChanged(it) },
                    )
                },
                webtoonSegments = webtoonSegments,
                onNeedMoreWebtoonSegments = { viewModel.appendNextWebtoonSegment() },
                // Zpetne nekonecne cteni - dolnatahnout PREDCHOZI kapitolu na zacatek
                // proudu (symetrie k appendu na konec).
                onNeedPrevWebtoonSegments = { viewModel.prependPreviousWebtoonSegment() },
                webtoonEpoch = webtoonEpoch,
                // Paged nekonecne cteni - plochy index hlaseny cteckou, mapovani
                // na (chapterId, lokalni) + dolnatizeni segmentu resi ViewModel.
                infiniteScrollEnabled = infiniteScrollEnabled,
                onPagedFlatPageChanged = { viewModel.onPagedFlatPageChanged(it) },
                onWebtoonVisibleChapterChanged = { id, localIndex, localOffset ->
                    viewModel.onWebtoonVisibleChapterChanged(id, localIndex, localOffset)
                },
                webtoonAppendingNextChapter = webtoonAppending,
                webtoonPrependingPrevChapter = webtoonPrepending,
                autoNextChapter = autoNextChapter,
                onAutoNextChapter = { viewModel.navigateNext() },
                cropBorders = cropBorders,
                webtoonScrollOffset = webtoonScrollOffset,
                volumeKeysNav = volumeKeysNav,
                readerOrientation = readerOrientation,
                onSetReaderOrientation = { viewModel.setReaderOrientation(it) },
                chapterComments = chapterComments,
                commentsLoading = commentsLoading,
                commentsSupported = commentsSupported,
                onShowComments = { viewModel.loadChapterComments() },
                onPagePermanentlyFailed = { chId, pageIndex -> viewModel.onPagePermanentlyFailed(chId, pageIndex) },
                pageCurlEnabled = pageCurlEnabled,
                curlStyle = curlStyle,
                referer = pageReferer,
                onOpenSettings = { showReaderSettingsSheet = true },
            )
        }

        // Sheet nastaveni ctecky - zije tady (ne v ReaderContent), protoze potrebuje
        // ViewModel kolekce i settery najednou a sheet musi prezit i schovane listy
        // (je mimo AnimatedVisibility controls, stejne jako sleep timer dialog).
        if (showReaderSettingsSheet) {
            ReaderSettingsSheet(
                mangaDirectionOverride = mangaDirectionOverride,
                onMangaDirectionChange = { viewModel.setMangaReaderDirection(it) },
                readingMode = readingMode,
                onReadingModeChange = { viewModel.setReadingMode(it) },
                readingDirection = readingDirection,
                onReadingDirectionChange = { viewModel.setReadingDirection(it) },
                pageScale = pageScale,
                onPageScaleChange = { viewModel.setPageScale(it) },
                doublePageSpread = doublePageSpread,
                onDoublePageSpreadChange = { viewModel.setDoublePageSpread(it) },
                cropBorders = cropBorders,
                onCropBordersChange = { viewModel.setCropBorders(it) },
                oledMode = oledMode,
                onOledModeChange = { viewModel.setOledMode(it) },
                fullscreenEnabled = fullscreenEnabled,
                onFullscreenChange = { viewModel.setFullscreenEnabled(it) },
                webtoonScrollSpeed = webtoonScrollSpeed,
                onWebtoonScrollSpeedChange = { viewModel.setWebtoonScrollSpeed(it) },
                tapZonesEnabled = tapZonesEnabled,
                onTapZonesChange = { viewModel.setTapZonesEnabled(it) },
                volumeKeysNav = volumeKeysNav,
                onVolumeKeysNavChange = { viewModel.setVolumeKeysNav(it) },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
                skipReadChapters = skipReadChapters,
                onSkipReadChaptersChange = { viewModel.setSkipReadChapters(it) },
                autoNextChapter = autoNextChapter,
                onAutoNextChapterChange = { viewModel.setAutoNextChapter(it) },
                infiniteScrollEnabled = infiniteScrollEnabled,
                onInfiniteScrollChange = { viewModel.setInfiniteScrollEnabled(it) },
                pageCurlEnabled = pageCurlEnabled,
                onPageCurlChange = { viewModel.setPageCurlEnabled(it) },
                curlStyle = curlStyle,
                onCurlStyleChange = { viewModel.setCurlStyle(it) },
                readerTheme = readerTheme,
                onReaderThemeChange = { viewModel.setReaderTheme(it) },
                onDismiss = { showReaderSettingsSheet = false },
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

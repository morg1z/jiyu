package com.haise.jiyu.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
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
private val OffsetSaver = Saver<Offset, List<Float>>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) },
)

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
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
) {
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
                Text(stringResource(R.string.reader_share_page_chooser), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
                OutlinedButton(
                    onClick = { onSharePage(sharePageUrl); showShareSheet = false },
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
                        val url = sharePageUrl
                        saveScope.launch { saveBitmapToGallery(saveContext, url) }
                        showShareSheet = false
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

    // Tracks the single page index across recompositions and spread-mode resets.
    // Lives OUTSIDE key(useSpread) so it survives the pager recreation and gives the
    // new pager its correct starting group.
    var currentSingleIndex by rememberSaveable { mutableStateOf(initialPage) }

    // Auto-advance to next chapter when reaching last page with autoNextChapter enabled.
    // reachedEndManually ensures we only trigger after navigating away from initial page,
    // preventing immediate jump when resuming on the last page.
    var reachedEndManually by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(currentSingleIndex, pages.size) {
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

        androidx.compose.runtime.LaunchedEffect(pagerState, groups) {
            snapshotFlow { pagerState.currentPage }.collect { groupIdx ->
                groups.getOrNull(groupIdx)?.firstOrNull()?.let {
                    currentSingleIndex = it
                    handlePageChanged(it)
                }
            }
        }

        androidx.compose.runtime.LaunchedEffect(jumpToPage) {
            val target = jumpToPage ?: return@LaunchedEffect
            val groupIdx = groups.indexOfFirst { target in it }.coerceAtLeast(0)
                .coerceIn(0, groups.lastIndex.coerceAtLeast(0))
            pagerState.animateScrollToPage(groupIdx)
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
                            scale = newScale
                            if (newScale > 1f) panOffset += pan
                            else panOffset = Offset.Zero
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
                                    scale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    val zoom = 2.5f
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    scale = zoom
                                    panOffset = Offset(
                                        (offset.x - cx) * (1f - zoom),
                                        (offset.y - cy) * (1f - zoom),
                                    )
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
                    var intrinsicSize by remember(pages[indices[0]]) { mutableStateOf<Size?>(null) }
                    val containerWidth = maxWidth
                    val containerHeight = maxHeight
                    // Aplikuje pinch/double-tap transformaci na celou stránku najednou
                    // (obrázek + překladové bubliny), aby bubliny zůstaly na správném
                    // místě při zoomu, místo toho, aby zůstávaly na původní pozici.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = panOffset.x,
                                translationY = panOffset.y,
                            ),
                    ) {
                        RetryableAsyncImage(
                            url = pages[indices[0]],
                            contentDescription = stringResource(R.string.reader_page_content_desc, indices[0] + 1),
                            contentScale = resolvedContentScale,
                            cropBorders = cropBorders,
                            modifier = Modifier.fillMaxSize(),
                            onImageSize = { intrinsicSize = it },
                        )
                        if (translateMode) {
                            val blocks = translatedPages[indices[0]]
                            if (!blocks.isNullOrEmpty()) {
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
                                    flippedBubbles = flippedBubbles,
                                    onToggleFlip = onToggleBubbleFlip,
                                    onEditBubble = onEditBubble,
                                )
                            }
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
                                var pageIntrinsicSize by remember(pages[idx]) { mutableStateOf<Size?>(null) }
                                RetryableAsyncImage(
                                    url = pages[idx],
                                    contentDescription = stringResource(R.string.reader_page_content_desc, idx + 1),
                                    contentScale = resolvedContentScale,
                                    modifier = Modifier.fillMaxSize(),
                                    onImageSize = { pageIntrinsicSize = it },
                                )
                                if (translateMode) {
                                    val blocks = translatedPages[idx]
                                    if (!blocks.isNullOrEmpty()) {
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
        }
    }
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
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
            android.os.Environment.DIRECTORY_PICTURES + "/Jiyu")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
    }
    val updateValues = android.content.ContentValues()
    updateValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, updateValues, null, null)
}

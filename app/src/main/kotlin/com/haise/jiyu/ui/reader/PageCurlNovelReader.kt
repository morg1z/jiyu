package com.haise.jiyu.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Novel čtečka s efektem ohýbané stránky ("page curl") - propojuje paginaci (Task 2), stav
 * ohybu (Task 3), geometrii ohybu (Task 4) a vykreslení (Task 5) do jednoho composable.
 * Vypnutelná per-uživatel přes `pageCurlEnabled` toggle v [NovelContent] - výchozí `LazyColumn`
 * zůstává nedotčená, viz podmíněná větev tam.
 */
@Composable
fun PageCurlNovelReader(
    text: String,
    fontSize: Float,
    lineSpacing: Float,
    textColor: Color,
    bgColor: Color,
    onChapterBoundary: (TurnDirection) -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(bgColor)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val baseStyle = TextStyle(color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
        val layoutProvider = remember(textMeasurer) { ComposeTextLayoutProvider(textMeasurer, baseStyle) }

        var pages by remember { mutableStateOf(listOf(NovelPage(0, 0))) }
        var curlState by remember { mutableStateOf(PageCurlState(0, 1)) }

        LaunchedEffect(text, fontSize, lineSpacing, widthPx, heightPx) {
            val previousOffset = pages.getOrNull(curlState.currentPageIndex)?.startIndex ?: 0
            val newPages = paginateNovelText(text, layoutProvider, widthPx, heightPx, fontSize)
            val newIndex = findPageIndexForOffset(newPages, previousOffset)
            pages = newPages
            curlState = PageCurlState(currentPageIndex = newIndex, pageCount = newPages.size)
        }

        val currentPage = pages[curlState.currentPageIndex.coerceIn(pages.indices)]
        val currentPageText = text.substring(currentPage.startIndex, currentPage.endIndex)

        val currentLayer = rememberGraphicsLayer()
        var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    currentLayer.record { this@drawWithContent.drawContent() }
                    drawContent()
                }
                .padding(20.dp),
        ) {
            Text(text = currentPageText, color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
        }

        LaunchedEffect(currentPage, fontSize, lineSpacing, textColor, widthPx, heightPx) {
            currentBitmap = currentLayer.toImageBitmap()
        }

        // Sousední stránka, kterou tah odkrývá pod ohybem - NEXT při kladném dragProgress,
        // PREV při záporném. Rasterizuje se do vlastní vrstvy, ale NEKRESLÍ se přímo na
        // obrazovku (chybí koncové `drawContent()`) - jinak by prosvítala i v klidu (dragProgress
        // == 0f), překrytá přes aktuální stránku.
        val revealedPageIndex = when {
            curlState.dragProgress > 0f -> curlState.currentPageIndex + 1
            curlState.dragProgress < 0f -> curlState.currentPageIndex - 1
            else -> null
        }
        val revealedPage = revealedPageIndex?.let { pages.getOrNull(it) }
        val revealedLayer = rememberGraphicsLayer()
        var revealedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    revealedLayer.record { this@drawWithContent.drawContent() }
                    // Zámerně BEZ drawContent() - tahle vrstva se jen rasterizuje pro
                    // revealedBitmap, na obrazovku samu o sobě nekreslí nic.
                }
                .padding(20.dp),
        ) {
            if (revealedPage != null) {
                val revealedText = text.substring(revealedPage.startIndex, revealedPage.endIndex)
                Text(text = revealedText, color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
            }
        }

        LaunchedEffect(revealedPage, fontSize, lineSpacing, textColor, widthPx, heightPx) {
            revealedBitmap = if (revealedPage != null) revealedLayer.toImageBitmap() else null
        }

        fun applyTurnResult(result: PageTurnResult) {
            when (result) {
                is PageTurnResult.WithinChapter -> curlState = result.newState
                is PageTurnResult.Cancelled -> curlState = result.newState
                is PageTurnResult.ChapterBoundary -> {
                    curlState = curlState.copy(dragProgress = 0f)
                    onChapterBoundary(result.direction)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(curlState.pageCount) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val deltaProgress = dragAmount.x / widthPx
                            curlState = curlState.withDrag(curlState.dragProgress + deltaProgress)
                        },
                        onDragEnd = { applyTurnResult(curlState.onDragEnd()) },
                    )
                }
                .pointerInput(curlState.pageCount) {
                    detectTapGestures(
                        onTap = { offset ->
                            val direction = when {
                                offset.x < widthPx * 0.15f -> TurnDirection.PREV
                                offset.x > widthPx * 0.85f -> TurnDirection.NEXT
                                else -> null
                            }
                            direction?.let { applyTurnResult(curlState.onEdgeTap(it)) }
                        },
                    )
                },
        ) {
            val bitmap = currentBitmap
            if (bitmap != null && curlState.dragProgress != 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val corner = if (curlState.dragProgress > 0f) Offset(widthPx, heightPx) else Offset(0f, heightPx)
                    val fingerOffset = Offset(
                        x = corner.x - curlState.dragProgress * widthPx,
                        y = corner.y,
                    )
                    val geometry = computePageCurlGeometry(
                        corner = Point(corner.x, corner.y),
                        dragPoint = Point(fingerOffset.x, fingerOffset.y),
                        pageWidth = widthPx, pageHeight = heightPx,
                    )
                    drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
            val percent = (curlState.currentPageIndex + 1) * 100 / pages.size.coerceAtLeast(1)
            Text(
                text = "Stránka ${curlState.currentPageIndex + 1} z ${pages.size} · $percent%",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}

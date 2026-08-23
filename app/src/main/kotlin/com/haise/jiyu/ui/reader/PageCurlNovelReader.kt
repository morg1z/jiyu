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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R

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
    curlStyle: String = com.haise.jiyu.settings.CurlStyleSetting.CLASSIC,
) {
    val resolvedCurlStyle = if (curlStyle == com.haise.jiyu.settings.CurlStyleSetting.ROLL) CurlStyle.ROLL else CurlStyle.CLASSIC
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(bgColor)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Znovu vytvořeno pokaždé, když se změní cokoliv, co ovlivňuje zalomení textu (fix
        // review nálezu č. 2) - dřív byl klíč jen `textMeasurer` (ten je sám o sobě
        // memoizovaný přes celý životní cyklus obrazovky), takže `layoutProvider` i jeho
        // zamrzlý `lineHeight` vznikly jen JEDNOU a další změna velikosti písma/řádkování
        // uživatelem v NovelContent je nikdy nedostihla - paginace pak počítala s jiným
        // řádkováním, než jaké se skutečně vykreslovalo.
        val layoutProvider = remember(textMeasurer, fontSize, lineSpacing, textColor) {
            val baseStyle = TextStyle(color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
            ComposeTextLayoutProvider(textMeasurer, baseStyle)
        }

        // Stránky se počítají SYNCHRONNĚ v tomtéž composition průchodu jako `text` (fix review
        // nálezu č. 1) - dřív se přepočítávaly až v `LaunchedEffect`, tedy o krok pozadu. Při
        // přechodu na jinou kapitolu (jiná délka textu) se stará `pages`/`curlState` použily
        // proti NOVÉMU `text` ještě předtím, než `LaunchedEffect` doběhl, a `text.substring(...)`
        // mohl spadnout s `StringIndexOutOfBoundsException`. Teď je `pages` vždy odvozeno přímo
        // z aktuálního `text` v rámci jednoho `remember` - nemůže nikdy patřit jinému textu.
        val pages = remember(text, fontSize, lineSpacing, widthPx, heightPx, layoutProvider) {
            paginateNovelText(text, layoutProvider, widthPx, heightPx, fontSize)
        }

        // Pozice čtenáře jako znakový offset do `text`, ne jako index stránky - přežije
        // repaginaci (zmena fontu/řádkování v RÁMCI stejné kapitoly) přes
        // `findPageIndexForOffset`, a resetuje se na 0 pokaždé, když se `text` sám změní
        // (přechod na jinou kapitolu) díky klíči `remember(text)` - nemůže tak nikdy zůstat
        // ukazovat na offset z PŘEDCHOZÍ kapitoly nad stránkami nově napaginovanými z textu
        // kapitoly aktuální.
        var readingOffset by remember(text) { mutableStateOf(0) }
        var dragProgress by remember(text) { mutableStateOf(0f) }
        // Fix regrese po Critical 1 - `PageCurlState.onDragEnd()` teď spravne cte
        // `rawDragProgress` (nezaclampovany pokus o smer), ale ta hodnota se musi
        // persistovat STEJNE jako `dragProgress`, jinak by pri kazde konstrukci
        // `PageCurlState(...)` defaultovala na 0f a `onDragEnd()` by VZDY vratil
        // `Cancelled` bez ohledu na skutecny tah - otaceni tahem by bylo kompletne
        // nefunkcni (tap zony/onEdgeTap by dal fungovaly, protoze nejdou pres tohle).
        var rawDragProgress by remember(text) { mutableStateOf(0f) }

        val currentPageIndex = findPageIndexForOffset(pages, readingOffset)
        val currentPage = pages[currentPageIndex.coerceIn(pages.indices)]
        // Bezpečnostní pojistka navíc (i když `pages` je vždy odvozeno ze stejného `text`,
        // který se tu čte) - substring nikdy nespadne, i kdyby se výše uvedená synchronizace
        // v budoucnu narušila.
        val currentSafeStart = currentPage.startIndex.coerceIn(0, text.length)
        val currentSafeEnd = currentPage.endIndex.coerceIn(currentSafeStart, text.length)
        val currentPageText = text.substring(currentSafeStart, currentSafeEnd)

        val currentLayer = rememberGraphicsLayer()
        var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    // Fix Important 7 - neprumyslne pozadi PRED zaznamem obsahu vrstvy.
                    // Bez tohohle by rasterizovana bitmapa mela pruhledne pozadi (Text sam o
                    // sobe nekresli pozadi, jen `.background(bgColor)` na vnejsim
                    // BoxWithConstraints, ktere ale neni soucasti teto zaznamenane vrstvy) a
                    // curl overlay by skrz tyto pruhledne mezery prosvital zivou textovou
                    // vrstvu vykreslenou pod nim - dvoji expozice (ghosting) textu v ohnute
                    // oblasti.
                    currentLayer.record {
                        drawRect(bgColor)
                        this@drawWithContent.drawContent()
                    }
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
            dragProgress > 0f -> currentPageIndex + 1
            dragProgress < 0f -> currentPageIndex - 1
            else -> null
        }
        val revealedPage = revealedPageIndex?.let { pages.getOrNull(it) }
        val revealedLayer = rememberGraphicsLayer()
        var revealedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    // Fix Important 7 - stejne neprumyslne pozadi jako u `currentLayer` výše;
                    // tahle vrstva je "sousední" stránka odkrývaná pod ohybem, takže její
                    // bitmapa musí být stejně neprůhledná, jinak by curl overlay prosvítal
                    // zivou textovou vrstvu aktuální stránky vykreslenou pod ní.
                    revealedLayer.record {
                        drawRect(bgColor)
                        this@drawWithContent.drawContent()
                    }
                    // Zámerně BEZ drawContent() - tahle vrstva se jen rasterizuje pro
                    // revealedBitmap, na obrazovku samu o sobě nekreslí nic.
                }
                .padding(20.dp)
                .clearAndSetSemantics { },
        ) {
            if (revealedPage != null) {
                val revealedSafeStart = revealedPage.startIndex.coerceIn(0, text.length)
                val revealedSafeEnd = revealedPage.endIndex.coerceIn(revealedSafeStart, text.length)
                val revealedText = text.substring(revealedSafeStart, revealedSafeEnd)
                Text(text = revealedText, color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
            }
        }

        LaunchedEffect(revealedPage, fontSize, lineSpacing, textColor, widthPx, heightPx) {
            revealedBitmap = if (revealedPage != null) revealedLayer.toImageBitmap() else null
        }

        fun applyTurnResult(result: PageTurnResult) {
            when (result) {
                is PageTurnResult.WithinChapter -> {
                    dragProgress = result.newState.dragProgress
                    rawDragProgress = result.newState.rawDragProgress
                    readingOffset = pages.getOrNull(result.newState.currentPageIndex)?.startIndex ?: 0
                }
                is PageTurnResult.Cancelled -> {
                    dragProgress = result.newState.dragProgress
                    rawDragProgress = result.newState.rawDragProgress
                }
                is PageTurnResult.ChapterBoundary -> {
                    dragProgress = 0f
                    rawDragProgress = 0f
                    onChapterBoundary(result.direction)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Klíčováno i na `text`, ne jen na `pages.size` - jinak by při přechodu na
                // kapitolu se STEJNÝM počtem stránek (běžné u podobně dlouhých kapitol)
                // `pointerInput` nerestartoval a gesta by dál čítala/zapisovala do starých,
                // teď už osiřelých `readingOffset`/`dragProgress` MutableState objektů
                // zpřed přechodu (nový pár alokoval `remember(text)` výše, ale tahle
                // korutina by ho nikdy neviděla) - navigace by tiše přestala reagovat.
                .pointerInput(text, pages.size) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Živě sestrojeno z aktuálních hodnot `readingOffset`/`dragProgress`
                            // při KAŽDÉM volání (ne z jednou zachyceného `val` z předchozí
                            // kompozice) - stejný princip jako předtím `var curlState by
                            // remember`, jen rozložený na dva primitivy kvůli fixu nálezu č. 1.
                            val liveState = PageCurlState(
                                currentPageIndex = findPageIndexForOffset(pages, readingOffset),
                                pageCount = pages.size,
                                dragProgress = dragProgress,
                                rawDragProgress = rawDragProgress,
                            )
                            // Znamenko obraceno oproti puvodni verzi (fix Critical 2) - musi
                            // odpovidat konvenci v `MangaPageCurlReader.kt` (`dragProgress -
                            // delta`, ne `+ delta`) a tomu, co ocekava sdileny `drawPageCurl`
                            // renderer. Puvodni `+ deltaProgress` otacelo stranku opacnym
                            // smerem, nez odpovidalo fyzickemu tahu prstu.
                            val deltaProgress = dragAmount.x / widthPx
                            val updated = liveState.withDrag(liveState.dragProgress - deltaProgress)
                            dragProgress = updated.dragProgress
                            // Fix regrese po Critical 1 - `rawDragProgress` z vysledku `withDrag`
                            // se musi ulozit zpet do persistovaneho state, jinak by pri pusteni
                            // prstu `onDragEnd()` cetl porad jen defaultni 0f.
                            rawDragProgress = updated.rawDragProgress
                        },
                        onDragEnd = {
                            val liveState = PageCurlState(
                                currentPageIndex = findPageIndexForOffset(pages, readingOffset),
                                pageCount = pages.size,
                                dragProgress = dragProgress,
                                rawDragProgress = rawDragProgress,
                            )
                            applyTurnResult(liveState.onDragEnd())
                        },
                        onDragCancel = {
                            // Fix Important 5 - kdyz je gesture node zrusen uprostred tahu
                            // (napr. system gesto/jiny gesture-node prevezme ukazatel), curl
                            // by jinak zustal trvale "zamrzly" na posledni hodnote dragProgress.
                            dragProgress = 0f
                            rawDragProgress = 0f
                        },
                    )
                }
                .pointerInput(text, pages.size) {
                    detectTapGestures(
                        onTap = { offset ->
                            val direction = when {
                                offset.x < widthPx * 0.15f -> TurnDirection.PREV
                                offset.x > widthPx * 0.85f -> TurnDirection.NEXT
                                else -> null
                            }
                            direction?.let {
                                val liveState = PageCurlState(
                                    currentPageIndex = findPageIndexForOffset(pages, readingOffset),
                                    pageCount = pages.size,
                                    dragProgress = dragProgress,
                                    rawDragProgress = rawDragProgress,
                                )
                                applyTurnResult(liveState.onEdgeTap(it))
                            }
                        },
                    )
                },
        ) {
            val bitmap = currentBitmap
            if (bitmap != null && dragProgress != 0f) {
                if (resolvedCurlStyle == CurlStyle.ROLL) {
                    // Port karacken.curl (OpenGL) knihovny - viz GLPageCurlView. Novel čtečka
                    // drží jen jednu "revealedBitmap" vrstvu (ne trvale next+prev jako manga),
                    // takže se posílá jako next/prev podle směru - druhá zůstane null (v tom
                    // směru je stejně skrytá za neprůhlednou aktivní/statickou stránkou).
                    com.haise.jiyu.ui.reader.glcurl.GLPageCurlView(
                        currentBitmap = bitmap,
                        prevBitmap = if (dragProgress < 0f) revealedBitmap else null,
                        nextBitmap = if (dragProgress > 0f) revealedBitmap else null,
                        forward = dragProgress > 0f,
                        progress = kotlin.math.abs(dragProgress),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val geometry = computePageCurlGeometry(
                            pageWidth = widthPx, pageHeight = heightPx,
                            turningFromRight = dragProgress > 0f,
                            progress = kotlin.math.abs(dragProgress),
                            style = resolvedCurlStyle,
                        )
                        drawPageCurl(geometry = geometry, currentPageBitmap = bitmap, revealedPageBitmap = revealedBitmap)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
            val percent = (currentPageIndex + 1) * 100 / pages.size.coerceAtLeast(1)
            Text(
                text = stringResource(R.string.reader_page_curl_progress, currentPageIndex + 1, pages.size, percent),
                color = textColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}

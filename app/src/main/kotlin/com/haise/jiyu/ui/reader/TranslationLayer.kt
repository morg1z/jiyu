package com.haise.jiyu.ui.reader

import com.haise.jiyu.BuildConfig
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.translate.BubbleOverlayFix
import com.haise.jiyu.translate.BubbleShapePoint
import com.haise.jiyu.translate.BubbleType
import com.haise.jiyu.translate.LineMetrics
import com.haise.jiyu.translate.PositionedTranslationBlock
import com.haise.jiyu.translate.TextMeasurement
import com.haise.jiyu.translate.TranslatedBlock
import com.haise.jiyu.translate.averageArgb
import com.haise.jiyu.translate.bubbleSkipReason
import com.haise.jiyu.translate.estimateNativeFontPx
import com.haise.jiyu.translate.fitFontSizeToBox
import com.haise.jiyu.translate.hasLeakedToken
import com.haise.jiyu.translate.hasTranslatableLetters
import com.haise.jiyu.translate.isMidWordBreak
import com.haise.jiyu.translate.fitFixedLinesToShape
import com.haise.jiyu.translate.fitTextToShape
import com.haise.jiyu.translate.isSuspiciouslyTinyBubbleBox
import com.haise.jiyu.translate.largestInscribedRect
import com.haise.jiyu.translate.layoutTranslationBlocks
import com.haise.jiyu.translate.longestIndivisibleRunWidthPx
import com.haise.jiyu.translate.matchOriginalCase
import com.haise.jiyu.translate.minTranslationFontSp
import com.haise.jiyu.translate.patchMeanArgb
import com.haise.jiyu.translate.PatchRect
import com.haise.jiyu.translate.renderBoxRect
import dagger.hilt.android.EntryPointAccessors
import com.haise.jiyu.translate.snapBubbleBg
import com.haise.jiyu.translate.tidyFrenchStyleSpacing
import com.haise.jiyu.translate.tidyStrandedPunctuation
import com.haise.jiyu.translate.TranslationDiagnostics
import com.haise.jiyu.translate.truncateToFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Translation overlay - sdíleno mezi MangaReader (ReaderPager.kt) a WebtoonReader.kt ──
//
// Dřív měl WebtoonPage vlastní, skoro řádek-po-řádku duplicitní kopii týhle logiky (jiná
// souřadnicová soustava - měřený `size: IntSize` místo `imageRect: Rect` - ale stejný bleed/
// clip-shape/snap-bg/AutoFitTranslatedText postup). Riziko: oprava (a na tomhle kódu se ladí
// často, viz docs/superpowers/specs/2026-07-24-bubble-shape-and-font-design.md) by se snadno
// aplikovala jen na jednu kopii. Teď obě cesty volají [TranslationOverlay]/[BubbleOverlayLayer].

/** Malý přesah kolem přeloženého boxu, aby nikde neprosvítal kousek originálu za okrajem OCR boxu. */
private val TRANSLATION_BOX_BLEED = 2.dp

/**
 * Plně neprůhledné - jakákoli průhlednost nechá prosvítat "ducha" originálu pod výplní
 * (viz zpětná vazba uživatele: i 2 % průhlednosti dělalo viditelný šedý závoj se stínem
 * původního textu). Reference (clean scanlation appky) mají výplň 100% krycí.
 */
private const val TRANSLATION_BOX_ALPHA = 1.0f

/** Horizontální padding uvnitř přeloženého boxu - sdíleno mezi voláním `.padding(horizontal = ...)` a [AutoFitTranslatedText], aby fitter měřil text proti stejné šířce, jakou Text ve skutečnosti dostane. */
private val TRANSLATION_TEXT_HORIZONTAL_PADDING = 4.dp

/** Svislý padding uvnitř přeloženého boxu - text nesmí sahat až na horní/dolní okraj, jinak ho obrys bubliny ořízne. */
private val TRANSLATION_TEXT_VERTICAL_PADDING = 2.dp

/** O kolik dovnitř se ořízne clip obrysu bubliny - viz [BubbleClipShape]. Chrání tenký černý okraj balónku před přepsáním výplní. */
private val BUBBLE_CLIP_INSET = 2.dp

/**
 * Fragment coverOnly tak blízko okraje stránky (<4 %) leží na řezu sliců - krytí se
 * dotáhne až k okraji (viz `box` v [TranslationOverlay]), aby na řezu nezůstal nekrytý
 * proužek původního textu.
 */
private const val SEAM_EDGE_FRACTION = 0.04f

/**
 * Jak velký podíl vepsaného obdélníku (viz [largestInscribedRect]) se skutečně použije na text.
 * Obrys bubliny bývá nakreslený znatelně tlustou linkou a text nalepený těsně na ni vypadá
 * špatně, i když technicky nepřetéká - tenhle odstup dělá výsledek vizuálně podobný tomu, jak
 * sází text skutečný lettering v originále.
 */
private const val INSCRIBED_TEXT_AREA_FACTOR = 0.92f

/**
 * Skutečně vykreslený obdélník obrázku uvnitř Boxu dané velikosti, podle stejné logiky,
 * jakou používá Coil/Compose Image k vykreslení Painteru (viz [ContentScale.computeScaleFactor]
 * + výchozí [Alignment.Center]). Bez tohohle by se OCR frakce (0..1, vztažené ke skutečným
 * pixelům staženého obrázku) mapovaly na `containerSize` Boxu - ten je ale typicky
 * `fillMaxSize()` přes celou obrazovku, zatímco obrázek (kromě ContentScale.FillBounds/Crop)
 * uvnitř něj sedí menší a vycentrovaný (letterbox mezery nahoře/dole nebo po stranách) -
 * bubliny by pak driftovaly tím víc, čím dál od středu stránky, přesně jak hlásil uživatel
 * (překlady mimo originální bubliny) - viz docs/superpowers/specs/2026-07-24-bubble-shape-and-font-design.md.
 *
 * Vrácené hodnoty jsou v "Dp-value" jednotkách (Float bez skutečné density konverze) -
 * [ContentScale.computeScaleFactor] pracuje jen s poměry stran, takže smíchání pixelů
 * (intrinsicSizePx) a Dp hodnot (containerSizeDp) je bezpečné, dokud se použije konzistentně.
 */
fun imageDisplayRect(intrinsicSizePx: Size, containerSizeDp: Size, contentScale: ContentScale): Rect {
    if (intrinsicSizePx.width <= 0f || intrinsicSizePx.height <= 0f ||
        containerSizeDp.width <= 0f || containerSizeDp.height <= 0f
    ) {
        return Rect(Offset.Zero, containerSizeDp)
    }
    val scaleFactor = contentScale.computeScaleFactor(intrinsicSizePx, containerSizeDp)
    val scaledWidth = intrinsicSizePx.width * scaleFactor.scaleX
    val scaledHeight = intrinsicSizePx.height * scaleFactor.scaleY
    val offsetX = (containerSizeDp.width - scaledWidth) / 2f
    val offsetY = (containerSizeDp.height - scaledHeight) / 2f
    return Rect(Offset(offsetX, offsetY), Size(scaledWidth, scaledHeight))
}

/**
 * Smí se bublinový overlay vůbec vykreslit? Musí existovat přeložené bloky A stránka pod
 * nimi musí být VIDĚT (úspěšně načtená bitmapa, ne Loading/Error stav Coilu) - jinak text
 * plave nad prázdným/rozbitým místem, kam nepatří (nahlášeno: timeout načtení stránky
 * nechal viset osamocenou přeloženou bublinu na bílé ploše, zatímco zbytek kresby chyběl).
 * Volající si drží `imageLoaded`/`loadedFlags[i]` z [RetryableAsyncImage.onLoadedChange].
 */
internal fun shouldShowTranslationOverlay(hasBlocks: Boolean, imageLoaded: Boolean): Boolean =
    hasBlocks && imageLoaded

/**
 * Vrstva všech (ne-SFX) přeložených bublin jedné stránky - jediné místo, odkud se volá
 * [TranslationOverlay], ať pro manga (MangaReader v ReaderPager.kt) nebo webtoon
 * (WebtoonPage ve WebtoonReader.kt) mód.
 *
 * @param pageIndex potřeba jen pro sestavení klíče "$pageIndex:$bubbleIndex" v [flippedBubbles]
 *   (viz ReaderViewModel.toggleBubbleFlip) - bubbleIndex je pozice bubliny v [positioned], ne
 *   v původním (nefiltrovaném) `blocks`.
 */
private val CropFractions.spanX get() = (1f - leftF - rightF).coerceAtLeast(0.01f)
private val CropFractions.spanY get() = (1f - topF - bottomF).coerceAtLeast(0.01f)
private fun CropFractions.remapX(f: Float) = ((f - leftF) / spanX).coerceIn(0f, 1f)
private fun CropFractions.remapY(f: Float) = ((f - topF) / spanY).coerceIn(0f, 1f)

/** Obrys bubliny (z OCR i znovunalezený při vykreslení) je vždy v prostoru NEOŘÍZLÉ bitmapy. */
private fun List<BubbleShapePoint>.remapForCrop(crop: CropFractions): List<BubbleShapePoint> =
    map { it.copy(yF = crop.remapY(it.yF), leftF = crop.remapX(it.leftF), rightF = crop.remapX(it.rightF)) }

/** Přemapuje frakce bloku (a jeho shape, pokud existuje) z prostoru "celý originál" do prostoru
 * "obrázek po CropBordersTransformation" - viz [CropBordersTransformation.cropFractionsFor]
 * a komentář u [BubbleOverlayLayer]. */
private fun TranslatedBlock.remapForCrop(crop: CropFractions): TranslatedBlock = copy(
    leftF = crop.remapX(leftF),
    rightF = crop.remapX(rightF),
    topF = crop.remapY(topF),
    bottomF = crop.remapY(bottomF),
    shape = shape?.remapForCrop(crop),
)

@Composable
fun BubbleOverlayLayer(
    blocks: List<TranslatedBlock>,
    imageRect: Rect,
    textScale: Float = 1f,
    pageIndex: Int = -1,
    /** URL zobrazované stránky - potřeba jen pro záplaty (viz [TextPatchProvider]). */
    pageUrl: String? = null,
    /** Je zapnuté "Oříznout okraje"? Bez tohohle by OCR frakce (změřené vůči PŮVODNÍMU
     * obrázku, viz [PageBitmapLoader]) neseděly na `imageRect` (spočítaný z už OŘÍZNUTÉHO
     * `intrinsicSize`) - systematický posun bublin, nahlášeno v auditu. */
    cropBorders: Boolean = false,
    flippedBubbles: Set<String> = emptySet(),
    onToggleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String, offsetXDp: Float, offsetYDp: Float) -> Unit = { _, _, _, _, _ -> },
) {
    // Bloky se remapuji na prostor UZ OŘÍZNUTÉHO obrázku PŘED layoutem - tak se stejná
    // korekce automaticky projeví i do heuristické expanze (TranslationLayout.kt) a do
    // shape bodů, misto aby se muselo opravovat kazde pouziti frakci zvlast.
    val crop = remember(cropBorders, pageUrl) {
        if (cropBorders && pageUrl != null) CropBordersTransformation.cropFractionsFor(pageUrl) else null
    }
    val adjustedBlocks = remember(blocks, crop) {
        if (crop == null || (crop.leftF == 0f && crop.topF == 0f && crop.rightF == 0f && crop.bottomF == 0f)) {
            blocks
        } else {
            blocks.map { it.remapForCrop(crop) }
        }
    }
    val positioned = remember(adjustedBlocks) { layoutTranslationBlocks(adjustedBlocks) }
    // Záplaty (viz níž) se řežou přímo z PIXELŮ stránky - ale [PageBitmapLoader] (na rozdíl
    // od zobrazovací cesty) crop okrajů nikdy neaplikuje, takže bitmapa, ze které se řeže, je
    // vždy ta PŮVODNÍ, neořízlá. `positioned` výš je ale přemapovaný na ořízlý prostor kvůli
    // zobrazení - kdyby se stejný (přemapovaný) seznam použil i tady, záplata by se vyřízla
    // ze ŠPATNÝCH pixelů. Pozice i-tého prvku v obou seznamech ale NENÍ stabilní identita:
    // remapForCrop sice nemění "shape == null" flag přímo, ale clamp souřadnic může
    // překlapnout verdikt `isDegenerateShapeForText`/`mergeUntranslatedSiblingBlocks` a
    // změnit tak rozdělení shapeBased/heuristicBased - tentýž blok pak v `positioned` a
    // `originalPositioned` stojí na jiném indexu (audit F13). Proto se fixy klíčují přes
    // `PositionedTranslationBlock.sourceIndex` = pozice v `blocks`, shodná v obou layotech.
    val originalPositioned = remember(blocks) { layoutTranslationBlocks(blocks) }

    // Záplaty se počítají až tady, při zobrazení, a žijí jen v paměti - do Room nic nepřibývá,
    // takže se kvůli nim nemusela zvedat PIPELINE_VERSION a hotové překlady zůstaly platné.
    // Provider se bere přes Hilt EntryPoint: BubbleOverlayLayer volají dvě různé čtečky
    // (MangaReader i WebtoonPage) a protahovat ho parametrem přes celý strom by znamenalo
    // měnit podpisy několika composable jen kvůli tomuhle.
    val context = LocalContext.current
    val patchProvider = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TextPatchEntryPoint::class.java,
        ).textPatchProvider()
    }
    val fixes by produceState(initialValue = emptyMap<Int, BubbleOverlayFix>(), pageUrl, blocks) {
        val url = pageUrl
        value = if (url == null) {
            emptyMap()
        } else {
            val result = patchProvider.patchesFor(url, originalPositioned)
            // Autoritativní záznam "jak se stránka doopravdy vykreslila" - na rozdíl od
            // kind="page" diagnostiky překladu tady známe výsledky záplat a recovery,
            // takže skip=módy odpovídají skutečné obrazovce (viz TranslationDiagnostics).
            withContext(Dispatchers.IO) {
                TranslationDiagnostics.recordRender(context, url, pageIndex, originalPositioned, result)
            }
            // Klíče převedeme z pozice v `originalPositioned` na sourceIndex - renderer
            // níže hledá přes `fixes[pos.sourceIndex]` (pos pochází z `positioned`,
            // oříznutý layout). -1 se nikdy netrefí a je bezpečný.
            result.mapKeys { (i, _) -> originalPositioned[i].sourceIndex }
        }
    }
    // Vlastní font uživatele (viz CustomFontRepository, item 15) - stejný EntryPoint důvod
    // jako u patchProvider výš. Null = žádný nastavený/stažený, render zůstává na vestavěné
    // sadě Comic Neue/Exo2 (viz fontFamilyFor).
    val customFontRepository = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CustomFontEntryPoint::class.java,
        ).customFontRepository()
    }
    val customFontFile by customFontRepository.activeFontFile.collectAsStateWithLifecycle(initialValue = null)
    positioned.forEach { pos ->
        // Stabilní identita přes re-layout: pozice v `blocks`, ne v `positioned`
        // (výstup je přerovnaný - shape bloky první - a klíč by se po re-emisi posunul).
        val bubbleIndex = pos.sourceIndex
        val fix = fixes[pos.sourceIndex]
        // Obnovený obrys (viz recoverBubble v TextPatchProvider) je spočítaný nad
        // ORIGINÁLNÍ, neořízlou bitmapou - pro vykreslení se remapuje do oříznutého
        // prostoru stejně jako bloky (viz remapForCrop). Počítá se PŘED render gate,
        // protože právě výsledek recovery rozhoduje o lettering-na-kresbě (viz níž).
        val recoveredShape = fix?.recovered?.shape?.let { shape ->
            if (crop == null) shape else shape.remapForCrop(crop)
        }
        // Lettering přímo v malbě (titulkové captiony, ručně kreslené nápisy): žádný obrys
        // bubliny z OCR ani znovunalezený a pestré pozadí kolem textu. Takový blok se SMÍ
        // překreslit - ale jedině záplatou, která smaže právě tahy původního písma a zbytek
        // kresby nechá (viz buildTextPatch); překlad se napíše barvou původního písma (viz
        // BubbleOverlayFix.textArgb), takže výsledek vypadá jako lettering, ne nálepka.
        // Bez záplaty (bitmapa se nenačetla / obrovský box / recovery místo ní našla obrys)
        // by tu ležela pevná výplň = placka přes malbu, jakou uživatel odmítl u "THE BATTLE
        // OF SEKIGAHARA" - proto radši přeskočíme a originál zůstane nedotčený.
        val isArtLettering = pos.block.shape == null && !pos.block.bgUniform && recoveredShape == null
        val skipArtLettering = isArtLettering && fix?.patch == null
        // seamCover: poražený fragment přešité webtoon bubliny - překlad se vykreslil na
        // sousední stránce, TADY se položí jen záplata přes kopii originálního textu.
        // Kryjeme VŽDY (i bez záplaty jednolitou výplní) - alternativa je viditelný
        // anglický originál vedle češtiny, což je přesně ten bug, kvůli kterému dedup
        // vznikl (audit RWS ch.215 - "AND NOW, EVEN THE RESIDUAL RECOIL OF" nad
        // českým překladem). Bez textu, bez tap/long-press gest.
        if (pos.block.seamCover) {
            TranslationOverlay(
                pos = pos,
                imageRect = imageRect,
                patch = fix?.patch,
                recoveredShape = recoveredShape,
                recoveredBgArgb = fix?.recovered?.interiorArgb,
                coverOnly = true,
            )
        } else if (!pos.block.isSfx && !pos.block.isArtText && !pos.block.isUntranslated && !skipArtLettering &&
            hasTranslatableLetters(pos.block.displayText) && !hasLeakedToken(pos.block.displayText)) {
            // Blok s obnoveným obrysem se vykreslí přesně jako bublina s tvarem z OCR:
            // box = obalový obdélník obrysu (žádná heuristická expanze mimo bublinu),
            // výplň/záplata se ořízne konturou a text se sází do vepsaného obdélníku.
            val effPos = if (recoveredShape != null) {
                pos.copy(
                    leftF = recoveredShape.minOf { it.leftF },
                    topF = recoveredShape.first().yF,
                    rightF = recoveredShape.maxOf { it.rightF },
                    maxBottomF = recoveredShape.last().yF,
                    minTopF = recoveredShape.first().yF,
                )
            } else {
                pos
            }
            TranslationOverlay(
                pos = effPos,
                imageRect = imageRect,
                textScale = textScale,
                isFlipped = "$pageIndex:$bubbleIndex" in flippedBubbles,
                // Klíčem je `sourceIndex` = pozice v `blocks` - stejně, jako se klíčuje
                // mapa záplat výš. Dřív se dohledávalo přes blocks.indexOf(pos.block),
                // jenže dva shodné bloky jsou si podle data class rovny a druhý z nich
                // pak dostal cizí záplatu; pozice v `positioned` zase není stabilní vůči
                // přerovnání dvou layoutů (original vs crop-remap).
                patch = fix?.patch,
                patchTextArgb = fix?.textArgb,
                recoveredShape = recoveredShape,
                recoveredBgArgb = fix?.recovered?.interiorArgb,
                customFontFile = customFontFile,
                onTap = { onToggleFlip(pageIndex, bubbleIndex) },
                onLongPress = {
                    onEditBubble(pageIndex, pos.block.originalText, pos.block.translatedText, pos.block.offsetXDp, pos.block.offsetYDp)
                },
            )
        } else {
            logBubbleSkipped(pos.block.originalText, pos.block.isSfx, pos.block.isUntranslated, hasTranslatableLetters(pos.block.displayText), pos.block.isArtText, hasLeakedToken(pos.block.displayText), skipArtLettering)
        }
    }
}

/**
 * Loguje, PROČ [BubbleOverlayLayer] tuhle bublinu vůbec nevykreslil (originál zůstane
 * prosvítat) - viz [com.haise.jiyu.translate.bubbleSkipReason]. Zatím čistě observabilita:
 * `adb logcat -s BubbleSkip` u nahlášeného "zmizelo YAH!" ukáže, jestli appka bublinu
 * schválně přeskočila (a proč), nebo se ztratila až při vykreslení (viz [logTinyBubbleBox]).
 */
private fun logBubbleSkipped(originalText: String, isSfx: Boolean, isUntranslated: Boolean, hasLetters: Boolean, isArtText: Boolean = false, leakedToken: Boolean = false, artLettering: Boolean = false) {
    val reason = bubbleSkipReason(isSfx, isUntranslated, hasLetters, isArtText, leakedToken, artLettering)
    if (reason != null) {
        if (BuildConfig.DEBUG) Log.d("BubbleSkip", "reason=$reason original=\"$originalText\"")
    }
}

/**
 * Compose Shape, co kopíruje skutečný obrys bubliny z [BubbleShapePoint] seznamu místo
 * pevného zaobleného obdélníku. Body jsou v normalizovaných (0..1) souřadnicích stránky -
 * shapeTopF/shapeBottomF/leftMinF/rightMaxF (= PositionedTranslationBlock.minTopF/maxBottomF/
 * leftF/rightF pro shape-based blok, viz TranslationLayout.kt) je přemapují na velikost
 * skutečně vykresleného boxu.
 */
private class BubbleClipShape(
    private val points: List<BubbleShapePoint>,
    private val shapeTopF: Float,
    private val shapeBottomF: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (points.size < 2 || shapeBottomF <= shapeTopF) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val yRange = shapeBottomF - shapeTopF
        val leftMinF = points.minOf { it.leftF }
        val rightMaxF = points.maxOf { it.rightF }
        val spanF = (rightMaxF - leftMinF).coerceAtLeast(0.0001f)

        // Inset clipu DOVNITŘ obrysu - flood-fill detekce vrací hranu kdesi uprostřed
        // antialiased černého okraje bubliny, takže výplň oříznutá přesně podle ní půlku
        // obrysu překryje a ten vizuálně "zmizí" (audit RWS ch.215 p125 - oválným bublinám
        // se smazal celý obrys). ~2dp mezera je bílá na bílém = neviditelná, obrys zůstane.
        val insetPx = with(density) { BUBBLE_CLIP_INSET.toPx() }
        fun py(p: BubbleShapePoint) =
            insetPx + ((p.yF - shapeTopF) / yRange) * (size.height - insetPx * 2).coerceAtLeast(1f)
        fun pxLeft(p: BubbleShapePoint) =
            insetPx + ((p.leftF - leftMinF) / spanF) * (size.width - insetPx * 2).coerceAtLeast(1f)
        fun pxRight(p: BubbleShapePoint) =
            insetPx + ((p.rightF - leftMinF) / spanF) * (size.width - insetPx * 2).coerceAtLeast(1f)

        val path = Path()
        path.moveTo(pxLeft(points.first()), py(points.first()))
        points.forEach { path.lineTo(pxLeft(it), py(it)) }
        points.asReversed().forEach { path.lineTo(pxRight(it), py(it)) }
        path.close()
        return Outline.Generic(path)
    }
}

@Composable
fun TranslationOverlay(
    pos: PositionedTranslationBlock,
    imageRect: Rect,
    textScale: Float = 1f,
    isFlipped: Boolean = false,
    /** Záplata pozadí pro bublinu na kresbě; null = kreslí se jednolitá výplň jako dosud. */
    patch: android.graphics.Bitmap? = null,
    /**
     * Jádrová barva původního písma ze záplaty (viz BubbleOverlayFix.textArgb). Když je
     * k dispozici, překlad se kreslí JÍ místo černé/bílé podle jasu pozadí - lettering
     * na kresbě tak drží styl originálu (bílý caption s tmavým lemem -> bílá čeština
     * s tmavým obrysem). Platí jen pro cestu se záplatou.
     */
    patchTextArgb: Int? = null,
    /**
     * Obrys bubliny znovunalezený při vykreslení (viz recoverBubble v BubbleRecovery.kt) -
     * záchrana pro bloky, kde selhala detekce tvaru při OCR. Chová se úplně stejně jako
     * obrys z OCR: ořez konturou, sazba do vepsaného obdélníku, nulový bleed.
     */
    recoveredShape: List<BubbleShapePoint>? = null,
    /**
     * Barva interiéru znovunalezené bubliny - přesnější důkaz než navzorkovaný prstenec
     * kolem OCR boxu, který u přetékající bubliny ležel částečně mimo ni.
     */
    recoveredBgArgb: Int? = null,
    /** Vlastní font uživatele (viz CustomFontRepository); null = vestavěná sada podle typu bubliny. */
    customFontFile: java.io.File? = null,
    /**
     * Krycí režim pro poražený fragment přešité webtoon bubliny ([TranslatedBlock.seamCover]):
     * vykreslí jen box se záplatou/výplní přes kopii originálního textu - bez textu
     * (překlad nese vítězný fragment na sousední stránce) a bez tap/long-press gest,
     * protože tady není co flipovat ani editovat.
     */
    coverOnly: Boolean = false,
    onTap: () -> Unit = {},
    /** Dlouhy stisk = rucni oprava prekladu teto bubliny. */
    onLongPress: () -> Unit = {},
) {
    // OCR bounding box je v zásadě vždy leftF<=rightF/topF<=bottomF, ale nejde o zaručený
    // invariant (různé OCR modely, rotace/mirror snímků atd.) - záporná šířka/výška předaná
    // do Modifier.width()/height() spadne na IllegalArgumentException přímo v Compose layout
    // fázi, mimo dosah jakéhokoliv try/catch kolem překladu, a appka tvrdě spadne.
    //
    // Frakce (leftF/topF/...) se mapují na imageRect (skutečně vykreslený obrázek), ne na
    // celý Box - viz [imageDisplayRect].
    //
    // Bubliny s detekovaným tvarem (flood-fill zná přesně vnitřek po vnitřní hranu černého
    // obrysu) NErozšiřujeme o bleed - jinak výplň přejede přes černý obrys bubliny a ten
    // zmizí. Bez tvaru (heuristický obdélník) bleed zůstává, protože tam OCR box bývá o chlup
    // těsnější než text a bez přesahu by po stranách prosvítal originál.
    //
    // Blok se záplatou bleed také nedostává: záplata NENÍ jednolitá výplň, ale skutečné
    // pixely stránky, a ty musí sedět 1:1 (viz [patchPlan]). Rozšířit box o bleed by je
    // posunulo, a posunutý zbytek původního tahu je vidět víc než seam, kterému bleed
    // předchází - u výplně jednou barvou navíc žádný barevný seam nevzniká.
    //
    // Obrys pro vykreslení = obrys z OCR, případně obrys znovunalezený při vykreslení
    // (viz recoverBubble) - pro render je to totéž, jen pochází z jiného místa pipeline.
    // coverOnly (poražený řezový fragment): bez tvaru - kryjeme jen vlastní OCR
    // výřez blokem zaobleného obdélníku. Použít obrys (z OCR i recovery) by obrys
    // přemapovalo na tenký útržek a kontura by krytí zkreslila.
    val shape = if (coverOnly) null else (pos.block.shape ?: recoveredShape)
    // coverOnly ignoruje záplatu (viz níž) - bez ní je krycí výřez na heuristice,
    // tedy bleed jako u shapeless bloku, aby kryl i lem antialiasu tahů útržku.
    val bleed = if (shape != null || (patch != null && !coverOnly)) 0.dp else TRANSLATION_BOX_BLEED
    // U víceřádkových bloků (bez tvaru/záplaty) se svislý bleed zvětší o JEDEN řádek
    // originálu nahoru i dolů - OCR box víceřádkového textu občas ořízne vrchní/spodní
    // glyfy o chlup, nebo sloučený blok nezahrnuje krajní řádek, a originál pak prosvítá
    // těsně nad/pod výplní (audit Vagabondu: "MATA-HACHI'S BEEN" nad překladem). Řádek
    // navíc je bezpečný: výplň je jednolitá barva bubliny, takže přesah vypadá jako
    // součást bubliny, ne jako cizí nálepka. Cap 24dp = pojistka u obrích titulků.
    // coverOnly vynechává řádkový bleedY - útržek je na hraně slicu a přesah o celý
    // řádek by překryl VÍTĚZNÝ fragment sousední stránky, který nese viditelný překlad.
    val bleedY = if (bleed > 0.dp && !coverOnly && pos.block.lineCount > 1 && pos.block.nativeLineHeightF > 0f) {
        bleed + (pos.block.nativeLineHeightF * imageRect.height).dp.coerceAtMost(24.dp)
    } else bleed
    // Jediný zdroj pravdy pro "jak velký kus stránky bublina zakryje" - stejnou funkci
    // používá TextPatchProvider, aby se obojí nemohlo rozejít.
    // seamCover výjimka: kryjeme JEN vlastní OCR rozsah fragmentu (kopie originálního
    // textu na řezu). Rozšířený region (maxBottomF sahá klidně k dalšímu bloku/pod
    // bublinu) by jednolitou výplní zakryl kus kresby, kterou ten fragment vůbec
    // neobsahuje - audit RWS ch.215 ("zakrylo půlku fotky").
    // Řezový útržek (poražený seamCover I vítěz přilepený na okraj stránky): jeho OCR box
    // bývá UŽŠÍ než přeříznutý řádek textu - kraje písmen pak vykukují po stranách krytí
    // (audit RWS ch.215: "HU...ON'T" po stranách češtiny na p91). Krytí proto rozšiřujeme
    // na seamSpan - sjednocený dosah dedup-páru persistovaný při označení. Bez něj jako
    // fallback bounding box vlastního oříznutého obrysu (výplň stejně clipne na interiér).
    val fragShape = pos.block.shape
    val seamFrag = coverOnly ||
        pos.block.topF < SEAM_EDGE_FRACTION ||
        pos.block.bottomF > 1f - SEAM_EDGE_FRACTION
    val spanL = pos.block.seamSpanLF
    val spanR = pos.block.seamSpanRF
    val box = renderBoxRect(pos).let { base ->
        val blk = pos.block
        when {
            seamFrag && spanL != null && spanR != null -> {
                // Svisle kryjeme přes celý dosah oříznutého obrysu na slicu, ne jen přes
                // OCR box - řádky originálu tesně pod/nad boxem útržku (audit RWS ch.215:
                // zbytek "CONTAMINATION AND" pod českým textem na seamu 89/90) patří do
                // téže bubliny a jinak by prosvítaly. Bez obrysu zůstává box + okraj.
                val shapeTop = fragShape?.minOf { it.yF }
                val shapeBottom = fragShape?.maxOf { it.yF }
                val topEdge = blk.topF < SEAM_EDGE_FRACTION
                val bottomEdge = blk.bottomF > 1f - SEAM_EDGE_FRACTION
                PatchRect(
                    spanL,
                    when {
                        coverOnly && topEdge -> 0f
                        coverOnly -> minOf(blk.topF, shapeTop ?: blk.topF)
                        else -> minOf(base.topF, shapeTop ?: base.topF)
                    },
                    spanR,
                    when {
                        coverOnly && bottomEdge -> 1f
                        coverOnly -> maxOf(blk.bottomF, shapeBottom ?: blk.bottomF)
                        else -> maxOf(base.bottomF, shapeBottom ?: base.bottomF)
                    },
                )
            }
            seamFrag && fragShape != null -> PatchRect(
                fragShape.minOf { it.leftF },
                if (blk.topF < SEAM_EDGE_FRACTION) 0f else fragShape.minOf { it.yF },
                fragShape.maxOf { it.rightF },
                if (blk.bottomF > 1f - SEAM_EDGE_FRACTION) 1f else fragShape.maxOf { it.yF },
            )
            // coverOnly bez tvaru: kryjeme vlastní OCR rozsah + dotáhneme k okraji stránky,
            // aby na řezu nezůstal nekrytý proužek originálu.
            coverOnly -> PatchRect(
                blk.leftF,
                if (blk.topF < SEAM_EDGE_FRACTION) 0f else blk.topF,
                blk.rightF,
                if (blk.bottomF > 1f - SEAM_EDGE_FRACTION) 1f else blk.bottomF,
            )
            else -> base
        }
    }
    // Clip: obrys útržku je oříznutý okrajem slicu - se spanem (užším než plný obrys
    // v širších místech bubliny) by vyřízl krytí zpátky na úzký pruh, takže u spanu
    // clipujeme jen zaobleným obdélníkem boxu.
    val spanClipped = spanL != null && spanR != null
    // Rucni posun (viz ManualTranslationEntity.offsetXDp/offsetYDp, item "position offset") -
    // pricte se AZ TADY, na koncovou vypoctenou pozici, takze nezasahuje do zadneho z vypoctu
    // vys (bleed, shape-clip, atd.) - jen posune uz hotovy box o kus stranou/dolu/nahoru.
    val left = (imageRect.left + imageRect.width * box.leftF).dp - bleed + pos.block.offsetXDp.dp
    val top  = (imageRect.top + imageRect.height * box.topF).dp - bleedY + pos.block.offsetYDp.dp
    val w    = (imageRect.width * (box.rightF - box.leftF)).dp.coerceAtLeast(0.dp) + bleed * 2
    // maxBottomF je HORNÍ LIMIT růstu (může sahat až k dalšímu prvku na stránce, klidně přes
    // spoustu prázdného pozadí) - použít ho jako MINIMUM by box nutilo vyplnit i prázdný
    // prostor, kde žádný originál nebyl. Skutečné minimum je vlastní OCR rozsah bubliny
    // (block.bottomF, ne maxBottomF) - to jediné je potřeba zakrýt, aby nikde neprosvítal originál.
    //
    // Výjimka: u bublin s rovnoměrným pozadím (či se známým tvarem) můžeme bezpečně použít
    // maxBottomF, protože jednolitá výplň plynule splyne s bublinou a pokrývá celou oblast,
    // ne jen písmena. U textu přes kresbu naopak zůstáváme na vlastním OCR rozsahu, aby
    // záplata/pozadí nezakrývalo víc kresby, než je nutné.
    val effectiveMinBottomF = when {
        coverOnly -> box.bottomF
        // Řezový útržek se známým dosahem (span/obrys): celé jeho území na slicu je
        // zbytek originálu -> výplň musí pokrýt vždy celý box, ne jen tolik, kolik
        // potřebuje text. Jinak pod krátkým překladem prosvítá řádek EN
        // (ch.215: "CONTAMINATION AND" pod českým textem na seamu).
        seamFrag && (spanClipped || fragShape != null) -> box.bottomF
        // Řezový útržek bez dosahu: box se smrsknul na bounding box obrysu -
        // maxBottomF z layoutu by přelezlo pod útržek a minH > maxH. Cap na box.bottomF.
        seamFrag -> minOf(pos.maxBottomF, box.bottomF)
        shape != null || pos.block.bgUniform -> pos.maxBottomF
        else -> pos.block.bottomF
    }
    val minH = (imageRect.height * (effectiveMinBottomF - box.topF)).dp.coerceAtLeast(0.dp) + bleedY * 2
    val maxH = (imageRect.height * (box.bottomF - box.topF)).dp.coerceAtLeast(0.dp) + bleedY * 2
    if (isSuspiciouslyTinyBubbleBox(w.value, maxH.value)) {
        logTinyBubbleBox(pos.block.originalText, w.value, minH.value, maxH.value, shape != null)
    }
    // Pro coverOnly drží box vlastní OCR rozsah (viz výše) - clipShape k němu mapujeme
    // stejně, aby kontura seděla na výřez fragmentu, ne na rozšířený region.
    // Řezové útržky s vlastním obrysem (vč. coverOnly) clipují tím obrysem - box už je
    // bounding box obrysu, takže mapování sedí přesně a krytí neuteče mimo bublinu.
    // Výjimka: se spanem je box záměrně ŠIRŠÍ než oříznutý obrys útržku (kraje přeříznutého
    // řádku), takže clip na útržkový obrys by krytí zase zúžil - držíme zaoblený obdélník.
    val clipShape = when {
        spanClipped -> RoundedCornerShape(3.dp)
        seamFrag && fragShape != null -> BubbleClipShape(fragShape, box.topF, box.bottomF)
        shape != null -> BubbleClipShape(shape, box.topF, box.bottomF)
        else -> RoundedCornerShape(3.dp)
    }
    // Svislý gradient (horní/dolní polovina vzorkovaného prstence, viz OcrEngine.sampleBackgroundColor)
    // místo jednolité barvy - obě strany se "přichytí" na bílou/černou nezávisle (snapBubbleBg),
    // takže obyčejné bubliny zůstávají plnou barvou stejně jako dřív, gradient se projeví jen
    // u barevných/stínovaných bublin, kde má reálný podklad.
    //
    // U obnovené bubliny se místo prstence použije barva interiéru pod textem - prstenec
    // kolem přetékajícího boxu vzorkoval okraj panelu a vracel špatnou barvu (přesně ta
    // chyba, kvůli které obrys selhal).
    val snappedBgTop = snapBubbleBg(recoveredBgArgb ?: pos.block.bgColorArgb)
    val snappedBgBottom = snapBubbleBg(recoveredBgArgb ?: pos.block.bgColorBottomArgb)
    // U záplaty se barva textu rozhoduje podle SKUTEČNĚ vykreslených pixelů záplaty, ne podle
    // navzorkovaného prstence - ten zachytí tmavou kresbu mimo bublinu a "jas pozadí" pak
    // vybere bílé písmo pro světlou záplatu (nahlášený nečitelný "bílý text na bílé záplate").
    // Bez záplaty se pozadí kreslí přímo vzorkovanou barvou, takže prstenec stačí.
    val textBgArgb = remember(patch, snappedBgTop, snappedBgBottom) {
        if (patch == null) {
            averageArgb(snappedBgTop, snappedBgBottom)
        } else {
            patchMeanArgb(patch::getPixel, patch.width, patch.height)
                ?: averageArgb(snappedBgTop, snappedBgBottom)
        }
    }
    // tidyStrandedPunctuation: mezera před koncovou tečkou nabízí zalamovači zlom, po kterém
    // tečka zůstane sama na řádku - viz [tidyStrandedPunctuation] a nahlášené "ODLÉTÁME" + tečka.
    // tidyFrenchStyleSpacing: model si někdy plete interpunkční styl se zdrojovým jazykem a
    // nechá mezeru před "?"/"!" i uprostřed textu (viz jeho doc komentář a nahlášené
    // "CO DĚLÁŠ ?").
    val displayText = tidyFrenchStyleSpacing(
        tidyStrandedPunctuation(
            matchOriginalCase(pos.block.displayText, pos.block.originalText),
        ),
    )

    // Bezpečná plocha pro text uvnitř tvaru bubliny (viz [largestInscribedRect]) - největší
    // obdélník, který se celý vejde dovnitř obrysu. Text se sází do NĚJ, ne do celého
    // ohraničujícího obdélníku tvaru: díky tomu nemůže zasáhnout obrys ani v užších místech
    // (dvojkruhová bublina, zvlněný okraj) a nepotřebuje k tomu žádné vykreslování řádek po
    // řádku, které dřív působilo překrývající se řádky (viz uživatelská zpětná vazba).
    // Padding uvnitř plochy - obrys bubliny bývá nakreslený "tlustou" linkou a text nalepený
    // těsně na ni vypadá špatně i když technicky nepřetéká.
    val inscribed = shape?.let { largestInscribedRect(it) }
    val textAreaWidth = inscribed
        ?.let { (imageRect.width * it.widthF * INSCRIBED_TEXT_AREA_FACTOR).dp }
        ?: w
    val textAreaHeight = inscribed
        ?.let { (imageRect.height * it.heightF * INSCRIBED_TEXT_AREA_FACTOR).dp }
        ?: maxH
    // Skutečné maximum pro text musí respektovat svislý padding vnějšího Boxu - jinak by
    // fitter mohl vybrat písmo, které zaplní celou výšku, ale Text dostane o padding menší
    // prostor a poslední řádek se uřízne (viz uživatelská zpětná vazba).
    val contentMaxHeight = textAreaHeight.coerceAtMost(
        maxH - TRANSLATION_TEXT_VERTICAL_PADDING * 2
    ).coerceAtLeast(0.dp)
    // Vepsaný obdélník nemusí být uprostřed bubliny (u složeného tvaru bývá posunutý k té
    // prostornější části) - text se musí posunout s ním, jinak by se vysázel doprostřed
    // celého tvaru, tedy mimo tu bezpečnou plochu.
    val textOffsetX = inscribed?.let {
        val boxCenterF = (pos.leftF + pos.rightF) / 2f
        val rectCenterF = (it.leftF + it.rightF) / 2f
        (imageRect.width * (rectCenterF - boxCenterF)).dp
    } ?: 0.dp
    val textOffsetY = inscribed?.let {
        val boxCenterF = (pos.minTopF + pos.maxBottomF) / 2f
        val rectCenterF = (it.topF + it.bottomF) / 2f
        (imageRect.height * (rectCenterF - boxCenterF)).dp
    } ?: 0.dp

    // Entrance animace - MutableTransitionState začíná na false a rovnou cílí na true, takže
    // AnimatedVisibility přehraje "enter" přesně jednou při prvním composnutí týhle bubliny
    // (např. když se stránka přeloží nebo se do ní scrollne/naviguje zpět) a pak už zůstává
    // viditelná, žádné "exit" se nikdy nespustí.
    val entranceState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    AnimatedVisibility(
        visibleState = entranceState,
        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = left, y = top)
                // Pevná šířka + heightIn(min = minH, max = maxH): box musí vždy zakrýt aspoň
                // vlastní OCR rozsah bubliny (jinak prosvítá originál), smí růst výš k maxH, jen
                // když to text opravdu potřebuje (ne nutit box vyplnit celý, klidně prázdný,
                // prostor až k dalšímu prvku na stránce) - ale NIKDY přes maxH. Bez horního
                // stropu tu neexistovala žádná pojistka, kdyby fitter někdy zvolil o chlup
                // moc velké písmo (zaokrouhlení/rozdíl mezi měřením a skutečným vykreslením) -
                // box se pak fyzicky natáhl do sousedního panelu s kresbou (viz uživatelská
                // zpětná vazba - "AHA, PÁNI."/"HORSKÉ BESTIE..." přetékající do obrázku pod
                // bublinou). Krajní řádek se teď nanejvýš neúhledně ořízne, ale nikdy nezasáhne
                // cizí kresbu.
                .width(w)
                .heightIn(min = minH, max = maxH)
                .clip(clipShape)
                // Kde pozadí NENÍ jedné barvy, kreslí se místo výplně ZÁPLATA: zakryté jsou jen
                // tahy původního písma, zbytek prosvítá (viz TextPatchProvider). Týká se to textu
                // na kresbě i balónků s vzorovaným vnitřkem - u těch druhých vzorek přežije,
                // kdežto jednolitá výplň by z nich udělala bílou nálepku (viz [patchPlan]).
                // Jednolité pozadí kreslí gradient jako dosud, tam je k nerozeznání od originálu.
                .let { m ->
                    // coverOnly (poražený řezový fragment): NIKDY záplatu - inpainting
                    // v tenkém útržku nemá okolní kontext a zanechává rozmazané zbytky
                    // původních písmen ("s____y" duchové pod překladem - audit RWS
                    // ch.215 p90). Plná výplň barvou interiéru je uvnitř bubliny čistá.
                    // Stejný důvod platí pro VÍTĚZNÝ blok přilepený na okraj stránky -
                    // horní I spodní: jeho záplata vznikla z útržku přes řez sliců,
                    // bez kontextu za řezem - na p91 zůstaly zbytky "HUMANS, DON'T"
                    // a na p89 celý neinpaintovaný řádek "CONTAMINATION AND" pod
                    // češtinou. Výplň barvou interiéru je zakryje čistě.
                    if (patch != null && !coverOnly && !seamFrag) {
                        m.paint(
                            painter = BitmapPainter(patch.asImageBitmap()),
                            sizeToIntrinsics = false,
                            // FillWidth + TopStart, ne FillBounds: záplata je výřez skutečné
                            // stránky a musí ležet přesně na svém místě. Šířka boxu je pevná
                            // a záplata je spočítaná přesně přes ni, takže vodorovně sedí 1:1
                            // a stejné měřítko vyjde i svisle. Výška boxu se ale řídí textem
                            // (heightIn min..max), a FillBounds ji dorovnával svislým
                            // roztažením - zbytky původních tahů se tím zvětšily a posunuly
                            // doprostřed překladu. Přebytek dole se radši ořízne.
                            alignment = Alignment.TopStart,
                            contentScale = ContentScale.FillWidth,
                            alpha = TRANSLATION_BOX_ALPHA,
                        )
                    } else {
                        m.background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(snappedBgTop).copy(alpha = TRANSLATION_BOX_ALPHA),
                                    Color(snappedBgBottom).copy(alpha = TRANSLATION_BOX_ALPHA),
                                ),
                            ),
                        )
                    }
                }
                // Tap = "flip" na originál (viz ReaderViewModel.toggleBubbleFlip). Konzumuje tap
                // dřív, než se dostane k page-level gestům (tap-zóny/double-tap zoom/long-press
                // sdílení v MangaReaderu) - vědomý kompromis, přesně nad bublinou chceme flip,
                // ne zoom/navigaci.
                // Dlouhy stisk NAD BUBLINOU stini sdileni stranky z MangaReaderu - stejny
                // vedomy kompromis jako u tapu vys: presne nad bublinou chceme jeji akce.
                .let { m ->
                    // seamCover nemá co flipovat/editovat - gesta by tu jen stínila
                    // page-level tap zóny bez jakéhokoliv efektu.
                    if (coverOnly) m else m.pointerInput(onTap, onLongPress) {
                        detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                    }
                }
                .padding(horizontal = TRANSLATION_TEXT_HORIZONTAL_PADDING, vertical = TRANSLATION_TEXT_VERTICAL_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            // Krycí režim (seamCover): jen záplata/výplň přes kopii originálního
            // textu - překlad se vykreslil na sousední stránce, druhý text by tu
            // vytvořil přesně tu duplicitu, kterou dedup na řezech řeší.
            if (!coverOnly) AnimatedContent(
                targetState = isFlipped,
                transitionSpec = {
                    (scaleIn(initialScale = 0.85f) + fadeIn())
                        .togetherWith(scaleOut(targetScale = 0.85f) + fadeOut())
                },
                label = "bubble-flip",
            ) { flipped ->
                AutoFitTranslatedText(
                    text = if (flipped) pos.block.originalText else displayText,
                    // Volba barvy textu (podle jasu) potřebuje JEDNU barvu - u záplaty
                    // z jejích skutečných pixelů, jinak průměr obou stran gradientu.
                    bgColorArgb = textBgArgb,
                    // U záplaty držíme barvu písma originálu - viz [patchTextArgb].
                    textArgbOverride = if (patch != null) patchTextArgb else null,
                    boxWidth = textAreaWidth,
                    maxHeight = contentMaxHeight,
                    // Šířka VNĚJŠÍHO boxu - text se do ní musí vejít bez ohledu na to, jak
                    // široký je obrys bubliny (viz [fitTextToShape] parametr maxLineWidthPx).
                    renderWidth = w,
                    textScale = textScale,
                    bubbleType = pos.block.bubbleType,
                    offsetX = textOffsetX,
                    offsetY = textOffsetY,
                    shape = shape,
                    shapeCenterF = inscribed?.let { (it.leftF + it.rightF) / 2f },
                    // Svislé těžiště SKUTEČNÉ textové plochy. Obalový obdélník obrysu zahrnuje
                    // i ocásek bubliny, takže jeho střed leží mimo lalok a text se od středu
                    // bubliny odtáhne - viz [fitTextToShape] parametr centerYF.
                    shapeCenterYF = inscribed?.let { (it.topF + it.bottomF) / 2f },
                    shapeTopF = pos.minTopF,
                    shapeBottomF = pos.maxBottomF,
                    imageWidthDp = imageRect.width,
                    imageHeightDp = imageRect.height,
                    nativeLineHeightF = pos.block.nativeLineHeightF,
                    originalText = pos.block.originalText,
                    customFontFile = customFontFile,
                )
            }
        }
    }
}

/**
 * Comic Neue - komiksové písmo s plnou podporou české diakritiky (ř,ž,č,š,ě,ň,ť,ů...), ne
 * systémový font, který v malé bublině vypadá jako titulky, ne jako lettering. Různé řezy
 * podle typu bubliny (viz BubbleType/fontFamilyFor) místo jednoho univerzálního - skutečná
 * vizuální analýza stylu písma z nízkorozlišeného OCR výřezu by byla nespolehlivá (viz spec
 * docs/superpowers/specs/2026-07-24-bubble-shape-and-font-design.md), tohle je praktičtější
 * přiblížení "co nejpodobnějšího originálu" fontu.
 */
private val ComicNeueRegular = FontFamily(Font(R.font.comic_neue_regular, FontWeight.Normal))
private val ComicNeueBold = FontFamily(Font(R.font.comic_neue_bold, FontWeight.Bold))
private val ComicNeueItalic = FontFamily(Font(R.font.comic_neue_italic, FontWeight.Normal, FontStyle.Italic))
private val ComicNeueBoldItalic = FontFamily(Font(R.font.comic_neue_bold_italic, FontWeight.Bold, FontStyle.Italic))

/**
 * Exo 2 (variabilní font, SIL OFL licence, https://github.com/google/fonts/tree/main/ofl/exo2,
 * plná podpora české diakritiky ověřena přes fontTools) - geometrický "sci-fi/herní" řez pro
 * SYSTEM bubliny (herní stat-boxy typu "God's Legion Support" / "Lucian" / "L-Rank
 * Stellar-Commander"). Dřív dostávaly stejný komiksový ComicNeueRegular jako běžná řeč, což
 * vizuálně vytrhávalo z atmosféry herního UI (nahlášeno uživatelem). Jeden .ttf pokrývá celou
 * škálu vah přes FontVariation osu, stejný vzor jako [com.haise.jiyu.ui.theme.InterFontFamily].
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun exo2Weight(weight: Int) = Font(
    resId = R.font.exo2_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Exo2System = FontFamily(exo2Weight(500), exo2Weight(700))

/**
 * Loguje, kdyz odhad nativni velikosti pisma ([estimateNativeFontPx]) narazil na strop sazby
 * a jestli pri tom v bublině zbylo nevyuzite misto (viz onCapProbe parametr [fitFontSizeToBox]/
 * [fitTextToShape]). Zatim cistě observabilita: `adb logcat -s NativeFontCap` pri beznem
 * cteni nasbira realnou distribuci, podle ktere se pomer box/font casem dolaci na datech.
 */
private fun logNativeFontCap(preferredFontSp: Float, roomToGrow: Boolean) {
    Log.d("NativeFontCap", "preferred=%.1fsp roomToGrow=%s".format(preferredFontSp, roomToGrow))
}

/**
 * Loguje bublinu, jejíž vypočtený box vyšel podezřele malý (viz [isSuspiciouslyTinyBubbleBox]) -
 * kandidát na "text zmizel, i když se technicky vykreslil" (nahlášeno na natěsno namačkaném
 * trsu bublin - "YAH!" vedle "STOP IT, HATSU!!"/dvou SFX/dlouhé bubliny). `hasShape` rozlišuje,
 * jestli šlo o tvarovou bublinu (obrys sám vyšel malý) nebo heuristickou (kolize se sousedy
 * ji zmáčkla) - viz [com.haise.jiyu.translate.layoutTranslationBlocks].
 */
private fun logTinyBubbleBox(originalText: String, widthDp: Float, minHeightDp: Float, maxHeightDp: Float, hasShape: Boolean) {
    if (BuildConfig.DEBUG) Log.d("TinyBubbleBox", "w=%.1fdp minH=%.1fdp maxH=%.1fdp shape=%s original=\"%s\"".format(widthDp, minHeightDp, maxHeightDp, hasShape, originalText))
}

private fun fontFamilyFor(bubbleType: BubbleType): FontFamily = when (bubbleType) {
    BubbleType.SHOUT -> ComicNeueBold
    BubbleType.THOUGHT, BubbleType.WHISPER -> ComicNeueItalic
    BubbleType.SYSTEM -> Exo2System
    BubbleType.SPEECH, BubbleType.NARRATION, BubbleType.SFX -> ComicNeueRegular
}

/**
 * Přeložený text (čeština) bývá delší než originál (JP/KR/EN) - bez úpravy velikosti písma
 * by buď přetekl přes sousední bublinu, nebo by ho Compose tvrdě uřízl. Tady najdeme
 * největší velikost, která se ještě vejde do zadané plochy (viz [fitFontSizeToBox]).
 *
 * Plocha ([boxWidth] x [maxHeight]) je u bublin se skutečným tvarem největší obdélník vepsaný
 * DOVNITŘ obrysu (viz [largestInscribedRect] a volající) - text tak fyzicky nemůže zasáhnout
 * obrys, i kdyby byla bublina jakkoli nepravidelná, a nepotřebuje k tomu žádné vykreslování
 * po jednotlivých řádcích (ten dřívější přístup působil překrývající se řádky).
 *
 * Text se vždy sází jako JEDEN normální blok - řádkování tak řeší Compose, ne vlastní
 * dopočítávání pozic.
 */
@Composable
private fun AutoFitTranslatedText(
    text: String,
    bgColorArgb: Int,
    boxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    textScale: Float,
    bubbleType: BubbleType,
    /** Šířka vnějšího Boxu bubliny - viz [renderableWidthPx]. */
    renderWidth: androidx.compose.ui.unit.Dp = boxWidth,
    offsetX: androidx.compose.ui.unit.Dp = 0.dp,
    offsetY: androidx.compose.ui.unit.Dp = 0.dp,
    shape: List<BubbleShapePoint>? = null,
    shapeCenterF: Float? = null,
    /** Svislé těžiště textové plochy bubliny - viz [fitTextToShape] parametr centerYF. */
    shapeCenterYF: Float? = null,
    shapeTopF: Float = 0f,
    shapeBottomF: Float = 0f,
    imageWidthDp: Float = 0f,
    imageHeightDp: Float = 0f,
    /** Průměrná výška JEDNOHO řádku originálu (zlomek výšky stránky) - viz [TranslatedBlock.nativeLineHeightF]. */
    nativeLineHeightF: Float = 0f,
    /** Text originálu - rozhoduje, jestli se výška OCR boxu čte jako verzálky, nebo smíšený text. */
    originalText: String = "",
    /**
     * Barva původního písma ze záplaty (viz BubbleOverlayFix.textArgb). Nastavená přepíše
     * volbu černá/bílá podle jasu pozadí - překlad lettering-na-kresbě tak drží vizuální
     * styl originálu; obrys se zvolí kontrastní k této barvě.
     */
    textArgbOverride: Int? = null,
    /** Vlastní font uživatele (viz CustomFontRepository) - když je nastavený, nahradí vestavěnou sadu pro VŠECHNY typy bublin (jeden font, ne čtyři řezy). */
    customFontFile: java.io.File? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // Neplatny/poskozeny soubor (velmi vzacne - font prosel kontrolou pri stazeni, ale
    // Font(file=) muze i tak odmitnout skutecny obsah, ktery [isAcceptableFontContentType]
    // nekontroluje) tise spadne zpatky na vestavenou sadu, misto aby appka spadla pri
    // vykreslovani KAZDE bubliny.
    val customFontFamily = customFontFile?.let { file ->
        remember(file) { runCatching { FontFamily(Font(file)) }.getOrNull() }
    }
    val fontFamily = customFontFamily ?: fontFamilyFor(bubbleType)
    val maxFontSp = 36f * textScale
    // Podlaha se ZÁMĚRNĚ nenásobí textScale nahoru - viz [minTranslationFontSp]. Zvětšené písmo
    // v nastavení jinak text z malých bublin mazalo, protože přebytek ořízne .clip(clipShape) níž.
    val minFontSp = minTranslationFontSp(textScale)

    // Velikost, jakou mělo písmo v ORIGINÁLU, převedená na sp - viz [fitFontSizeToBox]/
    // [fitTextToShape] parametr preferredFontSp. Řádek o výšce nativeLineHeightF (zlomek výšky
    // stránky) se na obrazovce vykreslí jako nativeLineHeightF * imageHeightDp - a protože
    // Compose řádkuje s výškou fontSp*1.25 (viz lineHeightPx níž), zpětně z toho dostaneme
    // fontSp. Vynásobeno textScale, ať respektuje i uživatelovo nastavení velikosti textu -
    // jinak by "nativní" velikost ignorovala jeho vlastní preferenci.
    val preferredFontSp = if (nativeLineHeightF > 0f && imageHeightDp > 0f) {
        val boxHeightPx = with(density) { (nativeLineHeightF * imageHeightDp).dp.toPx() }
        // Drive se tady delilo 1.25, jako by nativeLineHeightF byla radkova roztec. Je to ale
        // vyska OCR BOXU, ktery obepina jen samotna pismena - odhad proto vychazel na 0,62
        // nasobku skutecne velikosti a text zustaval maly i v obri bubline. Prevodni pomery
        // jsou zmerene na zarizeni, viz estimateNativeFontPx.
        val nativeFontPx = estimateNativeFontPx(boxHeightPx, originalText)
        with(density) { nativeFontPx.toSp() }.value * textScale
    } else {
        null
    }

    // Vzorkovaná barva pozadí bubliny může být i tmavá (stínovaný/černý shout box) - černý text
    // na černém pozadí by byl nečitelný, proto volíme barvu textu (a opačnou barvu obrysu)
    // podle jasu (luminance) pozadí, ne napevno.
    //
    // Výjimka: u záplaty na kresbě známe barvu PŮVODNÍHO písma (textArgbOverride) - překlad
    // drží její odstín, aby lettering vypadal jako součást malby (bílý caption zůstane
    // bílý i na světlém sněhu). Obrys se volí kontrastní K TEXTU, ne k pozadí: bílé písmo
    // dostane tmavý lem přesně jako původní caption lettering.
    val bg = Color(bgColorArgb)
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val textColor: Color
    val strokeColor: Color
    if (textArgbOverride != null) {
        val tc = Color(textArgbOverride)
        val textLum = 0.299f * tc.red + 0.587f * tc.green + 0.114f * tc.blue
        // Vzorkování barvy písma z kresby může seknout do pozadí (hlavně tmavý lettering na
        // tmavé scéně - audit Vagabondu: "DALŠÍ BANDITA?" přišel s textem skoro stejně
        // tmavým jako záplata a překlad byl neviditelný). Podezřele nízký kontrast
        // -> fallback na černou/bílou podle jasu pozadí, stejně jako bez override.
        // Práh 0.35: na auditované stránce držel i šedé písmo na tmavé kresbě
        // (rozdíl ~0.25 prošel při 0.22) a překlad četl hůř než originál pod ním.
        textColor = if (kotlin.math.abs(textLum - luminance) < 0.35f) {
            if (luminance < 0.5f) Color.White else Color.Black
        } else tc
        strokeColor = if (0.299f * textColor.red + 0.587f * textColor.green + 0.114f * textColor.blue > 0.5f) Color.Black else Color.White
    } else {
        textColor = if (luminance < 0.5f) Color.White else Color.Black
        strokeColor = if (luminance < 0.5f) Color.Black else Color.White
    }

    // Kolik místa dostane SKUTEČNÝ Text composable: šířka vnějšího Boxu minus jeho vodorovný
    // padding. Obě sazební cesty (tvarová i obdélníková) musí počítat s tímhle číslem, ne s
    // geometrií obrysu - obrys bubliny bývá širší než box (hranatý popiskový rámeček pokrývá
    // celý šedý obdélník, box kopíruje jen užší OCR rozsah textu), a sazba podle obrysu pak
    // prošla kontrolou "slovo se vejde", jenže Compose měl při vykreslení míň místa a slovo
    // rozsekal po písmenech ("SPOLEČNOS" + "T", viz uživatelský screenshot).
    val renderableWidthPx = with(density) {
        (renderWidth - TRANSLATION_TEXT_HORIZONTAL_PADDING * 2).toPx()
    }.coerceAtLeast(1f)
    val pageHeightPx = with(density) { imageHeightDp.dp.toPx() }.coerceAtLeast(1f)
    // Tvarovou sazbu zúžíme o svislý padding vnějšího Boxu - jinak fitter myslí, že má k
    // dispozici celou výšku obrysu, ale Text dostane od Compose o padding míň a poslední řádek
    // se uřízne.
    val verticalPaddingF = if (shape != null) with(density) { (TRANSLATION_TEXT_VERTICAL_PADDING * 2).toPx() } / pageHeightPx else 0f
    val fitShapeTopF = if (shape != null) (shapeTopF + verticalPaddingF / 2).coerceAtMost(shapeBottomF) else shapeTopF
    val fitShapeBottomF = if (shape != null) (shapeBottomF - verticalPaddingF / 2).coerceAtLeast(shapeTopF) else shapeBottomF

    // ── Sazba do skutečného tvaru bubliny (vyvážené řádky, viz [fitTextToShape]) ──
    // Tohle je hlavní cesta pro bubliny se známým obrysem: každý řádek dostane šířku podle
    // tvaru ve svém pásu, takže v oválné bublině vyjde blok textu kosočtvercový (delší řádky
    // uprostřed) - přesně jak sází profesionální lettering - a využije se mnohem víc plochy
    // než u prostého vepsaného obdélníku. Řádky jdou do JEDNOHO Textu oddělené \n, takže
    // řádkování i centrování řeší Compose (žádné vykreslování řádek po řádku, které dřív
    // způsobovalo překrývající se řádky).
    // Strukturovaná pole (viz BubbleMerge.mergeNearbyLines/STRUCTURED_FIELD_HEIGHT_RATIO) nesou
    // "\n" mezi jednotlivými poli (popisek/jméno/podtitul) PRÁVĚ PROTO, aby se nikdy neslila
    // zpátky do jedné plynulé věty. fitTextToShape by ale "\n" smazalo na obyčejnou mezeru
    // (text.split(' ', '\n')) a breakIntoLines mohlo řádky přerovnat do jiné sady podle změřené
    // šířky - přesně to, čemu měl BubbleMerge/prompt zabránit (nalezeno code-review). Takový
    // text proto jde přes fitFixedLinesToShape, který řádky bere tak, jak přišly.
    val isStructuredFieldText = text.contains('\n')
    val measureLineWidth: (String, Float) -> Float = { line, fontSp ->
        val style = TextStyle(fontSize = fontSp.sp, fontFamily = fontFamily)
        val strokeReserve = with(density) { maxOf(2.dp.toPx(), fontSp.sp.toPx() * STROKE_WIDTH_FACTOR) }
        textMeasurer.measure(text = line, style = style, softWrap = false).size.width + strokeReserve
    }
    val lineHeightForFontSp: (Float) -> Float = { fontSp -> with(density) { (fontSp * 1.25f).sp.toPx() } }
    val shapedLayout = if (shape != null && shapeCenterF != null && imageHeightDp > 0f && fitShapeBottomF > fitShapeTopF) {
        if (isStructuredFieldText) {
            val fixedLines = remember(text) { text.split('\n') }
            remember(text, shape, shapeCenterF, shapeCenterYF, fitShapeTopF, fitShapeBottomF, imageWidthDp, imageHeightDp, maxFontSp, fontFamily, renderableWidthPx, preferredFontSp) {
                fitFixedLinesToShape(
                    lines = fixedLines,
                    minFontSp = minFontSp,
                    maxFontSp = maxFontSp,
                    shape = shape,
                    centerF = shapeCenterF,
                    shapeTopF = fitShapeTopF,
                    shapeBottomF = fitShapeBottomF,
                    pageWidthPx = with(density) { imageWidthDp.dp.toPx() },
                    pageHeightPx = pageHeightPx,
                    measureLine = measureLineWidth,
                    lineHeightPx = lineHeightForFontSp,
                    maxLineWidthPx = renderableWidthPx,
                    preferredFontSp = preferredFontSp,
                    centerYF = shapeCenterYF,
                    onCapProbe = ::logNativeFontCap,
                )
            }
        } else {
            val words = remember(text) { text.split(' ').filter { it.isNotBlank() } }
            remember(text, shape, shapeCenterF, shapeCenterYF, fitShapeTopF, fitShapeBottomF, imageWidthDp, imageHeightDp, maxFontSp, fontFamily, renderableWidthPx, preferredFontSp) {
                fitTextToShape(
                    words = words,
                    minFontSp = minFontSp,
                    maxFontSp = maxFontSp,
                    shape = shape,
                    centerF = shapeCenterF,
                    shapeTopF = fitShapeTopF,
                    shapeBottomF = fitShapeBottomF,
                    pageWidthPx = with(density) { imageWidthDp.dp.toPx() },
                    pageHeightPx = pageHeightPx,
                    measureWord = { word, fontSp -> measureLineWidth(word, fontSp) },
                    spaceWidth = { fontSp ->
                        val style = TextStyle(fontSize = fontSp.sp, fontFamily = fontFamily)
                        // Šířka mezery = rozdíl mezi "a a" a "aa" - měřit samotné " " je nespolehlivé,
                        // protože měřič koncové mezery ořezává.
                        val withSpace = textMeasurer.measure(text = "a a", style = style, softWrap = false).size.width
                        val without = textMeasurer.measure(text = "aa", style = style, softWrap = false).size.width
                        (withSpace - without).toFloat().coerceAtLeast(1f)
                    },
                    lineHeightPx = lineHeightForFontSp,
                    maxLineWidthPx = renderableWidthPx,
                    preferredFontSp = preferredFontSp,
                    centerYF = shapeCenterYF,
                    onCapProbe = ::logNativeFontCap,
                )
            }
        }
    } else {
        null
    }

    if (shapedLayout != null) {
        // Vnější Box má u tvarových bloků přesně výšku obalového obdélníku obrysu a centruje
        // obsah do svého středu - jenže ten obdélník zahrnuje i OCÁSEK bubliny, takže jeho
        // střed leží mimo lalok s textem. Dřív se tenhle rozdíl neřešil vůbec (offsetY se
        // počítal, ale u tvarové sazby se zahazoval) a text se od středu bubliny odtáhl
        // směrem k ocásku - viz [fitTextToShape] parametr centerYF.
        //
        // Posouvá se o rozdíl proti středu, který si zvolila SAZBA (ne proti tomu, o co se
        // žádalo): u zúženého obrysu si ho mohla zarazit zpátky dovnitř, a šířky řádků platí
        // pro to místo, kde blok doopravdy leží.
        val shapedOffsetY = ((shapedLayout.centerYF - (fitShapeTopF + fitShapeBottomF) / 2f) * imageHeightDp).dp
        Box(
            modifier = Modifier.offset(x = offsetX, y = shapedOffsetY),
            contentAlignment = Alignment.Center,
        ) {
            StrokedTranslatedText(
                text = shapedLayout.lines.joinToString("\n"),
                fontSp = shapedLayout.fontSp,
                fontFamily = fontFamily,
                textColor = textColor,
                strokeColor = strokeColor,
                preWrapped = true,
            )
        }
        return
    }
    // Skutečná šířka dostupná pro Text je menší z boxWidth a vnějšího boxu minus jeho
    // horizontální padding. U obdélníkové bubliny je boxWidth == vnější šířka, takže
    // limitujícím faktorem je padding; u tvarové bubliny, která spadne do obdélníkové
    // náhrady, je boxWidth odvozené z vepsaného obdélníku a může být užší než vnější box.
    val widthPx = minOf(
        with(density) { boxWidth.roundToPx() },
        renderableWidthPx.toInt()
    ).coerceAtLeast(1)
    val maxHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

    // Měření jednoho kandidátního textu při daném písmu - sdílené mezi [fitFontSizeToBox]
    // (hledání velikosti) a nouzovým [truncateToFit] (hledání zkráceného textu, co se vejde).
    val measureBlock: (String, Float, Float) -> TextMeasurement = measureBlock@{ measuredText, fontSp, maxWidthPx ->
        // Rezerva na obrys (viz StrokedTranslatedText/STROKE_WIDTH_FACTOR) - obrys se
        // kreslí kolem stejného textu ve stejné velikosti, takže vizuálně "vykousne"
        // trochu místa navíc kolem glyphů. Bez rezervy by fitter vybral velikost, co
        // se vejde jen do samotné výplně (Fill), a obrys by pak u okrajů bubliny přetekl.
        val strokeReservePx = with(density) { maxOf(2.dp.toPx(), fontSp.sp.toPx() * STROKE_WIDTH_FACTOR) }
        val style = TextStyle(
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.25f).sp,
            fontFamily = fontFamily,
        )
        val constraintWidth = (maxWidthPx - strokeReservePx).toInt().coerceAtLeast(1)
        val measured = textMeasurer.measure(text = measuredText, style = style, constraints = Constraints(maxWidth = constraintWidth))
        val lines = (0 until measured.lineCount).map { i ->
            LineMetrics(
                widthPx = measured.getLineRight(i) - measured.getLineLeft(i),
                topPx = measured.getLineTop(i),
                bottomPx = measured.getLineBottom(i),
            )
        }
        // Nejdelší NEDĚLITELNÝ úsek měřený BEZ šířkového omezení - jinak by ho Compose
        // sám zalomil a naměřená šířka by byla vždycky menší než limit, takže by
        // kontrola v fitFontSizeToBox nikdy nic nezachytila. Tohle je jediná obrana
        // proti tomu, aby se slovo rozsekalo uprostřed po písmenech ("KDYBYCH" ->
        // "KDYB"/"YCH", viz uživatelská zpětná vazba).
        //
        // longestIndivisibleRunWidthPx měří po ÚSECÍCH mezi soft hyphen zlomy, ne po
        // celých slovech - slovo s rozdělovníkem (viz SoftHyphenation) NENÍ atomické,
        // i když neobsahuje mezeru. Bez tohohle rozdělení se dřív měřila šířka CELÉHO
        // slova i s rozdělovníkem, a když se ani tak nevešla, fitter se vzdal a Compose
        // vlastní nouzový zlom slovo rozsekl JINDE, než kam rozdělovník ukazoval
        // (nahlášeno: "Pante­rí" (platný zlom 5+2 písmen) vykresleno jako "PANTER"/"Í").
        val longestWordWidthPx = longestIndivisibleRunWidthPx(measuredText) { segment ->
            textMeasurer.measure(text = segment, style = style, softWrap = false).size.width.toFloat()
        }
        // Kontrola REÁLNÝCH pozic zlomů, ne jen šířek: longestWordWidthPx odhaduje, že
        // slovo půjde zalomit na soft hyphenu ("VARO-" se vejde) - ale když renderer
        // úsek s vykreslenou pomlčkou vyhodnotí o chlup širší, zalomí slovo nouzově
        // JINDE ("VAROV"/"ÁNÍ" - audit Vagabondu, varovný proužek na obálce). Zlom
        // mezi dvěma písmeny bez U+00AD/mezery je takový nouzový char-break.
        val hasMidWordBreak = (0 until measured.lineCount - 1).any { i ->
            isMidWordBreak(measuredText, measured.getLineEnd(i, visibleEnd = true))
        }
        TextMeasurement(
            totalHeightPx = measured.size.height + strokeReservePx,
            lines = lines,
            longestWordWidthPx = longestWordWidthPx + strokeReservePx,
            hasMidWordBreak = hasMidWordBreak,
        )
    }
    val fitResult = remember(text, widthPx, maxHeightPx, maxFontSp, fontFamily, preferredFontSp) {
        fitFontSizeToBox(
            minFontSp = minFontSp,
            maxFontSp = maxFontSp,
            boxWidthPx = widthPx.toFloat(),
            maxHeightPx = maxHeightPx.toFloat(),
            preferredFontSp = preferredFontSp,
            measure = { fontSp, maxWidthPx -> measureBlock(text, fontSp, maxWidthPx) },
            onCapProbe = ::logNativeFontCap,
        )
    }

    // Nouzový post-řez (audit: "TO JSEM NEMYSL." uříznuté klipou uprostřed glyfu) - když
    // se ani na podlaze nevejde, zkrátit slova od konce s výpustkou místo viditelně
    // useknutého řádku. Celý originál je pořád o klepnutí daleko (flip na originál).
    val fittedText = remember(text, fitResult.fontSp, widthPx, maxHeightPx) {
        val candidate = truncateToFit(text) { c ->
            val m = measureBlock(c, fitResult.fontSp, widthPx.toFloat())
            m.totalHeightPx <= maxHeightPx.toFloat() &&
                m.longestWordWidthPx <= widthPx.toFloat() + 0.5f &&
                !m.hasMidWordBreak
        }
        // Poslední pojistka proti zlomu slova uprostřed: když fitter skončil na podlaze a
        // ani tak se nejdelší slovo nevejde do šířky (nebo měřič v layoutu pořád láme
        // slova mimo označené zlomy - hasMidWordBreak), Compose ho při vykreslení nouzově
        // láme po písmenech (audit Vagabondu: úzký varovný proužek vypsal "VAROV"/"ÁNÍ"/
        // "RODIČ"/"E" svisle). truncateToFit tady už nepomůže - strukturovaný text s "\n"
        // ani samotné slovo se zkrátit nedají. Radši samotná výpustka než nečitelné písmo;
        // celý originál je pořád na klepnutí (flip).
        if (fitResult.fontSp <= minFontSp) {
            val floorMeasure = measureBlock(candidate, fitResult.fontSp, widthPx.toFloat())
            if (floorMeasure.longestWordWidthPx > widthPx.toFloat() + 0.5f ||
                floorMeasure.hasMidWordBreak
            ) "…" else candidate
        } else candidate
    }

    Box(
        modifier = Modifier.width(boxWidth).offset(x = offsetX, y = offsetY),
        contentAlignment = Alignment.Center,
    ) {
        StrokedTranslatedText(
            text = fittedText,
            fontSp = fitResult.fontSp,
            fontFamily = fontFamily,
            textColor = textColor,
            strokeColor = strokeColor,
            // Zalamování se měří proti stejné šířce, jakou použil fitter (viz measureBlock) -
            // jinak by text mohl zalomit jinak než při výběru velikosti.
            maxWidthPx = widthPx.toFloat(),
        )
    }
}

/** Podíl velikosti písma použitý jako šířka obrysu (viz [StrokedTranslatedText]), s dolní hranicí 2.dp pro malá písmena, kde by procentuální obrys byl neviditelně tenký. */
private const val STROKE_WIDTH_FACTOR = 0.12f

// Poznámka pro budoucí ladění: NENASTAVOVAT tady TextStyle.hyphens. Nabízí se to - výchozí
// hodnota je Hyphens.None, což zní jako "měkké rozdělovníky se ignorují", a vysvětlovalo by to
// slova zlomená uprostřed. Změřeno na zařízení (SoftHyphenRenderingOnDeviceTest): Android
// respektuje U+00AD při zalamování i s Hyphens.None, takže Hyphens.Auto je no-op, který navíc
// zapne automatické dělení podle jazykových vzorů. Zlomy uprostřed slova mají jinou příčinu -
// špatně umístěný rozdělovník od modelu, viz [com.haise.jiyu.translate.isValidSyllableBreaks].

/**
 * Vykreslí text DVAKRÁT přes sebe - nejdřív obrysovou vrstvu (opačná barva než výplň podle
 * jasu pozadí), pak výplň navrch - pro čitelnost přes komplexní/vzorované pozadí bubliny.
 *
 * Přes jeden změřený [TextLayoutResult] nakreslený dvakrát na Canvas - NIKDY přes dva
 * překrývající se Text composables. Dva Texty totiž nejsou pixel-align zaručené: Stroke
 * drawStyle nechává písmo "hubené" a fill Text v jiné composable pozici se umí o 1-3 px
 * rozejít (audit RWS ch.215 - "bílý duch pod černým textem", čtenářsky "napsané dvakrát").
 * Jeden layoutResult = stejné souřadnice glyfů pro oba průchody, rozjíždění je vyloučené.
 */
@Composable
private fun StrokedTranslatedText(
    text: String,
    fontSp: Float,
    fontFamily: FontFamily,
    textColor: Color,
    strokeColor: Color,
    preWrapped: Boolean = false,
    maxWidthPx: Float = Float.MAX_VALUE,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val strokeWidthPx = with(density) { maxOf(2.dp.toPx(), fontSp.sp.toPx() * STROKE_WIDTH_FACTOR) }

    // preWrapped: řádky už zalomila tvarová sazba ([fitTextToShape]) podle šířky bubliny v dané
    // výšce. Compose je nesmí zalamovat podruhé - softWrap=false + neomezená šířka nechá řádek
    // dopadnout přesně tak, jak byl navržen (jinak se slovo rozseklo uprostřed po písmenech -
    // "POSLEDNÍC"/"H"); obrys bubliny stejně ořezává BubbleClipShape.
    val layout = remember(text, fontSp, fontFamily, preWrapped, maxWidthPx) {
        textMeasurer.measure(
            text = text,
            style = TextStyle(
                fontSize = fontSp.sp,
                lineHeight = (fontSp * 1.25f).sp,
                fontFamily = fontFamily,
                // Každý řádek vlastní vycentrovaný (ne jen blok jako celek) - víceřádkový text
                // v bublině je jinak zarovnaný doleva a krajní řádky lepí/přetékají oblý okraj
                // bubliny (viz "K VEČEŘI..." uříznuté "K"). Centrování per-řádek odpovídá
                // klasickému komiksovému letteringu.
                textAlign = TextAlign.Center,
            ),
            softWrap = !preWrapped,
            overflow = TextOverflow.Visible,
            constraints = Constraints(
                maxWidth = if (maxWidthPx.isFinite()) {
                    // Stejná rezerva na obrys, jakou používá fitter (viz measureBlock) -
                    // jinak by zalamování vykresleného textu mohlo vyjít jinak než při
                    // výběru velikosti písma a krajní řádek by se přeskládal.
                    (maxWidthPx - strokeWidthPx).toInt().coerceAtLeast(1)
                } else {
                    Constraints.Infinity
                },
            ),
        )
    }
    val w = with(density) { layout.size.width.toDp() }
    val h = with(density) { layout.size.height.toDp() }
    Canvas(Modifier.size(w, h)) {
        drawText(
            textLayoutResult = layout,
            color = strokeColor,
            drawStyle = Stroke(width = strokeWidthPx, join = StrokeJoin.Round),
        )
        drawText(textLayoutResult = layout, color = textColor)
    }
}

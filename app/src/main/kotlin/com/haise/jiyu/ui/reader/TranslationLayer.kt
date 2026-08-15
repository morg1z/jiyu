package com.haise.jiyu.ui.reader

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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.translate.BubbleShapePoint
import com.haise.jiyu.translate.BubbleType
import com.haise.jiyu.translate.LineMetrics
import com.haise.jiyu.translate.PositionedTranslationBlock
import com.haise.jiyu.translate.TextMeasurement
import com.haise.jiyu.translate.TranslatedBlock
import com.haise.jiyu.translate.averageArgb
import com.haise.jiyu.translate.estimateNativeFontPx
import com.haise.jiyu.translate.fitFontSizeToBox
import com.haise.jiyu.translate.hasTranslatableLetters
import com.haise.jiyu.translate.fitTextToShape
import com.haise.jiyu.translate.largestInscribedRect
import com.haise.jiyu.translate.layoutTranslationBlocks
import com.haise.jiyu.translate.matchOriginalCase
import com.haise.jiyu.translate.minTranslationFontSp
import com.haise.jiyu.translate.renderBoxRect
import dagger.hilt.android.EntryPointAccessors
import com.haise.jiyu.translate.snapBubbleBg
import com.haise.jiyu.translate.tidyStrandedPunctuation

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
 * Vrstva všech (ne-SFX) přeložených bublin jedné stránky - jediné místo, odkud se volá
 * [TranslationOverlay], ať pro manga (MangaReader v ReaderPager.kt) nebo webtoon
 * (WebtoonPage ve WebtoonReader.kt) mód.
 *
 * @param pageIndex potřeba jen pro sestavení klíče "$pageIndex:$bubbleIndex" v [flippedBubbles]
 *   (viz ReaderViewModel.toggleBubbleFlip) - bubbleIndex je pozice bubliny v [positioned], ne
 *   v původním (nefiltrovaném) `blocks`.
 */
@Composable
fun BubbleOverlayLayer(
    blocks: List<TranslatedBlock>,
    imageRect: Rect,
    textScale: Float = 1f,
    pageIndex: Int = -1,
    /** URL zobrazované stránky - potřeba jen pro záplaty (viz [TextPatchProvider]). */
    pageUrl: String? = null,
    flippedBubbles: Set<String> = emptySet(),
    onToggleFlip: (pageIndex: Int, bubbleIndex: Int) -> Unit = { _, _ -> },
    onEditBubble: (pageIndex: Int, originalText: String, currentText: String) -> Unit = { _, _, _ -> },
) {
    val positioned = remember(blocks) { layoutTranslationBlocks(blocks) }

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
    val patches by produceState(initialValue = emptyMap<Int, android.graphics.Bitmap>(), pageUrl, blocks) {
        val url = pageUrl
        value = if (url == null) emptyMap() else patchProvider.patchesFor(url, positioned)
    }
    positioned.forEachIndexed { bubbleIndex, pos ->
        // isUntranslated = model vrátil UNTRANSLATED_MARKER (nečitelné OCR) - stejně jako u
        // SFX bublin appka radši nic nekreslí a nechá prosvítat originál, než aby ukázala
        // doslovný anglický placeholder tam, kde měl být český text (viz TranslateRepository).
        // Blok bez jediného písmene (samotná tečka/uvozovka) nemá co překládat a vykreslit ho jde
        // jen špatně - viz [hasTranslatableLetters]. Originál prosvítá, interpunkce tedy zůstane
        // přesně tam, kam ji nakreslil autor.
        if (!pos.block.isSfx && !pos.block.isUntranslated && hasTranslatableLetters(pos.block.displayText)) {
            TranslationOverlay(
                pos = pos,
                imageRect = imageRect,
                textScale = textScale,
                isFlipped = "$pageIndex:$bubbleIndex" in flippedBubbles,
                // Klíčem je pozice v `positioned` - stejně, jako je klíčuje TextPatchProvider.
                // Dřív se dohledávalo přes blocks.indexOf(pos.block), jenže dva shodné bloky
                // jsou si podle data class rovny a druhý z nich pak dostal cizí záplatu.
                patch = patches[bubbleIndex],
                onTap = { onToggleFlip(pageIndex, bubbleIndex) },
                onLongPress = {
                    onEditBubble(pageIndex, pos.block.originalText, pos.block.translatedText)
                },
            )
        }
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

        fun py(p: BubbleShapePoint) = ((p.yF - shapeTopF) / yRange) * size.height
        fun pxLeft(p: BubbleShapePoint) = ((p.leftF - leftMinF) / spanF) * size.width
        fun pxRight(p: BubbleShapePoint) = ((p.rightF - leftMinF) / spanF) * size.width

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
    val bleed = if (pos.block.shape != null || patch != null) 0.dp else TRANSLATION_BOX_BLEED
    // Jediný zdroj pravdy pro "jak velký kus stránky bublina zakryje" - stejnou funkci
    // používá TextPatchProvider, aby se obojí nemohlo rozejít.
    val box = renderBoxRect(pos)
    val left = (imageRect.left + imageRect.width * box.leftF).dp - bleed
    val top  = (imageRect.top + imageRect.height * box.topF).dp - bleed
    val w    = (imageRect.width * (box.rightF - box.leftF)).dp.coerceAtLeast(0.dp) + bleed * 2
    // maxBottomF je HORNÍ LIMIT růstu (může sahat až k dalšímu prvku na stránce, klidně přes
    // spoustu prázdného pozadí) - použít ho jako MINIMUM by box nutilo vyplnit i prázdný
    // prostor, kde žádný originál nebyl. Skutečné minimum je vlastní OCR rozsah bubliny
    // (block.bottomF, ne maxBottomF) - to jediné je potřeba zakrýt, aby nikde neprosvítal originál.
    val effectiveMinBottomF = pos.block.shape?.let { pos.maxBottomF } ?: pos.block.bottomF
    val minH = (imageRect.height * (effectiveMinBottomF - pos.minTopF)).dp.coerceAtLeast(0.dp) + bleed * 2
    val maxH = (imageRect.height * (pos.maxBottomF - pos.minTopF)).dp.coerceAtLeast(0.dp) + bleed * 2
    val clipShape = pos.block.shape?.let { BubbleClipShape(it, pos.minTopF, pos.maxBottomF) } ?: RoundedCornerShape(3.dp)
    // Svislý gradient (horní/dolní polovina vzorkovaného prstence, viz OcrEngine.sampleBackgroundColor)
    // místo jednolité barvy - obě strany se "přichytí" na bílou/černou nezávisle (snapBubbleBg),
    // takže obyčejné bubliny zůstávají plnou barvou stejně jako dřív, gradient se projeví jen
    // u barevných/stínovaných bublin, kde má reálný podklad.
    val snappedBgTop = snapBubbleBg(pos.block.bgColorArgb)
    val snappedBgBottom = snapBubbleBg(pos.block.bgColorBottomArgb)
    // tidyStrandedPunctuation: mezera před koncovou tečkou nabízí zalamovači zlom, po kterém
    // tečka zůstane sama na řádku - viz [tidyStrandedPunctuation] a nahlášené "ODLÉTÁME" + tečka.
    val displayText = tidyStrandedPunctuation(
        matchOriginalCase(pos.block.displayText, pos.block.originalText),
    )

    // Bezpečná plocha pro text uvnitř tvaru bubliny (viz [largestInscribedRect]) - největší
    // obdélník, který se celý vejde dovnitř obrysu. Text se sází do NĚJ, ne do celého
    // ohraničujícího obdélníku tvaru: díky tomu nemůže zasáhnout obrys ani v užších místech
    // (dvojkruhová bublina, zvlněný okraj) a nepotřebuje k tomu žádné vykreslování řádek po
    // řádku, které dřív působilo překrývající se řádky (viz uživatelská zpětná vazba).
    // Padding uvnitř plochy - obrys bubliny bývá nakreslený "tlustou" linkou a text nalepený
    // těsně na ni vypadá špatně i když technicky nepřetéká.
    val inscribed = pos.block.shape?.let { largestInscribedRect(it) }
    val textAreaWidth = inscribed
        ?.let { (imageRect.width * it.widthF * INSCRIBED_TEXT_AREA_FACTOR).dp }
        ?: w
    val textAreaHeight = inscribed
        ?.let { (imageRect.height * it.heightF * INSCRIBED_TEXT_AREA_FACTOR).dp }
        ?: maxH
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
                    if (patch != null) {
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
                .pointerInput(onTap, onLongPress) {
                    detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                }
                .padding(horizontal = TRANSLATION_TEXT_HORIZONTAL_PADDING, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isFlipped,
                transitionSpec = {
                    (scaleIn(initialScale = 0.85f) + fadeIn())
                        .togetherWith(scaleOut(targetScale = 0.85f) + fadeOut())
                },
                label = "bubble-flip",
            ) { flipped ->
                AutoFitTranslatedText(
                    text = if (flipped) pos.block.originalText else displayText,
                    // Volba barvy textu (podle jasu) potřebuje JEDNU barvu, ne gradient -
                    // průměr obou stran je dost přesný odhad pro čitelnost přes celou bublinu.
                    bgColorArgb = averageArgb(snappedBgTop, snappedBgBottom),
                    boxWidth = textAreaWidth,
                    maxHeight = textAreaHeight,
                    // Šířka VNĚJŠÍHO boxu - text se do ní musí vejít bez ohledu na to, jak
                    // široký je obrys bubliny (viz [fitTextToShape] parametr maxLineWidthPx).
                    renderWidth = w,
                    textScale = textScale,
                    bubbleType = pos.block.bubbleType,
                    offsetX = textOffsetX,
                    offsetY = textOffsetY,
                    shape = pos.block.shape,
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
 * Loguje, kdyz odhad nativni velikosti pisma ([estimateNativeFontPx]) narazil na strop sazby
 * a jestli pri tom v bublině zbylo nevyuzite misto (viz onCapProbe parametr [fitFontSizeToBox]/
 * [fitTextToShape]). Zatim cistě observabilita: `adb logcat -s NativeFontCap` pri beznem
 * cteni nasbira realnou distribuci, podle ktere se pomer box/font casem dolaci na datech.
 */
private fun logNativeFontCap(preferredFontSp: Float, roomToGrow: Boolean) {
    Log.d("NativeFontCap", "preferred=%.1fsp roomToGrow=%s".format(preferredFontSp, roomToGrow))
}

private fun fontFamilyFor(bubbleType: BubbleType): FontFamily = when (bubbleType) {
    BubbleType.SHOUT -> ComicNeueBold
    BubbleType.THOUGHT, BubbleType.WHISPER -> ComicNeueItalic
    BubbleType.SPEECH, BubbleType.NARRATION, BubbleType.SYSTEM, BubbleType.SFX -> ComicNeueRegular
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
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = fontFamilyFor(bubbleType)
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
    val bg = Color(bgColorArgb)
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val textColor = if (luminance < 0.5f) Color.White else Color.Black
    val strokeColor = if (luminance < 0.5f) Color.Black else Color.White

    // Kolik místa dostane SKUTEČNÝ Text composable: šířka vnějšího Boxu minus jeho vodorovný
    // padding. Obě sazební cesty (tvarová i obdélníková) musí počítat s tímhle číslem, ne s
    // geometrií obrysu - obrys bubliny bývá širší než box (hranatý popiskový rámeček pokrývá
    // celý šedý obdélník, box kopíruje jen užší OCR rozsah textu), a sazba podle obrysu pak
    // prošla kontrolou "slovo se vejde", jenže Compose měl při vykreslení míň místa a slovo
    // rozsekal po písmenech ("SPOLEČNOS" + "T", viz uživatelský screenshot).
    val renderableWidthPx = with(density) {
        (renderWidth - TRANSLATION_TEXT_HORIZONTAL_PADDING * 2).toPx()
    }.coerceAtLeast(1f)

    // ── Sazba do skutečného tvaru bubliny (vyvážené řádky, viz [fitTextToShape]) ──
    // Tohle je hlavní cesta pro bubliny se známým obrysem: každý řádek dostane šířku podle
    // tvaru ve svém pásu, takže v oválné bublině vyjde blok textu kosočtvercový (delší řádky
    // uprostřed) - přesně jak sází profesionální lettering - a využije se mnohem víc plochy
    // než u prostého vepsaného obdélníku. Řádky jdou do JEDNOHO Textu oddělené \n, takže
    // řádkování i centrování řeší Compose (žádné vykreslování řádek po řádku, které dřív
    // způsobovalo překrývající se řádky).
    val shapedLayout = if (shape != null && shapeCenterF != null && imageHeightDp > 0f) {
        val words = remember(text) { text.split(' ', '\n').filter { it.isNotBlank() } }
        remember(text, shape, shapeCenterF, shapeCenterYF, shapeTopF, shapeBottomF, imageWidthDp, imageHeightDp, maxFontSp, fontFamily, renderableWidthPx, preferredFontSp) {
            fitTextToShape(
                words = words,
                minFontSp = minFontSp,
                maxFontSp = maxFontSp,
                shape = shape,
                centerF = shapeCenterF,
                shapeTopF = shapeTopF,
                shapeBottomF = shapeBottomF,
                pageWidthPx = with(density) { imageWidthDp.dp.toPx() },
                pageHeightPx = with(density) { imageHeightDp.dp.toPx() },
                measureWord = { word, fontSp ->
                    val style = TextStyle(fontSize = fontSp.sp, fontFamily = fontFamily)
                    val strokeReserve = with(density) { maxOf(2.dp.toPx(), fontSp.sp.toPx() * STROKE_WIDTH_FACTOR) }
                    textMeasurer.measure(text = word, style = style, softWrap = false).size.width + strokeReserve
                },
                spaceWidth = { fontSp ->
                    val style = TextStyle(fontSize = fontSp.sp, fontFamily = fontFamily)
                    // Šířka mezery = rozdíl mezi "a a" a "aa" - měřit samotné " " je nespolehlivé,
                    // protože měřič koncové mezery ořezává.
                    val withSpace = textMeasurer.measure(text = "a a", style = style, softWrap = false).size.width
                    val without = textMeasurer.measure(text = "aa", style = style, softWrap = false).size.width
                    (withSpace - without).toFloat().coerceAtLeast(1f)
                },
                lineHeightPx = { fontSp -> with(density) { (fontSp * 1.25f).sp.toPx() } },
                maxLineWidthPx = renderableWidthPx,
                preferredFontSp = preferredFontSp,
                centerYF = shapeCenterYF,
                onCapProbe = ::logNativeFontCap,
            )
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
        val shapedOffsetY = ((shapedLayout.centerYF - (shapeTopF + shapeBottomF) / 2f) * imageHeightDp).dp
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
            )
        }
        return
    }
    // boxWidth je šířka VNĚJŠÍHO Boxu (viz volající) - Text uvnitř má reálně k dispozici
    // o horizontal padding Boxu (4.dp na každé straně) míň. Bez týhle korekce fitter vybíral
    // velikost písma, která se vejde do PLNÉ šířky boxu, ale skutečný Text dostal od Compose
    // užší constraint - řádek, co se těsně vešel do měření, se pak u reálného vykreslení
    // zalomil jinam a poslední slovo bylo uříznuté o okraj bubliny.
    // Strop [renderableWidthPx] je tu potřeba i navíc: u bubliny s tvarem, kde tvarová sazba
    // neuspěla a spadlo se sem, je boxWidth odvozené z vepsaného obdélníku OBRYSU, který může
    // být širší než skutečný box bubliny.
    val widthPx = (with(density) { boxWidth.roundToPx() } - with(density) { (TRANSLATION_TEXT_HORIZONTAL_PADDING * 2).roundToPx() })
        .coerceAtMost(renderableWidthPx.toInt())
        .coerceAtLeast(1)
    val maxHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

    val fitResult = remember(text, widthPx, maxHeightPx, maxFontSp, fontFamily, preferredFontSp) {
        fitFontSizeToBox(
            minFontSp = minFontSp,
            maxFontSp = maxFontSp,
            boxWidthPx = widthPx.toFloat(),
            maxHeightPx = maxHeightPx.toFloat(),
            preferredFontSp = preferredFontSp,
            measure = { fontSp, maxWidthPx ->
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
                val measured = textMeasurer.measure(text = text, style = style, constraints = Constraints(maxWidth = constraintWidth))
                val lines = (0 until measured.lineCount).map { i ->
                    LineMetrics(
                        widthPx = measured.getLineRight(i) - measured.getLineLeft(i),
                        topPx = measured.getLineTop(i),
                        bottomPx = measured.getLineBottom(i),
                    )
                }
                // Nejdelší JEDNOTLIVÉ slovo měřené BEZ šířkového omezení - jinak by ho Compose
                // sám zalomil a naměřená šířka by byla vždycky menší než limit, takže by
                // kontrola v fitFontSizeToBox nikdy nic nezachytila. Tohle je jediná obrana
                // proti tomu, aby se slovo rozsekalo uprostřed po písmenech ("KDYBYCH" ->
                // "KDYB"/"YCH", viz uživatelská zpětná vazba).
                val longestWordWidthPx = text.split(' ', '\n')
                    .filter { it.isNotBlank() }
                    .maxOfOrNull { word ->
                        textMeasurer.measure(text = word, style = style, softWrap = false).size.width.toFloat()
                    } ?: 0f
                TextMeasurement(
                    totalHeightPx = measured.size.height + strokeReservePx,
                    lines = lines,
                    longestWordWidthPx = longestWordWidthPx + strokeReservePx,
                )
            },
            onCapProbe = ::logNativeFontCap,
        )
    }

    Box(
        modifier = Modifier.width(boxWidth).offset(x = offsetX, y = offsetY),
        contentAlignment = Alignment.Center,
    ) {
        StrokedTranslatedText(
            text = text,
            fontSp = fitResult.fontSp,
            fontFamily = fontFamily,
            textColor = textColor,
            strokeColor = strokeColor,
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
 * [TextStyle.drawStyle] umí jen JEDEN styl na jedno volání Text (buď Fill, nebo Stroke), takže
 * obrys+výplň v jednom Text nejde - dvě překrývající se Text vrstvy jsou jednodušší a
 * spolehlivější než snaha o "vlastní" kreslení přes Canvas/drawWithContent.
 */
@Composable
private fun StrokedTranslatedText(
    text: String,
    fontSp: Float,
    fontFamily: FontFamily,
    textColor: Color,
    strokeColor: Color,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { maxOf(2.dp.toPx(), fontSp.sp.toPx() * STROKE_WIDTH_FACTOR) }

    Box(contentAlignment = Alignment.Center) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = strokeColor,
                fontSize = fontSp.sp,
                lineHeight = (fontSp * 1.25f).sp,
                fontFamily = fontFamily,
                drawStyle = Stroke(width = strokeWidthPx, join = StrokeJoin.Round),
            ),
        )
        Text(
            text = text,
            color = textColor,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.25f).sp,
            fontFamily = fontFamily,
            // Každý řádek vlastní vycentrovaný (ne jen blok jako celek) - víceřádkový text
            // v bublině je jinak zarovnaný doleva a krajní řádky lepí/přetékají oblý okraj
            // bubliny (viz "K VEČEŘI..." uříznuté "K"). Centrování per-řádek odpovídá
            // klasickému komiksovému letteringu.
            textAlign = TextAlign.Center,
        )
    }
}

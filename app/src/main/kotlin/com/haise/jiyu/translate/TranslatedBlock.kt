package com.haise.jiyu.translate

/** Bílá - výchozí barva boxu, když se nepodaří nasamplovat pozadí bubliny z bitmapy. */
const val DEFAULT_BUBBLE_BG_ARGB: Int = android.graphics.Color.WHITE

/**
 * Jeden přeložený textový blok (bublina) na stránce mangy.
 * Souřadnice jsou relativní (0.0–1.0) vůči rozměrům obrázku,
 * takže fungují nezávisle na rozlišení displeje.
 *
 * @param displayText [translatedText] s měkkými rozdělovníky (viz [GeminiUltraPrompt]) na
 *   platných slabičných hranicích - render (viz ReaderScreen.kt) by měl zalamovat text
 *   podle tohohle pole, ne podle [translatedText], jinak Compose zalomí kdekoliv se vejde.
 * @param bgColorArgb barva pozadí HORNÍ poloviny bubliny nasamplovaná z originálního obrázku
 *   (viz [OcrEngine.sampleBackgroundColor]) - box pak vizuálně splyne s bublinou místo pevně
 *   bílé, která na barevných/šrafovaných bublinách nechávala prosvítat okraj originálu.
 * @param bgColorBottomArgb barva pozadí DOLNÍ poloviny - společně s [bgColorArgb] tvoří
 *   svislý gradient výplně místo jednolité barvy (viz TranslationOverlay). Výchozí hodnota
 *   (stejná jako [bgColorArgb]) degraduje na plnou barvu - staré cache záznamy bez tohohle
 *   pole tak vypadají přesně jako dřív, dokud se stránka znovu nepřeloží.
 * @param isSfx zvukový efekt - viz [BubbleClassifier]; render mu nedává box, jen ho nechá být.
 * @param lineCount kolik OCR řádků bylo sloučeno do téhle bubliny - signál pro
 *   [layoutTranslationBlocks], jestli má smysl box roztahovat nahoru (viz [PositionedTranslationBlock.minTopF]).
 * @param shape skutečný obrys bubliny (flood-fill) - viz [BubbleShapeDetector]. Null =
 *   detekce selhala nebo starý cache formát, [layoutTranslationBlocks] pak použije
 *   heuristický obdélník místo přesného tvaru.
 * @param bubbleType typ bubliny (SPEECH/SHOUT/THOUGHT/...) - viz [BubbleClassifier]. Určuje
 *   řez písma v ReaderScreen.kt (fontFamilyFor).
 * @param isUntranslated model vrátil [GeminiUltraPrompt.UNTRANSLATED_MARKER] (nečitelné OCR/
 *   nesmyslný text) místo skutečného překladu - [BubbleOverlayLayer] takovou bublinu vůbec
 *   nevykresluje (stejně jako SFX), aby čtenář neviděl doslovný anglický placeholder tam,
 *   kde měl být český text.
 * @param bgUniform false = pozadí kolem textu je barevně nesourodé (typicky titulkový/
 *   dekorativní text napsaný přímo přes kresbu, ne v nakreslené bublině) - viz
 *   [OcrEngine.sampleBackgroundColor]. [layoutTranslationBlocks] u takového bloku (bez
 *   detekovaného tvaru) neroztahuje heuristický box tak štědře jako u skutečné bubliny,
 *   protože barevná výplň tam beztak nikdy nesplyne s pestrým okolím.
 * @param nativeLineHeightF průměrná výška JEDNOHO řádku originálního textu (zlomek výšky
 *   stránky), zjištěná z OCR ještě před sloučením řádků do bubliny - viz [mergeNearbyLines].
 *   0f = neznámé (starý cache záznam bez tohohle pole). Render z toho odvodí velikost
 *   písma, jakou měl originál, a zkusí ji jako první volbu místo rovnou hledat největší
 *   velikost, co se vejde - překlad tak vizuálně sedí na originální lettering, dokud se
 *   do bubliny vejde (viz [fitFontSizeToBox]/[fitTextToShape] parametr preferredFontSp).
 */
data class TranslatedBlock(
    val originalText: String,
    val translatedText: String,
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val bottomF: Float,
    val displayText: String = translatedText,
    val bgColorArgb: Int = DEFAULT_BUBBLE_BG_ARGB,
    val bgColorBottomArgb: Int = bgColorArgb,
    val isSfx: Boolean = false,
    val lineCount: Int = 1,
    val shape: List<BubbleShapePoint>? = null,
    val bubbleType: BubbleType = BubbleType.SPEECH,
    val isUntranslated: Boolean = false,
    val bgUniform: Boolean = true,
    val nativeLineHeightF: Float = 0f,
    /**
     * Ruční posun vykresleného boxu bubliny v Dp - viz
     * [com.haise.jiyu.data.db.entity.ManualTranslationEntity.offsetXDp]/`offsetYDp` (odkud se
     * napaří přes [applyManualPositionOffsets]) a [com.haise.jiyu.ui.reader.TranslationOverlay],
     * které o tuhle hodnotu posune vykreslený box PROTI vypočtené pozici. 0f (výchozí) = beze
     * změny - drtivá většina bublin nikdy ruční posun nedostane.
     */
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
)

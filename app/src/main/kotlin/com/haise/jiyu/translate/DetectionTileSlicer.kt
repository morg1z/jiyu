package com.haise.jiyu.translate

import android.graphics.Bitmap

/**
 * Řeže extrémně vysoké stránky (dlouhé webtoon/manhwa pruhy) na překrývající se vodorovné
 * díly PŘED detekcí bublin, aby [BubbleBoxDetector] neviděl jen jeden zdegenerovaný
 * čtvercový "letterbox" celé stránky - `letterboxParams`/`YoloPreprocessing` vždycky zmenší
 * vstup na čtverec 640×640 se zachováním poměru stran, takže na stránce s poměrem stran
 * 1:15 (typický dlouhý webtoon pruh) by bublina vysoká pár desítek pixelů na originále
 * vyšla ve vstupu modelu jen jako pár pixelů - pod rozlišením, na kterém byl model
 * trénovaný. Rozdělením na díly s mírnějším poměrem stran (viz [SLICE_ASPECT_RATIO]) zůstává
 * bublina v každém dílu dost velká na spolehlivou detekci.
 *
 * Přesah mezi sousedními díly (viz [SLICE_OVERLAP_FRACTION]) řeší bublinu, která by jinak
 * padla přesně na hranici řezu a byla by v obou dílech oříznutá napůl - taková bublina se
 * najde CELÁ aspoň v jednom z překrývajících se dílů. Duplicitní detekce téže bubliny z obou
 * dílů se pak sloučí přes IoU ([deduplicateByIou]), stejný princip jako NMS v
 * [nonMaxSuppression], jen nad už přepočtenými normalizovanými souřadnicemi CELÉ stránky
 * místo souřadnic jednoho vstupního tenzoru.
 *
 * Čistá matematika (dělení na díly/přepočet souřadnic/dedup) je oddělená do samostatných
 * funkcí bez Androidu, testovatelná JVM testem - stejný vzor jako [YoloDetectionDecode.kt].
 * Jediná funkce, která se dotýká [Bitmap] ([detectWithTallImageSlicing]), je tenký obal, který
 * nejde JVM testem spustit.
 *
 * Pozn.: nejde zaměnit s [com.haise.jiyu.util.TallImageSlicer] - ten řeší JINÝ problém
 * (rozdělení na nepřekrývající se díly kvůli limitu velikosti GPU textury PŘI VYKRESLOVÁNÍ),
 * ne detekci bublin. Schválně jiný název i balíček, aby se nepletly.
 */

/** true, když je stránka natolik vysoká vůči šířce, že se vyplatí ji před detekcí rozřezat. */
internal fun shouldSliceForDetection(widthPx: Int, heightPx: Int, aspectThreshold: Float = TALL_IMAGE_ASPECT_THRESHOLD): Boolean {
    if (widthPx <= 0 || heightPx <= 0) return false
    return heightPx.toFloat() / widthPx.toFloat() >= aspectThreshold
}

/** Jeden vodorovný díl stránky - pixelový rozsah `[topPxIncl, bottomPxExcl)`. */
internal data class SliceRegion(val topPxIncl: Int, val bottomPxExcl: Int)

/**
 * Rozdělí výšku [imageHeightPx] na díly výšky [sliceHeightPx] s přesahem [overlapFraction]
 * (0..1, podíl [sliceHeightPx]) mezi sousedními díly. Poslední díl se zkrátí, aby nepřesáhl
 * [imageHeightPx] - NEROZTAHUJE se za konec stránky.
 */
internal fun computeSliceRegions(imageHeightPx: Int, sliceHeightPx: Int, overlapFraction: Float): List<SliceRegion> {
    require(imageHeightPx > 0) { "imageHeightPx must be positive: $imageHeightPx" }
    require(sliceHeightPx > 0) { "sliceHeightPx must be positive: $sliceHeightPx" }
    if (sliceHeightPx >= imageHeightPx) return listOf(SliceRegion(0, imageHeightPx))

    val step = (sliceHeightPx * (1f - overlapFraction.coerceIn(0f, 0.9f))).toInt().coerceAtLeast(1)
    val regions = mutableListOf<SliceRegion>()
    var top = 0
    while (top < imageHeightPx) {
        val bottom = minOf(top + sliceHeightPx, imageHeightPx)
        regions += SliceRegion(top, bottom)
        if (bottom >= imageHeightPx) break
        top += step
    }
    return regions
}

/** Přepočte detekce z normalizovaných souřadnic JEDNOHO dílu na normalizované souřadnice CELÉ stránky. */
internal fun remapSliceDetections(
    detections: List<DetectedBubbleBox>,
    region: SliceRegion,
    fullImageHeightPx: Int,
): List<DetectedBubbleBox> {
    val sliceHeightPx = (region.bottomPxExcl - region.topPxIncl).toFloat()
    return detections.map { box ->
        val topPxInFull = region.topPxIncl + box.topF * sliceHeightPx
        val bottomPxInFull = region.topPxIncl + box.bottomF * sliceHeightPx
        box.copy(
            topF = (topPxInFull / fullImageHeightPx).coerceIn(0f, 1f),
            bottomF = (bottomPxInFull / fullImageHeightPx).coerceIn(0f, 1f),
        )
    }
}

/**
 * Sloučí detekce ze sousedních (překrývajících se) dílů, které ve skutečnosti popisují
 * STEJNOU bublinu - stejný greedy princip jako [nonMaxSuppression], ale nad normalizovanými
 * souřadnicemi CELÉ stránky (`leftF/topF/rightF/bottomF`), ne pixely jednoho tenzoru.
 */
internal fun deduplicateByIou(boxes: List<DetectedBubbleBox>, iouThreshold: Float = 0.5f): List<DetectedBubbleBox> {
    val sorted = boxes.sortedByDescending { it.score }.toMutableList()
    val kept = mutableListOf<DetectedBubbleBox>()
    while (sorted.isNotEmpty()) {
        val best = sorted.removeAt(0)
        kept += best
        sorted.removeAll { normalizedIou(best, it) > iouThreshold }
    }
    return kept
}

private fun normalizedIou(a: DetectedBubbleBox, b: DetectedBubbleBox): Float {
    val interLeft = maxOf(a.leftF, b.leftF)
    val interTop = maxOf(a.topF, b.topF)
    val interRight = minOf(a.rightF, b.rightF)
    val interBottom = minOf(a.bottomF, b.bottomF)
    val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
    val areaA = maxOf(0f, a.rightF - a.leftF) * maxOf(0f, a.bottomF - a.topF)
    val areaB = maxOf(0f, b.rightF - b.leftF) * maxOf(0f, b.bottomF - b.topF)
    val union = areaA + areaB - interArea
    return if (union <= 0f) 0f else interArea / union
}

/**
 * Vstupní bod pro [OcrEngine] - detekuje na CELÉ bitmapě, pokud je "normální" poměr stran,
 * jinak ji rozřeže (viz [shouldSliceForDetection]/[computeSliceRegions]), spustí [detectOnBitmap]
 * na každém dílu zvlášť a výsledky sloučí zpátky do jednoho seznamu v souřadnicích CELÉ stránky.
 *
 * @param detectOnBitmap skutečná detekce JEDNOHO obrázku (dílu nebo celé stránky) - v produkci
 *   [BubbleBoxDetector.detect], v testu falešná funkce (stejný testovatelný vzor jako
 *   `resolveAutoLanguage`/`isWrongTargetLanguage` jinde v modulu).
 */
internal suspend fun detectWithTallImageSlicing(
    bitmap: Bitmap,
    detectOnBitmap: suspend (Bitmap) -> List<DetectedBubbleBox>,
): List<DetectedBubbleBox> {
    if (!shouldSliceForDetection(bitmap.width, bitmap.height)) return detectOnBitmap(bitmap)

    val sliceHeightPx = (bitmap.width * SLICE_ASPECT_RATIO).toInt().coerceAtLeast(1)
    val regions = computeSliceRegions(bitmap.height, sliceHeightPx, SLICE_OVERLAP_FRACTION)
    val allDetections = mutableListOf<DetectedBubbleBox>()
    for (region in regions) {
        val slice = Bitmap.createBitmap(bitmap, 0, region.topPxIncl, bitmap.width, region.bottomPxExcl - region.topPxIncl)
        try {
            allDetections += remapSliceDetections(detectOnBitmap(slice), region, bitmap.height)
        } finally {
            slice.recycle()
        }
    }
    return deduplicateByIou(allDetections)
}

/** Nad tímhle poměrem výška/šířka se stránka před detekcí rozřeže - viz [shouldSliceForDetection]. */
internal const val TALL_IMAGE_ASPECT_THRESHOLD = 3.5f

/** Cílový poměr stran JEDNOHO dílu (výška = šířka × tohle) - viz [detectWithTallImageSlicing]. */
internal const val SLICE_ASPECT_RATIO = 3.0f

/** Přesah mezi sousedními díly jako podíl výšky dílu - viz [computeSliceRegions]. */
internal const val SLICE_OVERLAP_FRACTION = 0.2f

package com.haise.jiyu.translate

/**
 * Čisté (bez Androidu/ONNX Runtime) dekódování YOLOv8 detekčního výstupu - viz [BubbleBoxDetector],
 * který tohle volá po skutečné inferenci. Odděleno schválně, aby šlo otestovat JVM testem na
 * syntetických tenzorech, bez nutnosti mít na stroji reálný model nebo Android.
 */

/** Jedna surová detekce v prostoru VSTUPNÍHO tenzoru modelu (0..inputSize, ne stránky). */
internal data class RawBoxDetection(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
    val classId: Int,
    val score: Float,
)

/**
 * Detekovaná bublina/text v normalizovaných (0..1) souřadnicích PŮVODNÍ stránky - výstup
 * [BubbleBoxDetector], nezávislý na tom, jak appka jinak odvozuje polohu bubliny z OCR.
 *
 * @param classId třída modelu: 0 = "text_bubble" (text uvnitř bublinového obrysu),
 *   1 = "text_free" (volný text bez obrysu - popisek, SFX, systémové okno).
 */
data class DetectedBubbleBox(
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val bottomF: Float,
    val classId: Int,
    val score: Float,
)

/**
 * Rozbalí YOLOv8 "output0" tenzor (tvar `[1, 4+numClasses, numAnchors]`, kanál-po-kanálu,
 * ne anchor-po-anchor) do seznamu detekcí nad prahem [confThreshold].
 *
 * Model exportovaný přes `ultralytics.YOLO.export(format="onnx")` (bez `nms=True`) už má box
 * souřadnice DEKÓDOVANÉ do (cx, cy, w, h) v prostoru vstupního obrázku a class skóre PO
 * sigmoidu - appka na výstupu nic dalšího nedopočítává, jen vybírá nejlepší třídu na anchor
 * a filtruje prahem. Ověřeno srovnáním s `ultralytics.YOLO.predict()` na reálné stránce
 * (viz PR popis) - stejné boxy, stejná skóre.
 *
 * @param output plochý tenzor délky `(4 + numClasses) * numAnchors`
 */
internal fun decodeYoloOutput(
    output: FloatArray,
    numAnchors: Int,
    numClasses: Int,
    confThreshold: Float,
): List<RawBoxDetection> {
    require(output.size >= (4 + numClasses) * numAnchors) {
        "output too short: ${output.size} < ${(4 + numClasses) * numAnchors}"
    }
    val results = mutableListOf<RawBoxDetection>()
    for (a in 0 until numAnchors) {
        val cx = output[0 * numAnchors + a]
        val cy = output[1 * numAnchors + a]
        val w = output[2 * numAnchors + a]
        val h = output[3 * numAnchors + a]

        var bestClass = -1
        var bestScore = 0f
        for (c in 0 until numClasses) {
            val s = output[(4 + c) * numAnchors + a]
            if (s > bestScore) {
                bestScore = s
                bestClass = c
            }
        }
        if (bestClass < 0 || bestScore < confThreshold) continue

        results += RawBoxDetection(
            leftPx = cx - w / 2f,
            topPx = cy - h / 2f,
            rightPx = cx + w / 2f,
            bottomPx = cy + h / 2f,
            classId = bestClass,
            score = bestScore,
        )
    }
    return results
}

/**
 * Standardní greedy non-max suppression: seřadí podle skóre a odstraní vše, co se s už
 * vybranou detekcí překrývá nad [iouThreshold] - stejný jeden detekovaný objekt jinak vyjde
 * jako desítky skoro identických anchor-boxů.
 */
internal fun nonMaxSuppression(
    detections: List<RawBoxDetection>,
    iouThreshold: Float = 0.45f,
): List<RawBoxDetection> {
    val sorted = detections.sortedByDescending { it.score }.toMutableList()
    val kept = mutableListOf<RawBoxDetection>()
    while (sorted.isNotEmpty()) {
        val best = sorted.removeAt(0)
        kept += best
        sorted.removeAll { iou(best, it) > iouThreshold }
    }
    return kept
}

internal fun iou(a: RawBoxDetection, b: RawBoxDetection): Float {
    val interLeft = maxOf(a.leftPx, b.leftPx)
    val interTop = maxOf(a.topPx, b.topPx)
    val interRight = minOf(a.rightPx, b.rightPx)
    val interBottom = minOf(a.bottomPx, b.bottomPx)
    val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
    val areaA = maxOf(0f, a.rightPx - a.leftPx) * maxOf(0f, a.bottomPx - a.topPx)
    val areaB = maxOf(0f, b.rightPx - b.leftPx) * maxOf(0f, b.bottomPx - b.topPx)
    val union = areaA + areaB - interArea
    return if (union <= 0f) 0f else interArea / union
}

/**
 * Parametry "letterboxu" - standardní YOLO preprocessing, který obrázek zmenší se zachováním
 * poměru stran na [scale] a doplní šedým okrajem, aby výsledek byl přesně čtvercový vstup
 * modelu. Bez zpětného přepočtu by souřadnice detekcí odpovídaly ořezanému/deformovanému
 * obrázku, ne skutečné stránce.
 */
internal data class LetterboxParams(val scale: Float, val padX: Float, val padY: Float)

internal fun letterboxParams(srcWidth: Int, srcHeight: Int, targetSize: Int): LetterboxParams {
    require(srcWidth > 0 && srcHeight > 0 && targetSize > 0)
    val scale = minOf(targetSize.toFloat() / srcWidth, targetSize.toFloat() / srcHeight)
    val newW = srcWidth * scale
    val newH = srcHeight * scale
    val padX = (targetSize - newW) / 2f
    val padY = (targetSize - newH) / 2f
    return LetterboxParams(scale, padX, padY)
}

/** Přepočte detekci z prostoru letterboxovaného vstupu zpátky na normalizované souřadnice PŮVODNÍ stránky. */
internal fun RawBoxDetection.toPageNormalized(
    params: LetterboxParams,
    srcWidth: Int,
    srcHeight: Int,
): DetectedBubbleBox {
    val left = ((leftPx - params.padX) / params.scale).coerceIn(0f, srcWidth.toFloat())
    val top = ((topPx - params.padY) / params.scale).coerceIn(0f, srcHeight.toFloat())
    val right = ((rightPx - params.padX) / params.scale).coerceIn(0f, srcWidth.toFloat())
    val bottom = ((bottomPx - params.padY) / params.scale).coerceIn(0f, srcHeight.toFloat())
    return DetectedBubbleBox(
        leftF = left / srcWidth,
        topF = top / srcHeight,
        rightF = right / srcWidth,
        bottomF = bottom / srcHeight,
        classId = classId,
        score = score,
    )
}

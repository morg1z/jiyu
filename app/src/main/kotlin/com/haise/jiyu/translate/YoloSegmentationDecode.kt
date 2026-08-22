package com.haise.jiyu.translate

import kotlin.math.exp

/**
 * Čisté (bez Androidu/ONNX Runtime) dekódování YOLOv8-SEG výstupu - stejný princip jako
 * [YoloDetectionDecode.kt], jen navíc s maskovými koeficienty a rekonstrukcí masky z
 * prototypů. Viz [BubbleMaskSegmenter], který tohle volá po skutečné inferenci GPL modelu
 * (assets/models/NOTICE.md) - poslední záchrana pro tvar bubliny, když selže [BubbleShapeDetector]
 * i [BubbleShapeDetector.edgeAwareShape] (třída bugů "OKÖ!").
 *
 * YOLOv8-SEG má DVA výstupy:
 * - "output0" (`[1, 4+numClasses+numMaskCoeffs, numAnchors]`) - stejný box+class formát jako
 *   detekční model, plus [numMaskCoeffs] koeficientů na anchor.
 * - "output1" (`[1, numMaskCoeffs, protoH, protoW]`) - "prototypové" masky ve zmenšeném
 *   rozlišení (typicky 1/4 vstupu, tedy 160x160 pro 640 vstup).
 *
 * Skutečná binární maska JEDNÉ detekce = sigmoid(lineární kombinace prototypů podle jejích
 * koeficientů), prahovaná na 0.5 - viz [reconstructMask].
 */

/** Jedna surová segmentační detekce - box+třída jako [RawBoxDetection], plus koeficienty masky. */
internal data class RawSegDetection(
    val box: RawBoxDetection,
    val maskCoeffs: FloatArray,
)

/**
 * Rozbalí "output0" YOLOv8-SEG tenzor. Stejná logika jako [decodeYoloOutput], jen si navíc
 * odnese [numMaskCoeffs] koeficientů za posledními třídami kanálu.
 */
internal fun decodeYoloSegOutput(
    output: FloatArray,
    numAnchors: Int,
    numClasses: Int,
    numMaskCoeffs: Int,
    confThreshold: Float,
): List<RawSegDetection> {
    val channels = 4 + numClasses + numMaskCoeffs
    require(output.size >= channels * numAnchors) {
        "output too short: ${output.size} < ${channels * numAnchors}"
    }
    val results = mutableListOf<RawSegDetection>()
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

        val coeffs = FloatArray(numMaskCoeffs) { k -> output[(4 + numClasses + k) * numAnchors + a] }
        results += RawSegDetection(
            box = RawBoxDetection(
                leftPx = cx - w / 2f,
                topPx = cy - h / 2f,
                rightPx = cx + w / 2f,
                bottomPx = cy + h / 2f,
                classId = bestClass,
                score = bestScore,
            ),
            maskCoeffs = coeffs,
        )
    }
    return results
}

/** NMS nad segmentačními detekcemi - stejné pravidlo jako [nonMaxSuppression], jen na `.box`. */
internal fun nonMaxSuppressionSeg(
    detections: List<RawSegDetection>,
    iouThreshold: Float = 0.45f,
): List<RawSegDetection> {
    val sorted = detections.sortedByDescending { it.box.score }.toMutableList()
    val kept = mutableListOf<RawSegDetection>()
    while (sorted.isNotEmpty()) {
        val best = sorted.removeAt(0)
        kept += best
        sorted.removeAll { iou(best.box, it.box) > iouThreshold }
    }
    return kept
}

private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

/**
 * Rekonstruuje binární masku JEDNÉ detekce z jejích koeficientů a prototypů ("output1").
 *
 * @param prototypes plochý tenzor `[numMaskCoeffs][protoH][protoW]`, kanál-po-kanálu (stejný
 *   layout jako detekční výstup - viz [BubbleMaskSegmenter.flattenOutput])
 * @return `protoH * protoW` bitová maska (řádek po řádku), `true` = pixel uvnitř bubliny
 */
internal fun reconstructMask(
    maskCoeffs: FloatArray,
    prototypes: FloatArray,
    protoH: Int,
    protoW: Int,
    threshold: Float = 0.5f,
): BooleanArray {
    val numCoeffs = maskCoeffs.size
    val planeSize = protoH * protoW
    require(prototypes.size >= numCoeffs * planeSize) {
        "prototypes too short: ${prototypes.size} < ${numCoeffs * planeSize}"
    }
    val mask = BooleanArray(planeSize)
    for (p in 0 until planeSize) {
        var sum = 0f
        for (k in 0 until numCoeffs) {
            sum += maskCoeffs[k] * prototypes[k * planeSize + p]
        }
        mask[p] = sigmoid(sum) > threshold
    }
    return mask
}

/**
 * Převede binární masku (v prototypové mřížce, viz [reconstructMask]) na [BubbleShapePoint]
 * obrys ve stejném formátu, jaký vrací [BubbleShapeDetector.detectShape] - per-row levý/pravý
 * okraj vzorkovaný na [sampleCount] výškách, normalizovaný na PŮVODNÍ stránku.
 *
 * @param protoScale kolikrát je prototypová mřížka menší než letterboxovaný vstup modelu
 *   (typicky 4 - 640 vstup / 160 prototyp)
 * @return null, když maska nemá žádný pravdivý pixel (detekce bez skutečné plochy)
 */
internal fun maskToShapePoints(
    mask: BooleanArray,
    maskW: Int,
    maskH: Int,
    protoScale: Int,
    letterbox: LetterboxParams,
    srcWidth: Int,
    srcHeight: Int,
    sampleCount: Int = 24,
): List<BubbleShapePoint>? {
    require(mask.size >= maskW * maskH)
    val rowMin = IntArray(maskH) { Int.MAX_VALUE }
    val rowMax = IntArray(maskH) { Int.MIN_VALUE }
    var topY = Int.MAX_VALUE
    var bottomY = Int.MIN_VALUE
    for (y in 0 until maskH) {
        for (x in 0 until maskW) {
            if (!mask[y * maskW + x]) continue
            if (x < rowMin[y]) rowMin[y] = x
            if (x > rowMax[y]) rowMax[y] = x
            if (y < topY) topY = y
            if (y > bottomY) bottomY = y
        }
    }
    if (topY == Int.MAX_VALUE || bottomY < topY) return null

    val rowsWithData = (topY..bottomY).filter { rowMin[it] != Int.MAX_VALUE }
    if (rowsWithData.isEmpty()) return null

    /** Souřadnice v prototypové mřížce -> normalizované (0..1) souřadnice PŮVODNÍ stránky. */
    fun toPageX(maskX: Int): Float {
        val inputPx = maskX * protoScale
        return (((inputPx - letterbox.padX) / letterbox.scale).coerceIn(0f, srcWidth.toFloat())) / srcWidth
    }
    fun toPageY(maskY: Int): Float {
        val inputPx = maskY * protoScale
        return (((inputPx - letterbox.padY) / letterbox.scale).coerceIn(0f, srcHeight.toFloat())) / srcHeight
    }

    return (0 until sampleCount).map { i ->
        val frac = i / (sampleCount - 1).toFloat()
        val targetY = (topY + frac * (bottomY - topY)).toInt().coerceIn(topY, bottomY)
        val nearestY = nearestRowIndex(rowsWithData, targetY)
        BubbleShapePoint(
            yF = toPageY(nearestY),
            leftF = toPageX(rowMin[nearestY]),
            rightF = toPageX(rowMax[nearestY] + 1), // +1: pravy okraj pixelu, ne jeho stred
        )
    }
}

/** Binární hledání nejbližšího řádku s daty - stejný princip jako [BubbleShapeDetector]. */
private fun nearestRowIndex(sortedRows: List<Int>, target: Int): Int {
    var lo = 0
    var hi = sortedRows.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (sortedRows[mid] < target) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && Math.abs(sortedRows[lo - 1] - target) <= Math.abs(sortedRows[lo] - target)) return sortedRows[lo - 1]
    return sortedRows[lo]
}

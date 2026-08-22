package com.haise.jiyu.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.haise.jiyu.util.report
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * POSLEDNÍ záchranná záloha pro tvar bubliny - GPL-3.0 model (viz assets/models/NOTICE.md,
 * sekce "bubble_mask_segmenter.onnx" s DŮLEŽITÝM upozorněním na licenční dopad). Volá se
 * VÝHRADNĚ, když selže jak [BubbleShapeDetector.detectShape], tak
 * [BubbleShapeDetector.edgeAwareShape] - ne pro každou bublinu.
 *
 * Na rozdíl od [BubbleBoxDetector] (obdélníky přes celou stránku) vrací přímo pixelovou masku
 * tvaru KONKRÉTNÍ bubliny, takže může uspět i tam, kde klasický flood-fill nenajde uzavřenou
 * barevnou hranici (bublina s hranatým/"impact" obrysem - třída bugů "OKÖ!").
 *
 * Rozdělené na [detectPage] (jedna ONNX inference nad celou bitmapou) a [matchShape] (čisté
 * párování konkrétního OCR boxu proti už hotovým detekcím) SCHVÁLNĚ - když na jedné stránce
 * potřebuje zálohu víc bublin najednou, volající (viz [OcrEngine]) zavolá [detectPage] jen
 * JEDNOU a výsledek použije pro všechny. Původní jednokrokové API spouštělo celou ~100MB
 * inferenci znovu pro KAŽDOU bublinu zvlášť, což na přeplněné stránce s víc "hranatými"
 * bublinami hrozilo vyčerpáním sdíleného OCR timeoutu (viz TranslateRepository).
 *
 * Čistá matematika (dekódování, NMS, rekonstrukce masky z prototypů) žije v
 * [YoloSegmentationDecode.kt] a je testovaná JVM testy nezávisle na týhle třídě.
 */
@Singleton
class BubbleMaskSegmenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val session: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
        env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    /** Výsledek jedné inference nad celou stránkou - vstup pro (opakované) [matchShape]. */
    class PageSegmentation internal constructor(
        internal val detections: List<RawSegDetection>,
        internal val protoFlat: FloatArray,
        internal val protoH: Int,
        internal val protoW: Int,
        internal val params: LetterboxParams,
        internal val bitmapWidth: Int,
        internal val bitmapHeight: Int,
    )

    /**
     * Spustí model JEDNOU nad celou stránkou a vrátí všechny detekce bublin - viz [matchShape]
     * pro přiřazení ke konkrétnímu OCR boxu.
     *
     * Nikdy nevyhazuje - stejný důvod jako [BubbleBoxDetector.detect]: je to poslední záchrana,
     * ne kritická cesta, appka bez ní spadne zpátky na heuristický obdélník jako dřív.
     *
     * @return null, když model neběžel nebo nenašel na stránce žádnou bublinu.
     */
    suspend fun detectPage(
        bitmap: Bitmap,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
    ): PageSegmentation? = withContext(Dispatchers.Default) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val params = letterboxParams(bitmap.width, bitmap.height, INPUT_SIZE)
            val inputBuffer = YoloPreprocessing.letterboxToFloatBuffer(bitmap, params, INPUT_SIZE)
            OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())).use { inputTensor ->
                session.run(mapOf(session.inputNames.first() to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val detOutput = (result.get(DET_OUTPUT_NAME).orElse(null)?.value
                        ?: return@withContext null) as Array<Array<FloatArray>>
                    @Suppress("UNCHECKED_CAST")
                    val protoOutput = (result.get(PROTO_OUTPUT_NAME).orElse(null)?.value
                        ?: return@withContext null) as Array<Array<Array<FloatArray>>>

                    val detBatch = detOutput[0]
                    val numAnchors = detBatch[0].size
                    val flatDet = flattenChannelMajor(detBatch)
                    val raw = decodeYoloSegOutput(
                        flatDet,
                        numAnchors = numAnchors,
                        numClasses = NUM_CLASSES,
                        numMaskCoeffs = NUM_MASK_COEFFS,
                        confThreshold = confThreshold,
                    )
                    val kept = nonMaxSuppressionSeg(raw, iouThreshold)
                    if (kept.isEmpty()) return@withContext null

                    val protoBatch = protoOutput[0]
                    val protoH = protoBatch[0].size
                    val protoW = protoBatch[0][0].size
                    val flatProto = flattenChannelMajorHw(protoBatch)

                    PageSegmentation(
                        detections = kept,
                        protoFlat = flatProto,
                        protoH = protoH,
                        protoW = protoW,
                        params = params,
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                    )
                }
            }
        } catch (e: Exception) {
            e.report("translate:bubbleMaskSegmenter:detectPage")
            null
        }
    }

    /**
     * Najde v [page] tvar bubliny, která se nejvíc překrývá se zadaným OCR boxem textu
     * (normalizované 0..1 souřadnice stránky, stejný formát jako [TranslatedBlock]). Čistá
     * funkce bez ONNX volání - jde volat opakovaně pro víc bloků ze stejné [detectPage].
     *
     * @param minOverlapIou minimální překryv (IoU) mezi detekcí modelu a OCR boxem, aby se
     *   detekce vůbec považovala za TU SAMOU bublinu - bez týhle kontroly by appka mohla vzít
     *   tvar úplně jiné (jen nejbližší) bubliny na přeplněné stránce.
     * @return null, když žádná detekce dost nepřekrývala zadaný box.
     */
    fun matchShape(
        page: PageSegmentation,
        targetLeftF: Float,
        targetTopF: Float,
        targetRightF: Float,
        targetBottomF: Float,
        minOverlapIou: Float = 0.1f,
    ): List<BubbleShapePoint>? {
        // Cilovy box (OCR text, uz vime kde je) prevedeny do stejneho letterboxovaneho
        // prostoru jako detekce modelu, aby slo pocitat IoU proti nim.
        val targetInInputSpace = RawBoxDetection(
            leftPx = targetLeftF * page.bitmapWidth * page.params.scale + page.params.padX,
            topPx = targetTopF * page.bitmapHeight * page.params.scale + page.params.padY,
            rightPx = targetRightF * page.bitmapWidth * page.params.scale + page.params.padX,
            bottomPx = targetBottomF * page.bitmapHeight * page.params.scale + page.params.padY,
            classId = 0,
            score = 1f,
        )
        val best = page.detections.maxByOrNull { iou(it.box, targetInInputSpace) } ?: return null
        if (iou(best.box, targetInInputSpace) < minOverlapIou) return null

        val mask = reconstructMask(best.maskCoeffs, page.protoFlat, page.protoH, page.protoW)
        val protoScale = (INPUT_SIZE / page.protoW).coerceAtLeast(1)
        return maskToShapePoints(
            mask = mask,
            maskW = page.protoW,
            maskH = page.protoH,
            protoScale = protoScale,
            letterbox = page.params,
            srcWidth = page.bitmapWidth,
            srcHeight = page.bitmapHeight,
        )
    }

    /** Detekční tenzor `[channels][anchors]` -> plochý `FloatArray`, viz [decodeYoloSegOutput]. */
    private fun flattenChannelMajor(batch: Array<FloatArray>): FloatArray {
        val channels = batch.size
        val anchors = batch[0].size
        val flat = FloatArray(channels * anchors)
        for (c in 0 until channels) System.arraycopy(batch[c], 0, flat, c * anchors, anchors)
        return flat
    }

    /** Prototypový tenzor `[channels][h][w]` -> plochý `FloatArray`, viz [reconstructMask]. */
    private fun flattenChannelMajorHw(batch: Array<Array<FloatArray>>): FloatArray {
        val channels = batch.size
        val h = batch[0].size
        val w = batch[0][0].size
        val flat = FloatArray(channels * h * w)
        var offset = 0
        for (c in 0 until channels) {
            for (row in batch[c]) {
                System.arraycopy(row, 0, flat, offset, w)
                offset += w
            }
        }
        return flat
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/bubble_mask_segmenter.onnx"
        const val DET_OUTPUT_NAME = "output0"
        const val PROTO_OUTPUT_NAME = "output1"
        const val INPUT_SIZE = 640
        const val NUM_CLASSES = 1
        const val NUM_MASK_COEFFS = 32
    }
}

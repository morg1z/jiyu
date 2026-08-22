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

    /**
     * Najde tvar bubliny, která se v obraze nejvíc překrývá se zadaným OCR boxem textu
     * (normalizované 0..1 souřadnice stránky, stejný formát jako [TranslatedBlock]).
     *
     * Nikdy nevyhazuje - stejný důvod jako [BubbleBoxDetector.detect]: je to poslední záchrana,
     * ne kritická cesta, appka bez ní spadne zpátky na heuristický obdélník jako dřív.
     *
     * @param minOverlapIou minimální překryv (IoU) mezi detekcí modelu a OCR boxem, aby se
     *   detekce vůbec považovala za TU SAMOU bublinu - bez týhle kontroly by appka mohla vzít
     *   tvar úplně jiné (jen nejbližší) bubliny na přeplněné stránce.
     * @return null, když model neběžel, nenašel žádnou detekci, nebo žádná dost nepřekrývala
     *   zadaný box.
     */
    suspend fun segmentShape(
        bitmap: Bitmap,
        targetLeftF: Float,
        targetTopF: Float,
        targetRightF: Float,
        targetBottomF: Float,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
        minOverlapIou: Float = 0.1f,
    ): List<BubbleShapePoint>? = withContext(Dispatchers.Default) {
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

                    // Cilovy box (OCR text, uz vime kde je) prevedeny do stejneho letterboxovaneho
                    // prostoru jako detekce modelu, aby slo pocitat IoU proti nim.
                    val targetInInputSpace = RawBoxDetection(
                        leftPx = targetLeftF * bitmap.width * params.scale + params.padX,
                        topPx = targetTopF * bitmap.height * params.scale + params.padY,
                        rightPx = targetRightF * bitmap.width * params.scale + params.padX,
                        bottomPx = targetBottomF * bitmap.height * params.scale + params.padY,
                        classId = 0,
                        score = 1f,
                    )
                    val best = kept.maxByOrNull { iou(it.box, targetInInputSpace) } ?: return@withContext null
                    if (iou(best.box, targetInInputSpace) < minOverlapIou) return@withContext null

                    val protoBatch = protoOutput[0]
                    val protoH = protoBatch[0].size
                    val protoW = protoBatch[0][0].size
                    val flatProto = flattenChannelMajorHw(protoBatch)

                    val mask = reconstructMask(best.maskCoeffs, flatProto, protoH, protoW)
                    val protoScale = (INPUT_SIZE / protoW).coerceAtLeast(1)
                    maskToShapePoints(
                        mask = mask,
                        maskW = protoW,
                        maskH = protoH,
                        protoScale = protoScale,
                        letterbox = params,
                        srcWidth = bitmap.width,
                        srcHeight = bitmap.height,
                    )
                }
            }
        } catch (e: Exception) {
            e.report("translate:bubbleMaskSegmenter:segmentShape")
            null
        }
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

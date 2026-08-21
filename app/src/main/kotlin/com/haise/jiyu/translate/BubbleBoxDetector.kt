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
 * Nezávislá vizuální detekce "kde na stránce je bublina/text" přes natrénovaný YOLOv8 model
 * (viz assets/models/NOTICE.md) - běží čistě na zařízení, žádné API. Na rozdíl od zbytku
 * OCR pipeline nevychází z rozpoznaného textu vůbec, takže dává appce druhý, nezávislý zdroj
 * pravdy o poloze bubliny - viz [com.haise.jiyu.translate.OcrEngine], kde se výsledek používá
 * jako korekce pro [BubbleMerge]/[TranslationLayout] misto slepého spoléhání na OCR geometrii.
 *
 * Čistá matematika dekódování/NMS/letterboxu žije v [YoloDetectionDecode.kt] a je testovaná
 * JVM testy nezávisle na týhle třídě - tahle třída je jen tenký obal kolem ONNX Runtime a
 * Android Bitmap, který se JVM testem spustit nedá.
 */
@Singleton
class BubbleBoxDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val session: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
        env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    /**
     * Spustí detekci na celé stránce.
     *
     * Nikdy nevyhazuje - je to doplňkový signál, ne kritická cesta (appka bez něj fungovala
     * odjakživa), takže selhání modelu (chybějící/poškozený asset, OOM na slabém telefonu)
     * se jen zaloguje a appka pokračuje, jako by model nic nevrátil.
     */
    suspend fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
    ): List<DetectedBubbleBox> = withContext(Dispatchers.Default) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val params = letterboxParams(bitmap.width, bitmap.height, INPUT_SIZE)
            val inputBuffer = YoloPreprocessing.letterboxToFloatBuffer(bitmap, params, INPUT_SIZE)
            OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())).use { inputTensor ->
                session.run(mapOf(session.inputNames.first() to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<Array<FloatArray>>
                    val flat = flattenOutput(output)
                    val raw = decodeYoloOutput(flat, numAnchors = NUM_ANCHORS, numClasses = NUM_CLASSES, confThreshold = confThreshold)
                    nonMaxSuppression(raw, iouThreshold).map { it.toPageNormalized(params, bitmap.width, bitmap.height) }
                }
            }
        } catch (e: Exception) {
            e.report("translate:bubbleBoxDetector:detect")
            emptyList()
        }
    }

    /** Kanál-po-kanálu tenzor `[1][channels][anchors]` -> plochý `FloatArray` pro [decodeYoloOutput]. */
    private fun flattenOutput(output: Array<Array<FloatArray>>): FloatArray {
        val batch = output[0]
        val channels = batch.size
        val anchors = batch[0].size
        val flat = FloatArray(channels * anchors)
        for (c in 0 until channels) {
            System.arraycopy(batch[c], 0, flat, c * anchors, anchors)
        }
        return flat
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/comic_bubble_detector.onnx"
        const val INPUT_SIZE = 640
        const val NUM_CLASSES = 2
        const val NUM_ANCHORS = 8400
    }
}

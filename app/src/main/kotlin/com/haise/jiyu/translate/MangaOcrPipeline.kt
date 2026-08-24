package com.haise.jiyu.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.haise.jiyu.util.report
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ořízne bitmapu podle detekovaného boxu bubliny s malým okrajem (aby se do výřezu vešel
 * i tenký okraj bubliny kolem textu) - viz [MangaOcrPipeline.detectAndRecognize] a
 * [OcrEngine], které obě potřebují stejné oříznutí (jednou uvnitř téhle třídy pro
 * bez-fallbacku happy path, jednou v OcrEngine pro per-bublina timeout+ML Kit fallback).
 */
internal fun cropBubbleBoxWithMargin(bitmap: Bitmap, box: DetectedBubbleBox, marginFraction: Float = 0.08f): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val boxW = (box.rightF - box.leftF) * w
    val boxH = (box.bottomF - box.topF) * h
    val marginX = boxW * marginFraction
    val marginY = boxH * marginFraction
    val left = ((box.leftF * w) - marginX).toInt().coerceIn(0, w - 1)
    val top = ((box.topF * h) - marginY).toInt().coerceIn(0, h - 1)
    val right = ((box.rightF * w) + marginX).toInt().coerceIn(left + 1, w)
    val bottom = ((box.bottomF * h) + marginY).toInt().coerceIn(top + 1, h)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

/**
 * Tenký ONNX Runtime obal kolem `manga_ocr_encoder.onnx` + `manga_ocr_decoder.onnx` - viz
 * assets/models/NOTICE.md. Stejný vzor jako [BubbleBoxDetector]/[BubbleMaskSegmenter]:
 * nikdy nevyhazuje, selhání se loguje přes [report] a appka spadne na ML Kit fallback
 * (viz [OcrEngine], které tenhle fallback zajišťuje - tahle třída o ML Kitu vůbec neví).
 */
@Singleton
class MangaOcrPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bubbleBoxDetector: BubbleBoxDetector,
) {
    private val encoderSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelFile = ensureModelFileFromAsset(ENCODER_ASSET_PATH, "manga_ocr_encoder.onnx")
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }
    private val decoderSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelFile = ensureModelFileFromAsset(DECODER_ASSET_PATH, "manga_ocr_decoder.onnx")
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }
    private val tokenizer: MangaOcrTokenizer by lazy {
        val lines = context.assets.open(VOCAB_ASSET_PATH).bufferedReader().use { it.readLines() }
        MangaOcrTokenizer(lines)
    }

    /**
     * Zkopíruje `.onnx` model z assets do `filesDir/models/`, aby šel otevřít file-path
     * konstruktorem [OrtSession] (viz [encoderSession]/[decoderSession]) místo načítání
     * celého ~150-200MB souboru do Java-heap ByteArray (`createSession(bytes, ...)`), což
     * na reálném zařízení byl nejpravděpodobnější zdroj OOM (viz audit finding Critical #1).
     *
     * Kopíruje se malým pevným bufferem (8KB), ne `readBytes()` - to by problém jen přesunulo
     * z `createSession` do samotného kopírování. Kopie se přeskočí, pokud cílový soubor už
     * existuje (přežívá mezi běhy appky, nekopíruje se znovu při každém startu). Zapisuje se
     * nejdřív do `.tmp` a pak se přejmenuje - kdyby appka spadla/byla zabita uprostřed kopie,
     * příští spuštění neuvidí napůl zapsaný soubor jako "už existuje".
     */
    private fun ensureModelFileFromAsset(assetPath: String, fileName: String): File {
        val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
        val outFile = File(modelsDir, fileName)
        if (!outFile.exists()) {
            val tmpFile = File(modelsDir, "$fileName.tmp")
            context.assets.open(assetPath).use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            tmpFile.renameTo(outFile)
        }
        return outFile
    }

    /**
     * Najde bubliny na celé stránce ([BubbleBoxDetector], znovupoužitý Apache-2.0 model -
     * žádný nový box model netřeba) a pro každou přečte text přes manga-ocr. Bubliny, kde
     * [recognizeCrop] vrátí `null`/prázdný text, se v seznamu vůbec neobjeví - ML Kit
     * fallback pro ně zajišťuje [OcrEngine], ne tahle metoda (viz její vlastní smyčka).
     *
     * Pozn.: tahle metoda NENÍ na produkční cestě - [OcrEngine.recognizeJapaneseWithMangaOcr]
     * volá per-bublina [recognizeCrop] přímo (kvůli vlastnímu per-bublina timeoutu + ML Kit
     * fallbacku, který tahle metoda nemá). Jediný volající je `MangaOcrPipelineOnDeviceTest`
     * (androidTest sonda) - budoucí čtenář by ji neměl považovat za hot path.
     */
    suspend fun detectAndRecognize(bitmap: Bitmap): List<RawTextBlock> {
        val boxes = bubbleBoxDetector.detect(bitmap)
        return boxes.mapNotNull { box ->
            val crop = cropBubbleBoxWithMargin(bitmap, box)
            try {
                val text = recognizeCrop(crop)
                if (text.isNullOrBlank()) {
                    null
                } else {
                    RawTextBlock(text = text, leftF = box.leftF, topF = box.topF, rightF = box.rightF, bottomF = box.bottomF)
                }
            } finally {
                crop.recycle()
            }
        }
    }

    /** Přečte JEDNU už oříznutou bublinu - viz per-bublina timeout v [OcrEngine]. */
    suspend fun recognizeCrop(crop: Bitmap): String? = withContext(Dispatchers.Default) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val inputBuffer = MangaOcrPreprocessing.toEncoderInput(crop)
            // Per-bublina timeout (viz OcrEngine.MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS) zrusi
            // korutinu jen v suspend bodech - `session.run()` je blokujici nativni volani bez
            // vlastniho suspend bodu, takze bez tohohle by timeout na pomale/zaseknute bublině
            // reálně nic nepřerušil (viz audit finding Important #2).
            currentCoroutineContext().ensureActive()
            val encoderHiddenStates = OnnxTensor.createTensor(
                env, inputBuffer, longArrayOf(1, 3, MangaOcrPreprocessing.INPUT_SIZE.toLong(), MangaOcrPreprocessing.INPUT_SIZE.toLong()),
            ).use { pixelValues ->
                encoderSession.run(mapOf(ENCODER_INPUT_NAME to pixelValues)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result.get(ENCODER_OUTPUT_NAME).orElse(null)?.value
                        ?: return@withContext null) as Array<Array<FloatArray>>
                }
            }

            val encoderSeqLen = encoderHiddenStates[0].size
            val hiddenSize = encoderHiddenStates[0][0].size
            val encoderBuffer = FloatBuffer.allocate(encoderSeqLen * hiddenSize)
            for (token in encoderHiddenStates[0]) for (v in token) encoderBuffer.put(v)
            encoderBuffer.rewind()

            val decodedIds = OnnxTensor.createTensor(env, encoderBuffer, longArrayOf(1, encoderSeqLen.toLong(), hiddenSize.toLong())).use { hiddenTensor ->
                greedyDecode(bosId = tokenizer.bosId, eosId = tokenizer.eosId) { soFar ->
                    // Stejny duvod jako vys - kazdy krok dekodovani je dalsi blokujici nativni
                    // volani, takze tohle je jediny bod, kde se timeout muze mezi jednotlivymi
                    // tokeny skutecne projevit.
                    currentCoroutineContext().ensureActive()
                    val idsBuffer = LongBuffer.allocate(soFar.size)
                    soFar.forEach { idsBuffer.put(it.toLong()) }
                    idsBuffer.rewind()
                    OnnxTensor.createTensor(env, idsBuffer, longArrayOf(1, soFar.size.toLong())).use { idsTensor ->
                        decoderSession.run(mapOf(DECODER_INPUT_IDS_NAME to idsTensor, DECODER_ENCODER_STATES_NAME to hiddenTensor)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = result.get(DECODER_OUTPUT_NAME).orElse(null)?.value as? Array<Array<FloatArray>>
                            if (logits == null) {
                                Log.w("MangaOcrPipeline", "decoder logits null/shape mismatch, ukoncuji sekvenci eosId")
                                tokenizer.eosId
                            } else {
                                val lastStepLogits = logits[0].last()
                                var bestId = 0
                                var bestScore = Float.NEGATIVE_INFINITY
                                for (id in lastStepLogits.indices) {
                                    if (lastStepLogits[id] > bestScore) {
                                        bestScore = lastStepLogits[id]
                                        bestId = id
                                    }
                                }
                                bestId
                            }
                        }
                    }
                }
            }

            MangaOcrPostProcess.postProcess(tokenizer.decode(decodedIds))
        } catch (e: CancellationException) {
            // Zrušení uživatelem nebo systémem není chyba - hlásit by se nemělo.
            throw e
        } catch (e: Throwable) {
            // Throwable, ne Exception: KDoc téhle třídy slibuje "nikdy nevyhazuje, appka spadne
            // na ML Kit fallback" a tenhle slib musí platit i pro OutOfMemoryError (Error, ne
            // Exception - createSession/session.run na velkém modelu je nejpravděpodobnější
            // místo, kde OOM reálně hrozí, viz audit finding Critical #1). Bez tohohle by OOM
            // utekl přes recognizeCrop ven a spadla by celá stránka místo tichého fallbacku.
            e.report("translate:mangaOcrPipeline:recognizeCrop")
            null
        }
    }

    private companion object {
        const val ENCODER_ASSET_PATH = "models/manga_ocr_encoder.onnx"
        const val DECODER_ASSET_PATH = "models/manga_ocr_decoder.onnx"
        const val VOCAB_ASSET_PATH = "models/manga_ocr_vocab.txt"
        const val ENCODER_INPUT_NAME = "pixel_values"
        const val ENCODER_OUTPUT_NAME = "last_hidden_state"
        const val DECODER_INPUT_IDS_NAME = "input_ids"
        const val DECODER_ENCODER_STATES_NAME = "encoder_hidden_states"
        const val DECODER_OUTPUT_NAME = "logits"
    }
}

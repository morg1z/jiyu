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
 * Tenký ONNX Runtime obal kolem `manga_ocr_encoder.onnx` + `manga_ocr_decoder_init.onnx` +
 * `manga_ocr_decoder_step.onnx` (`ogkalu/manga-ocr-mobile`) - viz assets/models/NOTICE.md.
 * Stejný vzor jako [BubbleBoxDetector]/[BubbleMaskSegmenter]: nikdy nevyhazuje, selhání se
 * loguje přes [report] a appka spadne na ML Kit fallback (viz [OcrEngine], které tenhle
 * fallback zajišťuje - tahle třída o ML Kitu vůbec neví).
 */
@Singleton
class MangaOcrPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bubbleBoxDetector: BubbleBoxDetector,
) {
    private val encoderSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelFile = ensureModelFileFromAsset(context, ENCODER_ASSET_PATH, "manga_ocr_encoder.onnx")
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().withXnnpackIfAvailable())
    }
    private val decoderInitSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelFile = ensureModelFileFromAsset(context, DECODER_INIT_ASSET_PATH, "manga_ocr_decoder_init.onnx")
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().withXnnpackIfAvailable())
    }
    private val decoderStepSession: OrtSession by lazy {
        val env = OrtEnvironment.getEnvironment()
        val modelFile = ensureModelFileFromAsset(context, DECODER_STEP_ASSET_PATH, "manga_ocr_decoder_step.onnx")
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().withXnnpackIfAvailable())
    }
    private val tokenizer: MangaOcrTokenizer by lazy {
        val lines = context.assets.open(VOCAB_ASSET_PATH).bufferedReader().use { it.readLines() }
        MangaOcrTokenizer(lines)
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

    /**
     * Přečte JEDNU už oříznutou bublinu - viz per-bublina timeout v [OcrEngine].
     *
     * `manga-ocr-mobile` dekóduje s KV-cache přes dva samostatné ONNX grafy místo jednoho:
     * [decoderInitSession] se volá JEDNOU s BOS tokenem a vyprodukuje logity pro pozici 0 +
     * počáteční `self_k`/`self_v` (jednopoziční) + `cross_k`/`cross_v` (předpočítané
     * cross-attention klíče/hodnoty z `encoderHiddenStates`, stejné pro celou sekvenci -
     * proto se počítají jen jednou, ne na každém kroku). [decoderStepSession] se pak volá
     * pro každou další pozici s JEN posledním vygenerovaným tokenem (ne celou dosavadní
     * sekvencí jako starší model bez cache) + rostoucím `self_k_cache`/`self_v_cache` (pevná
     * vyalokovaná vyrovnávací paměť [MAX_CACHE_LEN] pozic, volající zapisuje vrácený
     * jednopoziční `self_k_slice`/`self_v_slice` na index `position_ids`) + stejnou
     * `cross_k_cache`/`cross_v_cache` z prvního kroku. Přesný tvar/název vstupů-výstupů
     * ověřen empiricky (`onnxruntime` v Pythonu) - viz assets/models/NOTICE.md.
     *
     * [greedyDecode] zůstává beze změny (viz [MangaOcrDecode]) - `soFar.size - 1` dává
     * přesně pozici dalšího tokenu a `soFar.last()` poslední vygenerovaný token, takže KV-
     * cache stav (cache pole, cross-cache, součet pravděpodobností) stačí držet v closure
     * kolem `nextToken` lambdy, aniž by se měnil obecný kontrakt dekódovací smyčky.
     */
    suspend fun recognizeCrop(crop: Bitmap): String? = withContext(Dispatchers.Default) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val inputBuffer = MangaOcrPreprocessing.toEncoderInput(crop)
            // Per-bublina timeout (viz OcrEngine.MANGA_OCR_PER_BUBBLE_TIMEOUT_MILLIS) zrusi
            // korutinu jen v suspend bodech - `session.run()` je blokujici nativni volani bez
            // vlastniho suspend bodu, takze bez tohohle by timeout na pomale/zaseknute bublině
            // reálně nic nepřerušil (viz audit finding Important #2).
            currentCoroutineContext().ensureActive()
            val hiddenBuffer = OnnxTensor.createTensor(
                env, inputBuffer, longArrayOf(1, 3, MangaOcrPreprocessing.INPUT_SIZE.toLong(), MangaOcrPreprocessing.INPUT_SIZE.toLong()),
            ).use { pixelValues ->
                encoderSession.run(mapOf(ENCODER_INPUT_NAME to pixelValues)).use { result ->
                    val tensor = result.get(ENCODER_OUTPUT_NAME).orElse(null) as? OnnxTensor
                        ?: return@withContext null
                    val flat = FloatArray(ENCODER_SEQ_LEN * HIDDEN_SIZE)
                    tensor.floatBuffer.run { rewind(); get(flat) }
                    flat
                }
            }

            // Cross K/V se po decoder_init už nemění - tenzory se vytvoří JEDNOU a použijí ve všech
            // krocích (dřív se v každém kroku znovu vytvářely a kopírovaly ~1,6 MB). Zavírají se ve
            // finally, aby při zrušení/výjimce neunikla nativní paměť.
            var crossKTensorShared: OnnxTensor? = null
            var crossVTensorShared: OnnxTensor? = null
            val (decodedIds, avgTokenConfidence) = try { OnnxTensor.createTensor(
                env, FloatBuffer.wrap(hiddenBuffer), longArrayOf(1, ENCODER_SEQ_LEN.toLong(), HIDDEN_SIZE.toLong()),
            ).use { hiddenTensor ->
                val selfKCache = FloatArray(SELF_CACHE_SIZE)
                val selfVCache = FloatArray(SELF_CACHE_SIZE)
                var probSum = 0.0
                var stepCount = 0

                val ids = greedyDecode(bosId = tokenizer.bosId, eosId = tokenizer.eosId) { soFar ->
                    // Stejny duvod jako vys - kazdy krok dekodovani je dalsi blokujici nativni
                    // volani, takze tohle je jediny bod, kde se timeout muze mezi jednotlivymi
                    // tokeny skutecne projevit.
                    currentCoroutineContext().ensureActive()
                    val pos = soFar.size - 1
                    if (pos == 0) {
                        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(soFar[0].toLong())), longArrayOf(1, 1))
                        try {
                            decoderInitSession.run(
                                mapOf(DECODER_ENCODER_STATES_NAME to hiddenTensor, DECODER_INPUT_IDS_NAME to idsTensor),
                            ).use { result ->
                                val logitsTensor = result.get(DECODER_LOGITS_NAME).orElse(null) as? OnnxTensor
                                val selfKTensor = result.get(DECODER_INIT_SELF_K_NAME).orElse(null) as? OnnxTensor
                                val selfVTensor = result.get(DECODER_INIT_SELF_V_NAME).orElse(null) as? OnnxTensor
                                val crossKTensor = result.get(DECODER_INIT_CROSS_K_NAME).orElse(null) as? OnnxTensor
                                val crossVTensor = result.get(DECODER_INIT_CROSS_V_NAME).orElse(null) as? OnnxTensor
                                if (logitsTensor == null || selfKTensor == null || selfVTensor == null || crossKTensor == null || crossVTensor == null) {
                                    Log.w("MangaOcrPipeline", "decoder_init vystup null/shape mismatch, ukoncuji sekvenci eosId")
                                    return@greedyDecode tokenizer.eosId
                                }
                                writeCacheSlice(selfKCache, selfKTensor.floatBuffer, 0)
                                writeCacheSlice(selfVCache, selfVTensor.floatBuffer, 0)
                                val ck = FloatArray(CROSS_CACHE_SIZE)
                                crossKTensor.floatBuffer.run { rewind(); get(ck) }
                                val cv = FloatArray(CROSS_CACHE_SIZE)
                                crossVTensor.floatBuffer.run { rewind(); get(cv) }
                                val crossCacheShape = longArrayOf(NUM_LAYERS.toLong(), 1, NUM_HEADS.toLong(), CROSS_SEQ_LEN.toLong(), HEAD_DIM.toLong())
                                crossKTensorShared?.close()
                                crossVTensorShared?.close()
                                crossKTensorShared = OnnxTensor.createTensor(env, FloatBuffer.wrap(ck), crossCacheShape)
                                crossVTensorShared = OnnxTensor.createTensor(env, FloatBuffer.wrap(cv), crossCacheShape)
                                logitsTensor.floatBuffer.rewind()
                                val (bestId, prob) = argmaxWithProb(logitsTensor.floatBuffer)
                                probSum += prob
                                stepCount++
                                bestId
                            }
                        } finally {
                            idsTensor.close()
                        }
                    } else {
                        val ckTensor = crossKTensorShared
                        val cvTensor = crossVTensorShared
                        if (ckTensor == null || cvTensor == null || pos >= MAX_CACHE_LEN) {
                            return@greedyDecode tokenizer.eosId
                        }
                        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(soFar.last().toLong())), longArrayOf(1, 1))
                        val posTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(pos.toLong())), longArrayOf(1, 1))
                        val selfCacheShape = longArrayOf(NUM_LAYERS.toLong(), 1, NUM_HEADS.toLong(), MAX_CACHE_LEN.toLong(), HEAD_DIM.toLong())
                        val skTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(selfKCache), selfCacheShape)
                        val svTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(selfVCache), selfCacheShape)
                        try {
                            decoderStepSession.run(
                                mapOf(
                                    DECODER_ENCODER_STATES_NAME to hiddenTensor,
                                    DECODER_INPUT_IDS_NAME to idsTensor,
                                    DECODER_POSITION_IDS_NAME to posTensor,
                                    DECODER_STEP_SELF_K_CACHE_NAME to skTensor,
                                    DECODER_STEP_SELF_V_CACHE_NAME to svTensor,
                                    DECODER_STEP_CROSS_K_CACHE_NAME to ckTensor,
                                    DECODER_STEP_CROSS_V_CACHE_NAME to cvTensor,
                                ),
                            ).use { result ->
                                val logitsTensor = result.get(DECODER_LOGITS_NAME).orElse(null) as? OnnxTensor
                                val kSliceTensor = result.get(DECODER_STEP_SELF_K_SLICE_NAME).orElse(null) as? OnnxTensor
                                val vSliceTensor = result.get(DECODER_STEP_SELF_V_SLICE_NAME).orElse(null) as? OnnxTensor
                                if (logitsTensor == null || kSliceTensor == null || vSliceTensor == null) {
                                    Log.w("MangaOcrPipeline", "decoder_step vystup null/shape mismatch, ukoncuji sekvenci eosId")
                                    return@greedyDecode tokenizer.eosId
                                }
                                writeCacheSlice(selfKCache, kSliceTensor.floatBuffer, pos)
                                writeCacheSlice(selfVCache, vSliceTensor.floatBuffer, pos)
                                logitsTensor.floatBuffer.rewind()
                                val (bestId, prob) = argmaxWithProb(logitsTensor.floatBuffer)
                                probSum += prob
                                stepCount++
                                bestId
                            }
                        } finally {
                            idsTensor.close()
                            posTensor.close()
                            skTensor.close()
                            svTensor.close()
                        }
                    }
                }
                ids to (if (stepCount > 0) (probSum / stepCount).toFloat() else 0f)
            } } finally {
                crossKTensorShared?.close()
                crossVTensorShared?.close()
            }

            // Nízká průměrná pravděpodobnost vybraných tokenů = model si nebyl jistý (typicky
            // prázdný/šumový výřez) - stejný fallback-na-ML-Kit důsledek jako u
            // MangaOcrGarbageFilter níže, jen jiný signál (pravděpodobnostní, ne vzor v textu).
            // MIN_AVG_TOKEN_CONFIDENCE je zatím konzervativní odhad bez reálného zařízení -
            // potřebuje doladit proti skutečnému manga textu (viz plán, položka 3).
            if (avgTokenConfidence < MIN_AVG_TOKEN_CONFIDENCE) return@withContext null

            val text = MangaOcrPostProcess.postProcess(tokenizer.decode(decodedIds))
            if (MangaOcrGarbageFilter.isPathologicalOutput(text)) null else text
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

    /**
     * Zapíše jednopoziční výstup dekodéru (`self_k`/`self_v`/`self_k_slice`/`self_v_slice`,
     * tvar `[NUM_LAYERS,1,NUM_HEADS,1,HEAD_DIM]`, tj. 16 bloků po 64 floatech - jeden blok na
     * kombinaci vrstva×hlava) do plochého cache pole tvaru `[NUM_LAYERS,1,NUM_HEADS,
     * MAX_CACHE_LEN,HEAD_DIM]` na pozici [pos]. Layout ověřen empiricky (korupce pozice 0
     * v cache změnila pozdější logity - viz research skript) - stride mezi bloky vrstva×hlava
     * v cache je `MAX_CACHE_LEN * HEAD_DIM`, ne `HEAD_DIM`.
     */
    private fun writeCacheSlice(cache: FloatArray, sliceBuffer: FloatBuffer, pos: Int) {
        sliceBuffer.rewind()
        for (lh in 0 until NUM_LAYERS * NUM_HEADS) {
            val srcBase = lh * HEAD_DIM
            val dstBase = (lh * MAX_CACHE_LEN + pos) * HEAD_DIM
            for (d in 0 until HEAD_DIM) {
                cache[dstBase + d] = sliceBuffer.get(srcBase + d)
            }
        }
    }

    /** Greedy argmax + softmax pravděpodobnost vybraného tokenu (pro [avgTokenConfidence]). */
    private fun argmaxWithProb(logits: FloatBuffer): Pair<Int, Float> {
        val n = logits.limit()
        var bestId = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val v = logits.get(i)
            if (v > bestScore) {
                bestScore = v
                bestId = i
            }
        }
        var sumExp = 0.0
        for (i in 0 until n) sumExp += kotlin.math.exp((logits.get(i) - bestScore).toDouble())
        return bestId to (1.0 / sumExp).toFloat()
    }

    private companion object {
        const val ENCODER_ASSET_PATH = "models/manga_ocr_encoder.onnx"
        const val DECODER_INIT_ASSET_PATH = "models/manga_ocr_decoder_init.onnx"
        const val DECODER_STEP_ASSET_PATH = "models/manga_ocr_decoder_step.onnx"
        const val VOCAB_ASSET_PATH = "models/manga_ocr_vocab.txt"

        const val ENCODER_INPUT_NAME = "serving_default_args_0:0"
        const val ENCODER_OUTPUT_NAME = "StatefulPartitionedCall:0"
        const val DECODER_INPUT_IDS_NAME = "input_ids"
        const val DECODER_ENCODER_STATES_NAME = "encoder_hidden_states"
        const val DECODER_POSITION_IDS_NAME = "position_ids"
        const val DECODER_LOGITS_NAME = "logits"
        const val DECODER_INIT_SELF_K_NAME = "self_k"
        const val DECODER_INIT_SELF_V_NAME = "self_v"
        const val DECODER_INIT_CROSS_K_NAME = "cross_k"
        const val DECODER_INIT_CROSS_V_NAME = "cross_v"
        const val DECODER_STEP_SELF_K_CACHE_NAME = "self_k_cache"
        const val DECODER_STEP_SELF_V_CACHE_NAME = "self_v_cache"
        const val DECODER_STEP_CROSS_K_CACHE_NAME = "cross_k_cache"
        const val DECODER_STEP_CROSS_V_CACHE_NAME = "cross_v_cache"
        const val DECODER_STEP_SELF_K_SLICE_NAME = "self_k_slice"
        const val DECODER_STEP_SELF_V_SLICE_NAME = "self_v_slice"

        // Rozměry modelu (ověřeno empiricky přes onnxruntime v Pythonu, viz
        // assets/models/NOTICE.md) - NE převzato z README, ověřeno na skutečných ONNX grafech.
        const val ENCODER_SEQ_LEN = 196 // 14x14 patchů (224/16)
        const val HIDDEN_SIZE = 256
        const val NUM_LAYERS = 4
        const val NUM_HEADS = 4
        const val HEAD_DIM = 64
        const val MAX_CACHE_LEN = 256 // musí být >= MANGA_OCR_MAX_DECODE_TOKENS (viz MangaOcrDecode.kt)
        const val CROSS_SEQ_LEN = ENCODER_SEQ_LEN
        const val SELF_CACHE_SIZE = NUM_LAYERS * NUM_HEADS * MAX_CACHE_LEN * HEAD_DIM
        const val CROSS_CACHE_SIZE = NUM_LAYERS * NUM_HEADS * CROSS_SEQ_LEN * HEAD_DIM

        const val MIN_AVG_TOKEN_CONFIDENCE = 0.1f
    }
}

package com.haise.jiyu.translate

import ai.onnxruntime.OrtSession
import android.content.Context
import com.haise.jiyu.util.report
import java.io.File

/**
 * XNNPACK je CPU inference akcelerator dostupny v ONNX Runtime Mobile (SIMD/kvantizovane
 * kernely) - typicky 1.3-2x zrychleni bez ztraty presnosti, zdarma (zadna zmena modelu,
 * jen konfigurace session). Nektere ORT Android build varianty XNNPACK EP nemusi obsahovat,
 * proto se pripojeni obali do runCatching - selhani se tise zaloguje a session pouzije
 * vychozi CPU EP misto XNNPACK, nikdy nesmi shodit vytvoreni session.
 */
internal fun OrtSession.SessionOptions.withXnnpackIfAvailable(): OrtSession.SessionOptions = apply {
    runCatching { addXnnpack(emptyMap()) }
        .onFailure { it.report("translate:onnxSessionOptions:xnnpackUnavailable") }
}

/**
 * Zkopiruje `.onnx` model z assets do `filesDir/models/`, aby sel otevrit file-path
 * konstruktorem [OrtSession] misto nacitani celeho souboru do Java-heap ByteArray
 * (`createSession(bytes, ...)`), coz byl na realnem zarizeni nejpravdepodobnejsi zdroj
 * OOM u vetsich modelu (viz audit finding Critical #1) - sdileno mezi [MangaOcrPipeline],
 * [BubbleBoxDetector] a [BubbleMaskSegmenter], ktere vsechny natvrdo nacitaly cely model
 * do pameti pred timhle sjednocenim.
 *
 * Kopiruje se malym pevnym bufferem (8KB), ne `readBytes()` - to by problem jen presunulo
 * z `createSession` do samotneho kopirovani. Kopie se preskoci, pokud cilovy soubor uz
 * existuje (prezije mezi behy appky). Zapisuje se nejdriv do `.tmp` a pak se prejmenuje -
 * kdyby appka spadla/byla zabita uprostred kopie, pristi spusteni neuvidi napul zapsany
 * soubor jako "uz existuje".
 */
internal fun ensureModelFileFromAsset(context: Context, assetPath: String, fileName: String): File {
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

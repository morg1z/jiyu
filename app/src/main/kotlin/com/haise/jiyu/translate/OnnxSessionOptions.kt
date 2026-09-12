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
 * existuje SE SPRAVNOU VELIKOSTI (viz nize) - jinak by budouci APK update s novym modelem
 * stejneho jmena uzivatele se starym zkopirovanym souborem uz nikdy nedohnal. Zapisuje se
 * nejdriv do `.tmp` a pak se prejmenuje - kdyby appka spadla/byla zabita uprostred kopie,
 * pristi spusteni neuvidi napul zapsany soubor jako "uz existuje".
 *
 * Assety `.onnx` jsou natvrdo nekomprimovane (`androidResources.noCompress += "onnx"` v
 * app/build.gradle.kts), takze `AssetFileDescriptor.length` jde precist bez rozbaleni celeho
 * souboru - staci na jednoduchou (ne kryptograficky silnou, ale k detekci "jiny/novejsi model"
 * dostacujici) kontrolu shody velikosti.
 */
internal fun ensureModelFileFromAsset(context: Context, assetPath: String, fileName: String): File {
    val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
    val outFile = File(modelsDir, fileName)
    val assetSize = context.assets.openFd(assetPath).use { it.length }
    if (!outFile.exists() || outFile.length() != assetSize) {
        val tmpFile = File(modelsDir, "$fileName.tmp")
        try {
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
            if (!tmpFile.renameTo(outFile)) {
                // renameTo() muze selhat na nekterych souborovych systemech/zarizenich i pri
                // uspesnem zapisu - zkopirovat rovnou pres cil misto tise pokracovat, jako by
                // outFile uz byl hotovy (createSession na chybejicim/starem souboru by pak
                // spadl mnohem hure citelnou chybou az v ONNX Runtime).
                tmpFile.copyTo(outFile, overwrite = true)
            }
        } finally {
            tmpFile.delete()
        }
    }
    return outFile
}

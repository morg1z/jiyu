package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end sonda [OcrEngine.recognize] pro japonštinu - viz spec sekce 4/5. Ověřuje dvě
 * věci na reálném zařízení (žádný unit test tohle nepokryje - potřebuje ML Kit i ONNX):
 *
 *  1. Šťastná cesta: manga-ocr běží normálně, vrátí nějaký text.
 *  2. Záložní cesta: [MangaOcrPipeline] uměle donucený selhat (zkonstruovaný s
 *     INSTRUMENTATION kontextem misto cilove appky - ten nema assets/models/ * .onnx
 *     zabundlovane, takze kazde recognizeCrop uvnitr selze na chybejicim assetu a vrati
 *     null) - appka i tak musí vrátit text, protože OcrEngine pro tenhle pripad spadne
 *     na ML Kit.
 *
 * Výsledky jdou do logcatu pod značkou "MangaOcrFallbackProbe".
 */
@RunWith(AndroidJUnit4::class)
class MangaOcrFallbackOnDeviceTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentationContext get() = InstrumentationRegistry.getInstrumentation().context

    /** Bublina (bílá elipsa s černým obrysem) s japonským textem uvnitř - vstup pro detectAndRecognize. */
    private fun pageWithJapaneseBubble(): Bitmap {
        val w = 900
        val h = 1200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(18, 18, 24))
        val fill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val stroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        canvas.drawOval(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.40f, fill)
        canvas.drawOval(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.40f, stroke)
        val text = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText("こんにちは", w * 0.30f, h * 0.30f, text)
        return bmp
    }

    @Test
    fun recognize_happyPath_returnsText() = runBlocking {
        val bubbleBoxDetector = BubbleBoxDetector(targetContext)
        val engine = OcrEngine(
            BubbleMaskSegmenter(targetContext),
            bubbleBoxDetector,
            MangaOcrPipeline(targetContext, bubbleBoxDetector),
        )
        val blocks = engine.recognize(pageWithJapaneseBubble(), "Japanese")
        Log.i("MangaOcrFallbackProbe", "happy path nalezl bloku: ${blocks.size}")
        blocks.forEach { Log.i("MangaOcrFallbackProbe", "  text=\"${it.text}\"") }
        assertTrue("OcrEngine musi na japonske bublině neco najit", blocks.isNotEmpty())
    }

    @Test
    fun recognize_fallsBackToMlKit_whenMangaOcrPipelineCannotLoadModel() = runBlocking {
        val bubbleBoxDetector = BubbleBoxDetector(targetContext)
        // MangaOcrPipeline zkonstruovany s kontextem TEST APK (ne cilove appky) - ten nema
        // assets/models/ * .onnx zabundlovane, takze kazde recognizeCrop uvnitr selze na
        // chybejicim souboru a vrati null (viz MangaOcrPipeline - nikdy nevyhazuje).
        val brokenPipeline = MangaOcrPipeline(instrumentationContext, bubbleBoxDetector)
        val engine = OcrEngine(BubbleMaskSegmenter(targetContext), bubbleBoxDetector, brokenPipeline)

        val blocks = engine.recognize(pageWithJapaneseBubble(), "Japanese")
        Log.i("MangaOcrFallbackProbe", "fallback path nalezl bloku: ${blocks.size}")
        blocks.forEach { Log.i("MangaOcrFallbackProbe", "  text=\"${it.text}\"") }

        assertTrue(
            "kdyz manga-ocr nejde nacist, OcrEngine musi spadnout na ML Kit a pořád neco vratit",
            blocks.isNotEmpty(),
        )
    }
}

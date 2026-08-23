package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SONDA k [MangaOcrPipeline] na reálném ONNX modelu (žádný unit test ho nemá - potřebuje
 * Android Bitmap/OrtSession, viz spec sekce "Testování"). Kontroluje, že celá pipeline
 * (preprocessing -> encoder -> greedy decode -> tokenizer -> post_process) doběhne na
 * reálném zařízení a vrátí neprázdný text - přesnost na synteticky vykresleném textu (ne
 * reálném manga fontu, na kterém je model trénovaný) se schválně nevynucuje přesnou shodou,
 * jen se loguje pro ruční kontrolu.
 *
 * Výsledky jdou do logcatu pod značkou "MangaOcrProbe".
 */
@RunWith(AndroidJUnit4::class)
class MangaOcrPipelineOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bubbleBoxDetector by lazy { BubbleBoxDetector(context) }
    private val pipeline by lazy { MangaOcrPipeline(context, bubbleBoxDetector) }

    /** Prostý bílý výřez s vodorovně vysázeným japonským textem - vstup pro recognizeCrop. */
    private fun japaneseCrop(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText(text, 20f, 70f, paint)
        return bmp
    }

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
    fun recognizeCrop_returnsNonNullTextForSyntheticJapanese() = runBlocking {
        val crop = japaneseCrop("こんにちは")
        val text = pipeline.recognizeCrop(crop)
        Log.i("MangaOcrProbe", "recognizeCrop vratil: \"$text\"")
        assertNotNull("manga-ocr pipeline se nesmi na zarizeni zhroutit / vratit null (asset/tenzor chyba)", text)
    }

    @Test
    fun detectAndRecognize_findsBubbleAndReturnsText() = runBlocking {
        val bitmap = pageWithJapaneseBubble()
        val blocks = pipeline.detectAndRecognize(bitmap)
        Log.i("MangaOcrProbe", "detectAndRecognize nalezl bloku: ${blocks.size}")
        blocks.forEach { b -> Log.i("MangaOcrProbe", "  box=%.2f,%.2f..%.2f,%.2f text=\"%s\"".format(b.leftF, b.topF, b.rightF, b.bottomF, b.text)) }
        // Pozn.: pokud bublinovy YOLO detektor na téhle synteticke elipse nic nenajde
        // (trenovany na realnych manga bublinach, ne programove kreslenych ovalech), je
        // potreba tenhle assert po prvnim behu na zarizeni prehodnotit - viz Task 10.
        assertTrue("bublinovy detektor musi na syntetické bublině najit aspon jeden box", blocks.isNotEmpty())
    }
}

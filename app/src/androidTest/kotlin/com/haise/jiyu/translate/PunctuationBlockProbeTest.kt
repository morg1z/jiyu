package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SONDA k nahlášeným snímkům z Vagabonda, na kterých pod přeloženou bublinou zbyla osamocená
 * TEČKA - u "MATAHACHI", u "ODLÉTÁME" a jako drobné artefakty pod "PŘEŽIVŠÍ".
 *
 * Ze snímku se nedá poznat, jestli jde o
 *   (a) zbytek originálu, který nedočistila záplata pozadí, nebo
 *   (b) samostatný OCR blok obsahující jen interpunkci, který appka poslala na překlad a
 *       vykreslila jako vlastní mrňavou bublinu.
 *
 * Obojí vypadá stejně a chce jinou opravu, takže se to musí změřit. Sonda pustí REÁLNÝ ML Kit
 * na stránku nakreslenou podle nahlášených panelů a vypíše, co z něj vypadne - kolik bloků,
 * jaké mají texty a jak je klasifikuje [BubbleClassifier].
 *
 * Nic netvrdí (žádné assert), jen měří. Výsledky jdou do logcatu pod značkou "PunctProbe".
 */
@RunWith(AndroidJUnit4::class)
class PunctuationBlockProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bubblePage(w: Int = 900, h: Int = 1400): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 20, 20))

        val fill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val text = Paint().apply {
            color = Color.BLACK
            textSize = 34f
            isAntiAlias = true
        }

        // Panel 1: replika na DVA řádky, druhý končí výpustkou - podle bubliny "A / SURVIVOR..."
        canvas.drawOval(w * 0.10f, h * 0.06f, w * 0.62f, h * 0.24f, fill)
        canvas.drawText("A", w * 0.33f, h * 0.13f, text)
        canvas.drawText("SURVIVOR...", w * 0.20f, h * 0.19f, text)

        // Panel 2: jednořádková replika končící TEČKOU - podle bubliny "MATAHACHI."
        canvas.drawOval(w * 0.32f, h * 0.34f, w * 0.92f, h * 0.48f, fill)
        canvas.drawText("MATAHACHI.", w * 0.40f, h * 0.42f, text)

        // Panel 3: krátká replika s tečkou, aby bylo vidět, jestli na délce záleží.
        canvas.drawOval(w * 0.10f, h * 0.56f, w * 0.60f, h * 0.68f, fill)
        canvas.drawText("URR...", w * 0.22f, h * 0.63f, text)

        return bmp
    }

    /**
     * Text nakreslený PŘÍMO NA PESTROU KRESBU, bez bubliny - podle titulní stránky, kde
     * "...HAS ENDED." zůstalo v překladu čitelné vedle drobného pokusu o překlad.
     */
    private fun artworkCaptionPage(w: Int = 900, h: Int = 700): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Pestrý podklad - modré nebe, zelená pláň, tmavé skvrny, žádná jednolitá plocha.
        val sky = Paint().apply { color = Color.rgb(90, 120, 190) }
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.55f, sky)
        val ground = Paint().apply { color = Color.rgb(70, 110, 80) }
        canvas.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), ground)
        val blob = Paint().apply { color = Color.rgb(40, 60, 50); isAntiAlias = true }
        for (i in 0 until 40) {
            canvas.drawCircle((i * 53 % w).toFloat(), h * 0.6f + (i * 31 % 200), 14f, blob)
        }

        val caption = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            isAntiAlias = true
        }
        canvas.drawText("THE BATTLE OF SEKIGAHARA", w * 0.45f, h * 0.30f, caption)
        canvas.drawText("...HAS ENDED.", w * 0.10f, h * 0.62f, caption)
        return bmp
    }

    private fun dump(label: String, bitmap: Bitmap) = runBlocking {
        val blocks = OcrEngine(BubbleMaskSegmenter(context)).recognize(bitmap, "English")
        val classified = BubbleClassifier.classifyPage(blocks)
        Log.i(TAG, "=== $label: ${blocks.size} bloku ===")
        blocks.forEachIndexed { i, b ->
            val c = classified[i]
            val letters = b.text.count { it.isLetter() }
            Log.i(
                TAG,
                "[$i] text=\"${b.text}\" pismen=$letters radku=${b.lineCount} " +
                    "bgUniform=${b.bgUniform} tvar=${b.shape != null} " +
                    "isSfx=${c.isSfx} typ=${c.bubbleType} " +
                    "box=(${"%.3f".format(b.leftF)},${"%.3f".format(b.topF)})-" +
                    "(${"%.3f".format(b.rightF)},${"%.3f".format(b.bottomF)})",
            )
        }
        val punctuationOnly = blocks.filter { it.text.none { ch -> ch.isLetterOrDigit() } }
        Log.i(TAG, ">>> $label: bloku BEZ jedineho pismene/cislice = ${punctuationOnly.size} ${punctuationOnly.map { it.text }}")
    }

    @Test
    fun probe_whatDoesOcrReturnForBubblesEndingInPunctuation() {
        dump("bubliny", bubblePage())
    }

    @Test
    fun probe_isCaptionOnArtworkSeenAsNonUniformBackground() {
        dump("kresba", artworkCaptionPage())
    }

    /**
     * Text na SKUTEČNĚ pestré kresbě (hustá textura, ne velká jednolitá plocha) - kontrola,
     * že tolerance k vzorkům spadlým na písmeno neoslabila rozpoznání kresby. Předchozí
     * varianta má pod textem velkou jednolitou zelenou plochu, což je pro tenhle účel příliš
     * hodný případ: takový text by plnou výplň klidně snesl.
     */
    private fun busyArtworkPage(w: Int = 900, h: Int = 700): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { isAntiAlias = true }
        // Hustá nepravidelná textura přes celou plochu - žádná souvislá barva.
        for (y in 0 until h step 7) {
            for (x in 0 until w step 7) {
                paint.color = Color.rgb((x * 7 + y * 3) % 256, (x * 3 + y * 11) % 256, (x * 13 + y * 5) % 256)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 7).toFloat(), (y + 7).toFloat(), paint)
            }
        }
        val caption = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            isAntiAlias = true
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2f
        }
        canvas.drawText("...HAS ENDED.", w * 0.12f, h * 0.55f, caption)
        return bmp
    }

    @Test
    fun probe_busyArtworkMustStillCountAsNonUniform() {
        dump("pestra kresba", busyArtworkPage())
    }

    private companion object {
        const val TAG = "PunctProbe"
    }
}

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
 * Měří, jaký je vztah mezi VÝŠKOU OCR BOXU a skutečnou velikostí písma.
 *
 * Proč: [RawTextBlock.nativeLineHeightF] se plní výškou OCR boxu řádku (viz [mergeNearbyLines]),
 * ale TranslationLayer s ní zachází, jako by to byla řádková ROZTEČ, a dělí ji 1.25, aby z ní
 * dostal velikost písma. OCR box ale obepíná jen samotná písmena - u verzálek zhruba výšku
 * velkého písmene, což je výrazně méně než rozteč řádků. Odvozená "nativní" velikost proto
 * vyjde menší než skutečné písmo originálu, a protože slouží jako STROP pro sazbu překladu,
 * text nikdy nemůže být větší (viz uživatelská zpětná vazba - text malý v obří bublině).
 *
 * Poměr se tu nehádá z typografie, ale změří: vykreslí se text o ZNÁMÉ velikosti a porovná se
 * s tím, co vrátí ML Kit. Výsledek jde do logcatu pod značkou "FontRatioProbe".
 */
@RunWith(AndroidJUnit4::class)
class NativeFontSizeOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun page(text: String, fontPx: Float, w: Int = 1000, h: Int = 400): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = fontPx
            isAntiAlias = true
        }
        canvas.drawText(text, 60f, h * 0.6f, paint)
        return bmp
    }

    @Test
    fun measureOcrBoxHeightAgainstKnownFontSize() = runBlocking {
        val engine = OcrEngine(BubbleMaskSegmenter(context))
        val ratios = mutableListOf<Float>()

        // Verzalky jsou to, co manga lettering pouziva nejcasteji.
        for (fontPx in listOf(40f, 60f, 80f)) {
            val bitmap = page("PROBOHA ZTRATIT SE", fontPx)
            val blocks = engine.recognize(bitmap, "English")
            if (blocks.isEmpty()) {
                Log.i("FontRatioProbe", "font=$fontPx -> OCR nenaslo nic")
                continue
            }
            val boxHeightPx = blocks.first().let { (it.bottomF - it.topF) * bitmap.height }
            val ratio = boxHeightPx / fontPx
            ratios += ratio
            Log.i(
                "FontRatioProbe",
                "VERZALKY font=%.0fpx -> box=%.1fpx, pomer box/font=%.3f".format(fontPx, boxHeightPx, ratio),
            )
        }

        // Pro srovnani jeste text s dolnimi dotaznicemi (p, y) - ten box je vyssi.
        val mixed = page("Ztratit se py", 60f)
        engine.recognize(mixed, "English").firstOrNull()?.let {
            val boxHeightPx = (it.bottomF - it.topF) * mixed.height
            Log.i("FontRatioProbe", "SMISENE font=60px -> box=%.1fpx, pomer=%.3f".format(boxHeightPx, boxHeightPx / 60f))
        }

        assertTrue("aspon jedno mereni musi projit", ratios.isNotEmpty())
        val avg = ratios.average()
        Log.i("FontRatioProbe", "PRUMERNY POMER box/font = %.3f".format(avg))
        Log.i(
            "FontRatioProbe",
            "soucasny prepocet deli 1.25, tedy font = box/1.25 = %.3f x skutecne velikosti".format(avg / 1.25),
        )

        assertTrue(
            "pomer box/font ma byt pod 1 - OCR box obepina pismena, ne rozteč radku (bylo $avg)",
            avg < 1.0,
        )
    }
}

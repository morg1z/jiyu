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
 * Reprodukuje na skutečném zařízení nahlášenou chybu s kaskádovou ("dvoulaločnou") bublinou.
 *
 * Nahlášeno: replika rozdělená do dvou překrývajících se bublinek - po překladu byl horní lalok
 * úplně prázdný a jeho text zmizel. Flip na originál ukázal, že uložený originál spodní bubliny
 * obsahuje jen spodní větu, takže horní text do toho bloku nepatří.
 *
 * Podezření: obě bublinky tvoří JEDNU spojitou bílou plochu, takže flood-fill hledající obrys
 * spodní bubliny se přes ten pas přelije nahoru a vrátí tvar pokrývající oba laloky. Výplň pak
 * přemaluje text horní bubliny.
 *
 * Tenhle test tu domněnku ověřuje proti REÁLNÉMU ML Kitu i reálné detekci obrysu - unit test
 * ani jedno nemá. Zároveň odpovídá na otázku, na kterou se z obrázku dívat nešlo: najde OCR
 * horní bublinu vůbec?
 *
 * Naměřené hodnoty jdou do logcatu pod značkou "CascadeProbe".
 */
@RunWith(AndroidJUnit4::class)
class CascadingBubbleOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Nakreslí kaskádovou bublinu: dvě PŘEKRÝVAJÍCÍ SE bílé elipsy s černým obrysem na tmavém
     * pozadí, v každé kus repliky. Přesně ta situace ze screenshotu.
     */
    private fun cascadingBalloon(w: Int = 900, h: Int = 1200): Bitmap {
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
        // Horni, mensi lalok
        canvas.drawOval(w * 0.30f, h * 0.14f, w * 0.72f, h * 0.32f, fill)
        canvas.drawOval(w * 0.30f, h * 0.14f, w * 0.72f, h * 0.32f, stroke)
        // Spodni, vetsi lalok - PREKRYVA se s hornim (spolecna bila plocha)
        canvas.drawOval(w * 0.10f, h * 0.28f, w * 0.66f, h * 0.52f, fill)
        canvas.drawOval(w * 0.10f, h * 0.28f, w * 0.66f, h * 0.52f, stroke)
        // Prekryv prekreslime jeste jednou vyplni, aby mezi laloky nezustal obrys - presne
        // jak to vypada v originale.
        canvas.drawOval(w * 0.32f, h * 0.26f, w * 0.64f, h * 0.34f, fill)

        val text = Paint().apply {
            color = Color.BLACK
            textSize = 38f
            isAntiAlias = true
        }
        canvas.drawText("GOOD HEAVENS,", w * 0.34f, h * 0.24f, text)
        canvas.drawText("TO GET LOST AFTER", w * 0.14f, h * 0.36f, text)
        canvas.drawText("COMING ALL THIS WAY.", w * 0.14f, h * 0.41f, text)
        return bmp
    }

    @Test
    fun cascadingBubbleShapesDoNotSwallowTheNeighbour() = runBlocking {
        val bitmap = cascadingBalloon()
        val blocks = OcrEngine(BubbleMaskSegmenter(context)).recognize(bitmap, "English")

        Log.i("CascadeProbe", "nalezeno bloku: ${blocks.size}")
        blocks.forEachIndexed { i, b ->
            val shapeSpan = b.shape?.let { s -> "%.3f..%.3f".format(s.minOf { it.yF }, s.maxOf { it.yF }) } ?: "bez tvaru"
            Log.i(
                "CascadeProbe",
                "[$i] box y=%.3f..%.3f x=%.3f..%.3f | tvar y=%s | text=\"%s\"".format(
                    b.topF, b.bottomF, b.leftF, b.rightF, shapeSpan, b.text.replace("\n", " "),
                ),
            )
        }

        assertTrue("OCR musi na strance neco najit", blocks.isNotEmpty())

        // JADRO NAHLASENE CHYBY: tvar zadneho bloku nesmi sahat pres OCR box jineho bloku -
        // presne to zpusobovalo premalovani cizi bubliny.
        blocks.forEachIndexed { i, block ->
            val shape = block.shape ?: return@forEachIndexed
            val top = shape.minOf { it.yF }
            val bottom = shape.maxOf { it.yF }
            blocks.forEachIndexed { j, other ->
                if (i == j) return@forEachIndexed
                val horizontallyOverlaps =
                    minOf(block.rightF, other.rightF) - maxOf(block.leftF, other.leftF) > 0f
                if (!horizontallyOverlaps) return@forEachIndexed
                val swallowsOther = top <= other.topF && bottom >= other.bottomF
                assertTrue(
                    "tvar bloku $i (y=$top..$bottom) polkl cely box bloku $j " +
                        "(y=${other.topF}..${other.bottomF}) - premaluje mu text",
                    !swallowsOther,
                )
            }
        }
    }
}

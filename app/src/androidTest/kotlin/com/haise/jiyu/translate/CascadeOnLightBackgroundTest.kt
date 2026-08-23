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
import kotlin.random.Random

/**
 * Reprodukuje kaskádovou bublinu na SVĚTLÉM ŠRAFOVANÉM pozadí.
 *
 * Nahlášeno: horní lalok zůstal bílý a prázdný i po opravě ořezu (v0.8.5). Klepnutí na horní
 * lalok přepnulo SPODNÍ bublinu - její box tedy sahá i přes horní lalok.
 *
 * Podezření: na světlém pozadí (šrafura blízká bílé výplni bubliny) detekce obrysu selže a
 * vrátí null. Ořez [clampShapeToOwnLobe] pak nemá co ořezávat - pracuje jen s nalezeným tvarem -
 * a použije se náhradní obdélník z heuristiky.
 *
 * Předchozí pokus (tmavé pozadí) tuhle chybu nereprodukoval, protože tam se obrys najde.
 *
 * Loguje se pod značkou "LightBgProbe": pro každý blok text, box, tvar i to, jak ho vyhodnotil
 * klasifikátor - tedy všechno, co rozhoduje o tom, jestli se vůbec vykreslí.
 */
@RunWith(AndroidJUnit4::class)
class CascadeOnLightBackgroundTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Světlé šrafované pozadí - drobné tmavé tahy na skoro bílé ploše, jako na nahlášené stránce. */
    private fun hatchedBackground(canvas: Canvas, w: Int, h: Int) {
        canvas.drawColor(Color.rgb(245, 245, 245))
        val hatch = Paint().apply {
            color = Color.rgb(110, 110, 110)
            strokeWidth = 2f
            isAntiAlias = true
        }
        val rnd = Random(7)
        repeat(1400) {
            val x = rnd.nextInt(w).toFloat()
            val y = rnd.nextInt(h).toFloat()
            canvas.drawLine(x, y, x + 14f, y + 9f, hatch)
        }
    }

    private fun cascadingOnLightBg(w: Int = 900, h: Int = 1300): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        hatchedBackground(canvas, w, h)

        val fill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val stroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        canvas.drawOval(w * 0.30f, h * 0.10f, w * 0.82f, h * 0.40f, fill)
        canvas.drawOval(w * 0.30f, h * 0.10f, w * 0.82f, h * 0.40f, stroke)
        canvas.drawOval(w * 0.08f, h * 0.34f, w * 0.64f, h * 0.64f, fill)
        canvas.drawOval(w * 0.08f, h * 0.34f, w * 0.64f, h * 0.64f, stroke)
        canvas.drawOval(w * 0.33f, h * 0.32f, w * 0.61f, h * 0.42f, fill)

        val text = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            isAntiAlias = true
        }
        // Radky tesne u sebe, aby se v ramci laloku slouceji do jednoho bloku.
        listOf("IF I HAD KNOWN", "THE ROAD AHEAD", "WOULD BE LIKE THIS,").forEachIndexed { i, line ->
            canvas.drawText(line, w * 0.36f, h * (0.17f + i * 0.032f), text)
        }
        listOf("I WOULD NEVER", "HAVE SIGNED THAT", "CONTRACT AT ALL.").forEachIndexed { i, line ->
            canvas.drawText(line, w * 0.12f, h * (0.42f + i * 0.032f), text)
        }
        return bmp
    }

    @Test
    fun probeShapesAndClassificationOnLightBackground() = runBlocking<Unit> {
        val bitmap = cascadingOnLightBg()
        val raw = OcrEngine(BubbleMaskSegmenter(context), BubbleBoxDetector(context), MangaOcrPipeline(context, BubbleBoxDetector(context))).recognize(bitmap, "English")
        val classified = BubbleClassifier.classifyPage(raw)

        Log.i("LightBgProbe", "bloku = ${raw.size}")
        classified.forEachIndexed { i, c ->
            val b = c.raw
            val shapeSpan = b.shape?.let { s -> "%.3f..%.3f".format(s.minOf { it.yF }, s.maxOf { it.yF }) }
                ?: "BEZ TVARU"
            Log.i(
                "LightBgProbe",
                "[$i] box y=%.3f..%.3f | tvar=%s | radku=%d | sfx=%s | typ=%s | bgUniform=%s | \"%s\"".format(
                    b.topF, b.bottomF, shapeSpan, b.lineCount, c.isSfx, c.bubbleType,
                    b.bgUniform, b.text.replace("\n", " / "),
                ),
            )
        }

        assertTrue("OCR musi neco najit", raw.isNotEmpty())

        val withoutShape = raw.count { it.shape == null }
        Log.i("LightBgProbe", "bloku BEZ nalezeneho tvaru: $withoutShape z ${raw.size}")

        // JADRO NAHLASENE CHYBY: pred opravou dostaly OBA bloky totozny tvar celeho balonu
        // (0.102..0.637), takze spodni bublina premalovala text te horni. Zadny tvar nesmi
        // sahat pres cizi OCR box.
        raw.forEachIndexed { i, block ->
            val shape = block.shape ?: return@forEachIndexed
            val top = shape.minOf { it.yF }
            val bottom = shape.maxOf { it.yF }
            raw.forEachIndexed { j, other ->
                if (i == j) return@forEachIndexed
                assertTrue(
                    "tvar bloku $i (y=$top..$bottom) saha na text bloku $j " +
                        "(y=${other.topF}..${other.bottomF}) - premaluje mu ho",
                    bottom < other.topF || top > other.bottomF,
                )
            }
        }
    }
}

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
 * SONDA: při jakém řádkování se dva řádky JEDNÉ bubliny ještě slijí do jednoho bloku?
 *
 * Proč to měřím: v nahlášených bublinách se řádky téže repliky rozpadly na samostatné bloky -
 * "I / SURVIVED, / TOO..." i "A / SURVIVOR..." - a každý se pak přeložil zvlášť (nebo vůbec).
 * Předchozí sonda (PunctuationBlockProbeTest) to potvrdila na reálném ML Kitu.
 *
 * Podezření je na práh v [shouldMerge]: `verticalGap < avgHeight * 0.9`. Ta výška ale není
 * řádková rozteč - je to výška OCR BOXU, který obepíná jen samotná písmena a u verzálek vychází
 * na 0,73 násobku velikosti písma (změřeno, viz [estimateNativeFontPx]). Mezera mezi boxy dvou
 * řádků je tedy `rozteč - 0,73` velikosti písma, a proti výšce boxu vyjde poměr, který se
 * k prahu 0,9 nebezpečně blíží.
 *
 * Sonda vykreslí tutéž dvouřádkovou repliku s několika roztečemi a vypíše NAMĚŘENÝ poměr
 * `verticalGap / avgHeight` i verdikt [shouldMerge]. Z toho se dá práh zvolit podle dat,
 * ne podle úvahy.
 *
 * Nic netvrdí (žádné assert), jen měří. Výsledky jdou do logcatu pod značkou "SpacingProbe".
 */
@RunWith(AndroidJUnit4::class)
class LineSpacingMergeProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fontPx = 40f

    /** Bublina se dvěma řádky verzálek, jejichž základny jsou [lineSpacingRatio] * fontPx od sebe. */
    private fun twoLineBubble(lineSpacingRatio: Float, w: Int = 900, h: Int = 500): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 20, 20))
        canvas.drawOval(w * 0.08f, h * 0.15f, w * 0.92f, h * 0.85f, Paint().apply { color = Color.WHITE })

        val text = Paint().apply {
            color = Color.BLACK
            textSize = fontPx
            isAntiAlias = true
        }
        val firstBaseline = h * 0.45f
        canvas.drawText("REFUGEE", w * 0.30f, firstBaseline, text)
        canvas.drawText("HUNTERS", w * 0.30f, firstBaseline + fontPx * lineSpacingRatio, text)
        return bmp
    }

    @Test
    fun probe_atWhichLineSpacingDoLinesStopMerging() = runBlocking {
        Log.i(TAG, "=== rozteč -> naměřený pomer gap/avgHeight -> slouci se? (prah je 0.9) ===")
        for (ratio in listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f)) {
            val blocks = OcrEngine(BubbleMaskSegmenter(context), BubbleBoxDetector(context), MangaOcrPipeline(context, BubbleBoxDetector(context))).recognize(twoLineBubble(ratio), "English")
            if (blocks.size < 2) {
                // Sloučené uz na urovni OCR/mergeNearbyLines - to je zadouci vysledek.
                Log.i(TAG, "roztec=${ratio}x -> ${blocks.size} blok(u): ${blocks.map { it.text }} (SLOUCENO)")
                continue
            }
            val a = blocks[0]
            val b = blocks[1]
            val avgHeight = ((a.bottomF - a.topF) + (b.bottomF - b.topF)) / 2f
            val gap = maxOf(0f, maxOf(a.topF, b.topF) - minOf(a.bottomF, b.bottomF))
            val ratioMeasured = if (avgHeight > 0f) gap / avgHeight else -1f
            Log.i(
                TAG,
                "roztec=${ratio}x -> ${blocks.size} bloky ${blocks.map { it.text }} " +
                    "avgHeight=${"%.4f".format(avgHeight)} gap=${"%.4f".format(gap)} " +
                    "POMER=${"%.2f".format(ratioMeasured)} shouldMerge=${shouldMerge(a, b)}",
            )
        }
    }

    /**
     * Nahlášená bublina má KRÁTKÝ první řádek ("I / SURVIVED, / TOO...", "A / SURVIVOR...").
     * Sonda výš měřila dva stejně dlouhé řádky - tahle měří ten skutečný tvar, protože
     * jednoznakový řádek má užší (a jak se ukázalo i nižší) OCR box, čímž stahuje `avgHeight`
     * dolů a poměr proti prahu nahoru.
     */
    @Test
    fun probe_theReportedShape_shortFirstLine() = runBlocking {
        Log.i(TAG, "=== nahlasenmy tvar: kratky prvni radek, realisticka roztec ===")
        for (ratio in listOf(1.2f, 1.3f, 1.4f, 1.5f)) {
            val w = 900
            val h = 500
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(20, 20, 20))
            canvas.drawOval(w * 0.08f, h * 0.15f, w * 0.92f, h * 0.85f, Paint().apply { color = Color.WHITE })
            val text = Paint().apply {
                color = Color.BLACK
                textSize = fontPx
                isAntiAlias = true
            }
            val firstBaseline = h * 0.42f
            canvas.drawText("I", w * 0.47f, firstBaseline, text)
            canvas.drawText("SURVIVED,", w * 0.32f, firstBaseline + fontPx * ratio, text)
            canvas.drawText("TOO...", w * 0.38f, firstBaseline + fontPx * ratio * 2f, text)

            val blocks = OcrEngine(BubbleMaskSegmenter(context), BubbleBoxDetector(context), MangaOcrPipeline(context, BubbleBoxDetector(context))).recognize(bmp, "English")
            if (blocks.size < 2) {
                Log.i(TAG, "kratky radek roztec=${ratio}x -> ${blocks.size} blok(u) ${blocks.map { it.text }} (SLOUCENO)")
                continue
            }
            val a = blocks[0]
            val b = blocks[1]
            val avgHeight = ((a.bottomF - a.topF) + (b.bottomF - b.topF)) / 2f
            val gap = maxOf(0f, maxOf(a.topF, b.topF) - minOf(a.bottomF, b.bottomF))
            Log.i(
                TAG,
                "kratky radek roztec=${ratio}x -> ${blocks.size} bloky ${blocks.map { it.text }} " +
                    "vyskaA=${"%.4f".format(a.bottomF - a.topF)} vyskaB=${"%.4f".format(b.bottomF - b.topF)} " +
                    "POMER=${"%.2f".format(gap / avgHeight)} shouldMerge=${shouldMerge(a, b)}",
            )
        }
    }

    /**
     * Kontrolní měření pro volbu prahu: jak TĚSNĚ u sebe můžou být dvě RŮZNÉ bubliny, a
     * zachytí je vizuální pojistka [hasWallBetween], i když je geometrie pustí?
     *
     * Tohle rozhoduje, jak vysoko se práh smí zvednout. Slučování stojí na DVOU nezávislých
     * kontrolách - geometrii a zdi mezi bloky - takže benevolentnější geometrie je bezpečná
     * přesně do té míry, do jaké zeď funguje.
     */
    @Test
    fun probe_howCloseCanTwoSeparateBubblesGet() = runBlocking {
        val w = 900
        val h = 900
        Log.i(TAG, "=== dve RUZNE bubliny ruzne blizko u sebe ===")
        for (bubbleGapRatio in listOf(0.02f, 0.05f, 0.10f, 0.20f)) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(20, 20, 20))
            val fill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            val stroke = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            val firstBottom = h * 0.30f
            val secondTop = firstBottom + h * bubbleGapRatio
            canvas.drawOval(w * 0.08f, h * 0.10f, w * 0.60f, firstBottom, fill)
            canvas.drawOval(w * 0.08f, h * 0.10f, w * 0.60f, firstBottom, stroke)
            canvas.drawOval(w * 0.08f, secondTop, w * 0.60f, secondTop + h * 0.20f, fill)
            canvas.drawOval(w * 0.08f, secondTop, w * 0.60f, secondTop + h * 0.20f, stroke)
            val text = Paint().apply {
                color = Color.BLACK
                textSize = fontPx
                isAntiAlias = true
            }
            canvas.drawText("REFUGEE", w * 0.15f, firstBottom - h * 0.07f, text)
            canvas.drawText("HUNTERS", w * 0.15f, secondTop + h * 0.12f, text)

            val blocks = OcrEngine(BubbleMaskSegmenter(context), BubbleBoxDetector(context), MangaOcrPipeline(context, BubbleBoxDetector(context))).recognize(bmp, "English")
            if (blocks.size < 2) {
                Log.i(TAG, "odstup bublin=${bubbleGapRatio} -> ${blocks.size} blok(u) ${blocks.map { it.text }} (CHYBNE SLOUCENO)")
                continue
            }
            val a = blocks[0]
            val b = blocks[1]
            val avgHeight = ((a.bottomF - a.topF) + (b.bottomF - b.topF)) / 2f
            val gap = maxOf(0f, maxOf(a.topF, b.topF) - minOf(a.bottomF, b.bottomF))
            val source = PixelSource { x, y -> bmp.getPixel(x, y) }
            Log.i(
                TAG,
                "odstup bublin=${bubbleGapRatio} -> POMER=${"%.2f".format(gap / avgHeight)} " +
                    "shouldMerge=${shouldMerge(a, b)} " +
                    "hasWall=${hasWallBetween(source, w, h, a, b)}",
            )
        }
    }

    private companion object {
        const val TAG = "SpacingProbe"
    }
}

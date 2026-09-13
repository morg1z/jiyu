package com.haise.jiyu.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Ořezané okraje jako frakce (0..1) PŮVODNÍHO (neořezaného) obrázku - kolik se uřízlo z které
 * strany. Viz [CropBordersTransformation.cropFractionsFor]. */
data class CropFractions(val leftF: Float, val topF: Float, val rightF: Float, val bottomF: Float)

/**
 * `pageUrl` - identita stránky, pod kterou se po dokončení ořezu uloží [CropFractions] (viz
 * companion object) - OCR souřadnice se počítají vůči PŮVODNÍMU obrázku (viz
 * [com.haise.jiyu.translate.PageBitmapLoader], který tuhle transformaci nepoužívá), ale Coil
 * intrinsicSize po transformaci je už OŘÍZNUTÁ - bez znalosti ořezu by překladový overlay
 * počítal pozice bublin vůči jinému obrázku, než ve kterém OCR skutečně měřilo (nahlášeno
 * v auditu jako systematický posun bublin při zapnutém "Oříznout okraje").
 */
class CropBordersTransformation(private val pageUrl: String) : Transformation {

    override val cacheKey: String = "crop_borders"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 20 || h < 20) return input

        val bounds = detectContentBounds(input)
        if (bounds == null) {
            cropFractions.remove(pageUrl)
            return input
        }
        val (left, top, right, bottom) = bounds

        cropFractions[pageUrl] = CropFractions(
            leftF = left / w.toFloat(),
            topF = top / h.toFloat(),
            rightF = (w - 1 - right) / w.toFloat(),
            bottomF = (h - 1 - bottom) / h.toFloat(),
        )

        val cropW = (right - left + 1).coerceAtLeast(1)
        val cropH = (bottom - top + 1).coerceAtLeast(1)
        return Bitmap.createBitmap(input, left, top, cropW, cropH)
    }

    private data class ContentBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** null = žádný významný okraj k oříznutí (bitmapa se vrátí beze změny). */
    private fun detectContentBounds(input: Bitmap): ContentBounds? {
        val w = input.width
        val h = input.height

        // Detect border color from 4 corners (average)
        val corners = listOf(
            input.getPixel(0, 0),
            input.getPixel(w - 1, 0),
            input.getPixel(0, h - 1),
            input.getPixel(w - 1, h - 1),
        )
        val borderR = corners.sumOf { Color.red(it) }   / 4
        val borderG = corners.sumOf { Color.green(it) } / 4
        val borderB = corners.sumOf { Color.blue(it) }  / 4

        fun isBorder(pixel: Int): Boolean {
            return abs(Color.red(pixel) - borderR) +
                   abs(Color.green(pixel) - borderG) +
                   abs(Color.blue(pixel) - borderB) < 40
        }

        // Step size for sampling (faster on large bitmaps)
        val stepX = maxOf(1, w / 30)
        val stepY = maxOf(1, h / 30)

        var top = 0
        outer@ for (y in 0 until h) {
            for (x in 0 until w step stepX) {
                if (!isBorder(input.getPixel(x, y))) { top = y; break@outer }
            }
        }
        var bottom = h - 1
        outer@ for (y in h - 1 downTo 0) {
            for (x in 0 until w step stepX) {
                if (!isBorder(input.getPixel(x, y))) { bottom = y; break@outer }
            }
        }
        var left = 0
        outer@ for (x in 0 until w) {
            for (y in 0 until h step stepY) {
                if (!isBorder(input.getPixel(x, y))) { left = x; break@outer }
            }
        }
        var right = w - 1
        outer@ for (x in w - 1 downTo 0) {
            for (y in 0 until h step stepY) {
                if (!isBorder(input.getPixel(x, y))) { right = x; break@outer }
            }
        }

        // Only crop if the border is at least 1% of the image dimension
        val minCrop = minOf(w, h) / 100
        val noSignificantCrop = top < minCrop && left < minCrop &&
            (w - 1 - right) < minCrop && (h - 1 - bottom) < minCrop
        if (noSignificantCrop) return null

        return ContentBounds(left, top, right, bottom)
    }

    companion object {
        private val cropFractions = ConcurrentHashMap<String, CropFractions>()

        /** Ořezové frakce naposledy spočítané pro tuhle stránku, nebo null (žádný ořez / stránka
         * ještě nenačtena). Volá se z [TranslationLayer.kt] až PO úspěšném načtení obrázku, kdy
         * už tahle transformace doběhla. */
        fun cropFractionsFor(pageUrl: String): CropFractions? = cropFractions[pageUrl]
    }
}

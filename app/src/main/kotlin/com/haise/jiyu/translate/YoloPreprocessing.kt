package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer

/**
 * Sdílený "letterbox" preprocessing pro YOLOv8 modely - viz [BubbleBoxDetector] a
 * [BubbleMaskSegmenter], které oba potřebují stejný vstupní formát (čtvercový NCHW tenzor,
 * kanály R,G,B po sobě, hodnoty 0..1). Obrázek se zmenší se zachováním poměru stran a doplní
 * šedým okrajem (114,114,114 - stejná hodnota, jakou používá export obou modelů) na
 * [size]x[size].
 */
internal object YoloPreprocessing {

    fun letterboxToFloatBuffer(bitmap: Bitmap, params: LetterboxParams, size: Int): FloatBuffer {
        val scaledW = (bitmap.width * params.scale).toInt().coerceAtLeast(1)
        val scaledH = (bitmap.height * params.scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            Canvas(canvasBitmap).apply {
                drawColor(Color.rgb(114, 114, 114))
                drawBitmap(scaled, params.padX, params.padY, null)
            }

            val pixels = IntArray(size * size)
            canvasBitmap.getPixels(pixels, 0, size, 0, 0, size, size)

            val buffer = FloatBuffer.allocate(3 * size * size)
            for (i in pixels.indices) buffer.put(((pixels[i] shr 16) and 0xFF) / 255f)
            for (i in pixels.indices) buffer.put(((pixels[i] shr 8) and 0xFF) / 255f)
            for (i in pixels.indices) buffer.put((pixels[i] and 0xFF) / 255f)
            buffer.rewind()
            return buffer
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            canvasBitmap.recycle()
        }
    }
}

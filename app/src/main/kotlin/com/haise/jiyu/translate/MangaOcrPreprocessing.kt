package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer

/**
 * Preprocessing vstupu pro `manga_ocr_encoder.onnx` - viz [MangaOcrPipeline]. Kromě
 * standardního resize+normalize (shodně s referenčním `preprocessor_config.json`
 * v C:/bml/manga_ocr_spike/onnx_export_nocache/) navíc dělá odbarvení do šedi a zpět do
 * RGB - to referenční `preprocessor_config.json` NEobsahuje, dělá ho přímo
 * `MangaOcr.__call__` v Pythonu před předáním do image processoru. Bez tohohle kroku
 * vychází výstup číselně jinak, než referenční model (ověřeno na reálných datech -
 * viz spec sekce "Ověření na reálných datech").
 */
internal object MangaOcrPreprocessing {

    const val INPUT_SIZE = 224

    fun toEncoderInput(crop: Bitmap): FloatBuffer {
        val grayscaled = toGrayscaleRgb(crop)
        try {
            val scaled = Bitmap.createScaledBitmap(grayscaled, INPUT_SIZE, INPUT_SIZE, true)
            try {
                val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
                scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

                val buffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
                for (i in pixels.indices) buffer.put(normalize((pixels[i] shr 16) and 0xFF))
                for (i in pixels.indices) buffer.put(normalize((pixels[i] shr 8) and 0xFF))
                for (i in pixels.indices) buffer.put(normalize(pixels[i] and 0xFF))
                buffer.rewind()
                return buffer
            } finally {
                if (scaled !== grayscaled) scaled.recycle()
            }
        } finally {
            if (grayscaled !== crop) grayscaled.recycle()
        }
    }

    /** `(pixel/255 - 0.5) / 0.5`, tj. `image_mean=image_std=0.5` z preprocessor_config.json. */
    private fun normalize(channel: Int): Float = (channel / 255f - 0.5f) / 0.5f

    /**
     * `img.convert("L").convert("RGB")` z Pythonu - PIL vzorec pro šedotón
     * (`L = (R*19595 + G*38470 + B*7471 + 0x8000) >> 16`), pak stejná hodnota do
     * všech tří kanálů zpátky.
     */
    private fun toGrayscaleRgb(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = ((r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16).coerceIn(0, 255)
            pixels[i] = Color.rgb(l, l, l)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}

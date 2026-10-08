package com.haise.jiyu.translate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Docasny debug test: realny vyrez Vagabond ch6 p008 (render box 237x980),
 * text region = OCR box "WHAT AM I THINKING!?" posunuty do patch souradnic.
 * Spousti pipeline per-stage a dumpuje masku i vysledek (raw RGB -> PIL).
 */
class RealPagePatchDebugTest {

    private fun dumpRgb(path: String, px: IntArray, w: Int, h: Int) {
        File(path).parentFile?.mkdirs()
        FileOutputStream(path).use { out ->
            val hdr = ByteBuffer.allocate(8)
            hdr.putInt(w); hdr.putInt(h)
            out.write(hdr.array())
            for (c in px) {
                out.write((c shr 16) and 0xFF)
                out.write((c shr 8) and 0xFF)
                out.write(c and 0xFF)
            }
        }
    }

    private fun dumpMask(path: String, mask: BooleanArray, lum: IntArray, w: Int, h: Int) {
        // maskovane cervene, nemaskovane originalni
        val px = IntArray(w * h)
        for (i in px.indices) {
            val l = lum[i]
            px[i] = if (mask[i]) (0xFF shl 24) or 0x00FF00 else (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
        dumpRgb(path, px, w, h)
    }

    @Test
    fun debugRealPatch() {
        val raw = RealPagePatchDebugTest::class.java.getResourceAsStream("/patch_region_p008.rgb")!!
            .readBytes()
        val buf = ByteBuffer.wrap(raw)
        val w = buf.int; val h = buf.int
        val pixels = IntArray(w * h)
        val luminance = IntArray(w * h)
        for (i in pixels.indices) {
            val r = buf.get().toInt() and 0xFF
            val g = buf.get().toInt() and 0xFF
            val b = buf.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            luminance[i] = luminanceOf(pixels[i])
        }

        val textLeft = 16; val textTop = 27; val textRight = 222; val textBottom = 163
        val pad = textRegionPadding(textBottom - textTop)
        val rl = textLeft - pad; val rt = textTop - pad
        val rr = textRight + pad; val rb = textBottom + pad

        val isText = markTextPixels(luminance, w, h)
        restrictToTextRegion(isText, w, h, rl, rt, rr, rb, true)
        dumpMask("build/outputs/dbg_mask_mark.rgb", isText, luminance, w, h)

        suppressTextureNoise(isText, luminance, w, h, rl, rt, rr, rb, true)
        dumpMask("build/outputs/dbg_mask_suppress.rgb", isText, luminance, w, h)

        dilate(isText, w, h, 2)
        restrictToTextRegion(isText, w, h, rl, rt, rr, rb, true)
        sealEnclosedHoles(isText, w, h, rl, rt, rr, rb, true)

        // Halo-obrys glyphu - zrcadlí HALO_COVER_DILATION v produkční pipeline
        // (těsný region, před ink-floodem).
        dilate(isText, w, h, 3)
        restrictToTextRegion(isText, w, h, rl, rt, rr, rb, true)
        sealEnclosedHoles(isText, w, h, rl, rt, rr, rb, true)
        dumpMask("build/outputs/dbg_mask_halo.rgb", isText, luminance, w, h)

        var reach = (pad + (textBottom - textTop) / 8).coerceIn(6, 32)
        val reachCap = maxOf(textBottom - textTop, reach)
        while (true) {
            val clipped = floodClippedGlyphs(isText, luminance, w, h, rl, rt, rr, rb, reach)
            if (!clipped || reach >= reachCap) break
            reach = (reach * 2).coerceAtMost(reachCap)
        }
        dilate(isText, w, h, 1)
        restrictToTextRegion(isText, w, h, rl - reach, rt - reach, rr + reach, rb + reach, true)
        sealEnclosedHoles(isText, w, h, rl - reach, rt - reach, rr + reach, rb + reach, true)
        dumpMask("build/outputs/dbg_mask_final.rgb", isText, luminance, w, h)

        var masked = 0
        for (m in isText) if (m) masked++
        println("DBG masked=$masked / ${w * h}")

        continuePeriodicTexture(pixels, luminance, isText, w, h)
        dumpMask("build/outputs/dbg_mask_afterperiod.rgb", isText, luminance, w, h)
        dumpRgb("build/outputs/dbg_after_period.rgb", pixels, w, h)

        resampleFromLocalField(pixels, luminance, isText, w, h)
        dumpMask("build/outputs/dbg_mask_afterresample.rgb", isText, luminance, w, h)
        dumpRgb("build/outputs/dbg_after_resample.rgb", pixels, w, h)

        fillNearestSource(pixels, isText, w, h)
        dumpRgb("build/outputs/dbg_final.rgb", pixels, w, h)

        assertTrue(true)
    }
}

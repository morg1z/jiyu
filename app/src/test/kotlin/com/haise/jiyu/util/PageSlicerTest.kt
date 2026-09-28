package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSlicerTest {

    // ── sampleSizeFor ─────────────────────────────────────────────────────────

    @Test
    fun `small image needs no downsampling`() {
        assertEquals(1, PageSlicer.sampleSizeFor(800, 1200, 2_000_000))
    }

    @Test
    fun `tall webtoon page samples down to pixel budget`() {
        // 800x30000 = 24 MP -> sample 4 => 200x7500 = 1.5 MP <= 2 MP
        assertEquals(4, PageSlicer.sampleSizeFor(800, 30_000, 2_000_000))
    }

    @Test
    fun `extreme page gets larger power-of-two sample`() {
        // 1600x40000 = 64 MP -> sample 8 => 200x5000 = 1 MP <= 2 MP
        assertEquals(8, PageSlicer.sampleSizeFor(1600, 40_000, 2_000_000))
    }

    @Test
    fun `sample is always power of two`() {
        for (w in listOf(400, 800, 1600, 3000)) {
            for (h in listOf(1_000, 8_192, 20_000, 60_000)) {
                val s = PageSlicer.sampleSizeFor(w, h, 2_000_000)
                assertTrue("sample $s neni mocnina 2", s and (s - 1) == 0)
            }
        }
    }

    // ── isAnimatedMagic ───────────────────────────────────────────────────────

    @Test
    fun `gif header is animated`() {
        val gif = "GIF89a".toByteArray() + ByteArray(100)
        assertTrue(PageSlicer.isAnimatedMagic(gif))
    }

    @Test
    fun `animated webp with ANIM chunk is detected`() {
        val riff = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + "ANIM".toByteArray() + ByteArray(100)
        assertTrue(PageSlicer.isAnimatedMagic(riff))
    }

    private fun webpWithVp8x(flags: Int, tail: ByteArray = ByteArray(100)): ByteArray {
        // RIFF(4) + size(4) + WEBP(4) + "VP8X"(4) + chunkSize(4) + flags(1) + ...
        return "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() +
            "VP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + byteArrayOf(flags.toByte()) + tail
    }

    @Test
    fun `webp with VP8X animation flag is detected even without ANIM in header`() {
        // ANIM/ANMF leží až za velkým ICCP chunkem - autoritativní flags byte to pokryje.
        val riff = webpWithVp8x(0x02) + ByteArray(2048) // "ANIM" mimo okno
        assertTrue(PageSlicer.isAnimatedMagic(riff))
    }

    @Test
    fun `webp with VP8X but no animation flag is static`() {
        // flags: alpha(0x10) + ICC(0x20), bez animačního bitu
        val riff = webpWithVp8x(0x30)
        assertFalse(PageSlicer.isAnimatedMagic(riff))
    }

    @Test
    fun `webp VP8X static flag but ANIM present still detected`() {
        // Poškozený/exotický soubor - flags tvrdí statiku, ale ANIM chunk existuje.
        val riff = webpWithVp8x(0x00, tail = "ANIM".toByteArray() + ByteArray(96))
        assertTrue(PageSlicer.isAnimatedMagic(riff))
    }

    @Test
    fun `static webp without ANIM chunk passes`() {
        val riff = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + "VP8 ".toByteArray() + ByteArray(100)
        assertFalse(PageSlicer.isAnimatedMagic(riff))
    }

    @Test
    fun `jpeg passes as static`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "JFIF".toByteArray() + ByteArray(100)
        assertFalse(PageSlicer.isAnimatedMagic(jpeg))
    }

    @Test
    fun `static png passes`() {
        val png = byteArrayOf(0x89.toByte()) + "PNG\r\n\u001A\n".toByteArray() + "IHDR".toByteArray() + ByteArray(100)
        assertFalse(PageSlicer.isAnimatedMagic(png))
    }

    @Test
    fun `apng with acTL chunk is animated`() {
        val apng = byteArrayOf(0x89.toByte()) + "PNG\r\n\u001A\n".toByteArray() + "IHDRxxxxxx".toByteArray() + "acTL".toByteArray() + ByteArray(64)
        assertTrue(PageSlicer.isAnimatedMagic(apng))
    }

    @Test
    fun `tiny header never crashes`() {
        assertFalse(PageSlicer.isAnimatedMagic(byteArrayOf(1, 2)))
        assertFalse(PageSlicer.isAnimatedMagic(ByteArray(0)))
    }

    // ── diskKeyFor ───────────────────────────────────────────────────────────

    @Test
    fun `disk key is bare url without proxy`() {
        assertEquals("http://h.cz/p.jpg", PageSlicer.diskKeyFor("http://h.cz/p.jpg", proxyEnabled = false))
    }

    @Test
    fun `disk key gets original suffix with proxy`() {
        assertEquals("http://h.cz/p.jpg#original", PageSlicer.diskKeyFor("http://h.cz/p.jpg", proxyEnabled = true))
    }

    @Test
    fun `disk key replaces existing fragment with original suffix`() {
        // Fragment (#mplus_key apod.) nesmí přežít do disk klíče - konvence PageBitmapLoader.
        assertEquals("http://h.cz/p.jpg#original", PageSlicer.diskKeyFor("http://h.cz/p.jpg#x=1", proxyEnabled = true))
    }

    // ── TallImageSlicer.computeSlices (sdílená matematika řezů) ───────────────

    @Test
    fun `slices are contiguous and cover whole height`() {
        val slices = TallImageSlicer.computeSlices(11_000, 2048)
        assertEquals(0, slices.first().first)
        assertEquals(11_000, slices.last().last + 1)
        slices.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(slices.all { it.last - it.first + 1 <= 2048 })
    }

    @Test
    fun `image under slice height is single slice`() {
        val slices = TallImageSlicer.computeSlices(2_000, 2048)
        assertEquals(1, slices.size)
        assertEquals(0, slices[0].first)
        assertEquals(1_999, slices[0].last)
    }
}

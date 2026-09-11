package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/** Čistý JVM test [sortIntoReadingOrder] - žádná Android/Bitmap závislost. */
class ReadingOrderTest {

    private fun block(text: String, l: Float, t: Float, r: Float, b: Float) =
        RawTextBlock(text = text, leftF = l, topF = t, rightF = r, bottomF = b)

    @Test
    fun `japanese manga row reads right to left`() {
        val left = block("levá", 0.1f, 0.1f, 0.3f, 0.2f)
        val right = block("pravá", 0.6f, 0.1f, 0.8f, 0.2f)
        // Vloženo v "nepřirozeném" pořadí (levá první) - řadič musí přehodit.
        val result = sortIntoReadingOrder(listOf(left, right), rightToLeft = true)
        assertEquals(listOf("pravá", "levá"), result.map { it.text })
    }

    @Test
    fun `non-japanese row reads left to right`() {
        val left = block("levá", 0.1f, 0.1f, 0.3f, 0.2f)
        val right = block("pravá", 0.6f, 0.1f, 0.8f, 0.2f)
        val result = sortIntoReadingOrder(listOf(left, right), rightToLeft = false)
        assertEquals(listOf("levá", "pravá"), result.map { it.text })
    }

    @Test
    fun `top row always comes before bottom row regardless of horizontal position`() {
        val topRight = block("nahoře", 0.6f, 0.1f, 0.8f, 0.2f)
        val bottomLeft = block("dole", 0.1f, 0.5f, 0.3f, 0.6f)
        val result = sortIntoReadingOrder(listOf(bottomLeft, topRight), rightToLeft = true)
        assertEquals(listOf("nahoře", "dole"), result.map { it.text })
    }

    @Test
    fun `reconstructs a realistic two-row manga page in correct reading order`() {
        // Horní řádek: 2 bubliny vedle sebe (čte se zprava doleva). Dolní řádek: 1 bublina.
        val topLeftBubble = block("horní-levá", 0.05f, 0.05f, 0.35f, 0.20f)
        val topRightBubble = block("horní-pravá", 0.55f, 0.04f, 0.90f, 0.19f)
        val bottomBubble = block("dolní", 0.20f, 0.60f, 0.70f, 0.75f)

        val shuffled = listOf(bottomBubble, topLeftBubble, topRightBubble)
        val result = sortIntoReadingOrder(shuffled, rightToLeft = true)

        assertEquals(listOf("horní-pravá", "horní-levá", "dolní"), result.map { it.text })
    }

    @Test
    fun `single block is returned unchanged`() {
        val only = block("sám", 0.1f, 0.1f, 0.3f, 0.2f)
        assertEquals(listOf(only), sortIntoReadingOrder(listOf(only), rightToLeft = true))
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<RawTextBlock>(), sortIntoReadingOrder(emptyList(), rightToLeft = true))
    }

    // ── countBackwardReadingOrderJumps (viz item 19 - mereni spatneho poradi cteni) ──

    @Test
    fun `a normal top-to-bottom sequence has no backward jumps`() {
        val blocks = listOf(
            block("first", 0.1f, 0.05f, 0.9f, 0.15f),
            block("second", 0.1f, 0.25f, 0.9f, 0.35f),
            block("third", 0.1f, 0.45f, 0.9f, 0.55f),
        )
        assertEquals(0, countBackwardReadingOrderJumps(blocks))
    }

    @Test
    fun `a large backward jump up the page is counted`() {
        val blocks = listOf(
            block("bottom of a tall left panel", 0.1f, 0.05f, 0.4f, 0.60f),
            // Dalsi v poradi zacina daleko NAD koncem predchoziho - typicky signal, ze se
            // spatne seskupily dva RUZNE panely do jedne "radky".
            block("top of a short right panel", 0.5f, 0.06f, 0.9f, 0.20f),
        )
        assertEquals(1, countBackwardReadingOrderJumps(blocks))
    }

    @Test
    fun `a small overlap within the tolerance is not counted`() {
        val blocks = listOf(
            block("first", 0.1f, 0.05f, 0.9f, 0.20f),
            block("second", 0.1f, 0.19f, 0.9f, 0.35f), // mírný překryv, ne skutečný skok
        )
        assertEquals(0, countBackwardReadingOrderJumps(blocks))
    }

    @Test
    fun `fewer than two blocks never counts a jump`() {
        assertEquals(0, countBackwardReadingOrderJumps(emptyList()))
        assertEquals(0, countBackwardReadingOrderJumps(listOf(block("only", 0.1f, 0.1f, 0.3f, 0.2f))))
    }

    @Test
    fun `multiple backward jumps are all counted`() {
        val blocks = listOf(
            block("a", 0.1f, 0.05f, 0.4f, 0.60f),
            block("b", 0.5f, 0.06f, 0.9f, 0.20f),
            block("c", 0.1f, 0.65f, 0.4f, 0.90f),
            block("d", 0.5f, 0.21f, 0.9f, 0.30f),
        )
        assertEquals(2, countBackwardReadingOrderJumps(blocks))
    }
}

package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test geometrie [layoutTranslationBlocks] (žádná Android závislost).
 * Reprodukuje vzor z reálného screenshotu (víc bublin blízko sebe na jedné
 * stránce, kde přeložený text roste dolů) a ověřuje, že po expanzi žádné dva
 * finální boxy (rightF/maxBottomF) nekolidují.
 */
class TranslationLayoutTest {

    private fun block(l: Float, t: Float, r: Float, b: Float, text: String = "x") =
        TranslatedBlock(originalText = text, translatedText = text, leftF = l, topF = t, rightF = r, bottomF = b)

    private fun overlaps(a: PositionedTranslationBlock, b: PositionedTranslationBlock): Boolean {
        val horizontallyOverlaps = a.leftF < b.rightF && b.leftF < a.rightF
        val verticallyOverlaps = a.topF < b.maxBottomF && b.topF < a.maxBottomF
        return horizontallyOverlaps && verticallyOverlaps
    }

    @Test
    fun `single block expands to full page when no neighbors`() {
        val positioned = layoutTranslationBlocks(listOf(block(0.4f, 0.2f, 0.6f, 0.25f)))
        assertEquals(1, positioned.size)
        assertEquals(0f, positioned[0].leftF)
        assertEquals(1f, positioned[0].rightF)
        assertEquals(1f, positioned[0].maxBottomF)
    }

    @Test
    fun `two blocks in same row do not get overlapping horizontal ranges`() {
        // Bloky drženy dál od okrajů stránky, aby vazbu na šířku expanze určovala
        // vzájemná mezera mezi nimi, ne blízkost okraje stránky (0/1).
        val blocks = listOf(
            block(0.30f, 0.2f, 0.40f, 0.25f),
            block(0.60f, 0.2f, 0.70f, 0.25f),
        )
        val positioned = layoutTranslationBlocks(blocks)
        val (a, b) = positioned

        assertTrue("expanded left block must not cross into right block's original region", a.rightF <= b.leftF + 1e-4f)
        // Symetrická expanze kolem středu - obě strany dostanou stejný podíl mezery,
        // takže se setkají přesně v polovině mezery mezi originály (0.5).
        assertEquals(0.5f, a.rightF, 0.01f)
        assertEquals(0.5f, b.leftF, 0.01f)
    }

    @Test
    fun `block below caps vertical growth of block above`() {
        val blocks = listOf(
            block(0.2f, 0.1f, 0.7f, 0.15f),
            block(0.2f, 0.3f, 0.7f, 0.35f),
        )
        val positioned = layoutTranslationBlocks(blocks)
        val above = positioned.first { it.topF == 0.1f }
        assertTrue("above block's max growth must stop before the block below starts", above.maxBottomF <= 0.3f)
    }

    @Test
    fun `dense page from bug report produces no overlapping final boxes`() {
        // Přibližná rekonstrukce rozložení z reportovaného screenshotu - víc bublin
        // natěsno vedle/pod sebou na jedné stránce.
        val blocks = listOf(
            block(0.08f, 0.55f, 0.28f, 0.62f), // "MOŽNÁ, ŽE EXISTUJÍ JI[NÉ]"
            block(0.30f, 0.60f, 0.48f, 0.66f), // "KTERÉ MOHU POUŽÍT?"
            block(0.55f, 0.52f, 0.72f, 0.58f), // "UKÁZÁVELNEVÍM..."
            block(0.75f, 0.50f, 0.88f, 0.56f), // "POZOR, ZNÁTINSTANTLY..."
            block(0.90f, 0.46f, 0.99f, 0.52f), // "BY LA AKTI VOVÁ..."
            block(0.10f, 0.75f, 0.45f, 0.80f), // "Magie Ovládá sílu tíže..."
            block(0.10f, 0.85f, 0.45f, 0.90f), // "Magie Útočí bleskem na cíl"
            block(0.60f, 0.78f, 0.90f, 0.84f), // "POJĎME SE POZDÍVAT NA JEDEN."
            block(0.70f, 0.70f, 0.95f, 0.76f), // "VYPADÁ TO TAK, ŽE MÁ..."
        )
        val positioned = layoutTranslationBlocks(blocks)

        for (i in positioned.indices) {
            for (j in i + 1 until positioned.size) {
                assertTrue(
                    "blocks $i and $j must not overlap after layout: ${positioned[i]} vs ${positioned[j]}",
                    !overlaps(positioned[i], positioned[j]),
                )
            }
        }
    }

    @Test
    fun `expansion never shrinks below original block bounds`() {
        val blocks = listOf(
            block(0.10f, 0.10f, 0.20f, 0.15f),
            block(0.12f, 0.10f, 0.22f, 0.15f), // uměle mírně překrývající se OCR boxy
        )
        val positioned = layoutTranslationBlocks(blocks)
        positioned.forEachIndexed { i, pos ->
            val original = blocks[i]
            assertTrue(pos.leftF <= original.leftF + 1e-4f)
            assertTrue(pos.rightF >= original.rightF - 1e-4f)
            assertTrue(pos.maxBottomF >= original.bottomF - 1e-4f)
        }
    }
}

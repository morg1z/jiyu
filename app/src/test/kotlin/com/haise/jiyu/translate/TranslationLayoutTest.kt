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

    private fun blockWithShape(shape: List<BubbleShapePoint>, text: String = "x") =
        TranslatedBlock(
            originalText = text, translatedText = text,
            leftF = shape.minOf { it.leftF }, topF = shape.first().yF,
            rightF = shape.maxOf { it.rightF }, bottomF = shape.last().yF,
            shape = shape,
        )

    private fun overlaps(a: PositionedTranslationBlock, b: PositionedTranslationBlock): Boolean {
        val horizontallyOverlaps = a.leftF < b.rightF && b.leftF < a.rightF
        val verticallyOverlaps = a.topF < b.maxBottomF && b.topF < a.maxBottomF
        return horizontallyOverlaps && verticallyOverlaps
    }

    @Test
    fun `non-uniform background block expands far less than a uniform bubble background`() {
        // Titulkový/dekorativní text napsaný přímo přes kresbu (bgUniform=false) by neměl
        // roztahovat heuristický box stejně štědře jako skutečná bublina - jinak barevná
        // placka (viz OcrEngine.sampleBackgroundColor) zbytečně zakryje spoustu kresby
        // (viz uživatelská zpětná vazba - hnědá placka přes titulní stránku Chainsaw Man).
        val uniformBlock = TranslatedBlock(
            originalText = "x", translatedText = "x",
            leftF = 0.4f, topF = 0.2f, rightF = 0.6f, bottomF = 0.25f,
            bgUniform = true,
        )
        val nonUniformBlock = TranslatedBlock(
            originalText = "x", translatedText = "x",
            leftF = 0.4f, topF = 0.2f, rightF = 0.6f, bottomF = 0.25f,
            bgUniform = false,
        )
        val uniformPositioned = layoutTranslationBlocks(listOf(uniformBlock))[0]
        val nonUniformPositioned = layoutTranslationBlocks(listOf(nonUniformBlock))[0]

        val uniformWidth = uniformPositioned.rightF - uniformPositioned.leftF
        val nonUniformWidth = nonUniformPositioned.rightF - nonUniformPositioned.leftF
        assertTrue(
            "non-uniform background must expand less than uniform ($nonUniformWidth vs $uniformWidth)",
            nonUniformWidth < uniformWidth * 0.8f,
        )
        // Uniformní bublina bez souseda roste až k okrajům stránky (existující chování).
        assertEquals(1f, uniformWidth, 0.01f)
        // Pořád musí krýt aspoň vlastní OCR rozsah, jen se štědře nenafukovat navíc.
        assertTrue(nonUniformPositioned.leftF <= nonUniformBlock.leftF + 1e-4f)
        assertTrue(nonUniformPositioned.rightF >= nonUniformBlock.rightF - 1e-4f)
    }

    @Test
    fun `single block expands horizontally to page edges but caps vertical growth when no neighbors`() {
        // Vodorovně beze zbytku sousedů roste až k okrajům stránky (bezpečné - box se
        // stejně zobrazí jen tak široký, jak potřebuje AutoFitTranslatedText).
        // Svisle ALE MUSÍ mít strop i bez souseda: box teď fyzicky vyplňuje aspoň vlastní
        // rozsah bubliny (viz ReaderScreen.kt .heightIn(min=)), takže "žádný soused dole
        // = roztáhni box přes zbytek stránky" by v reálné appce vytvořilo obří box přes
        // spoustu prázdného pozadí (reprodukováno a opraveno na reálném zařízení).
        val positioned = layoutTranslationBlocks(listOf(block(0.4f, 0.2f, 0.6f, 0.25f)))
        assertEquals(1, positioned.size)
        assertEquals(0f, positioned[0].leftF)
        assertEquals(1f, positioned[0].rightF)
        assertTrue("vertical growth without a neighbor must stay bounded, not reach the page edge", positioned[0].maxBottomF < 0.5f)
        assertEquals(0.35f, positioned[0].maxBottomF, 0.01f)
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

    @Test
    fun `block with shape uses shape bounding box and skips heuristic expansion`() {
        val shape = listOf(
            BubbleShapePoint(0.20f, 0.30f, 0.60f),
            BubbleShapePoint(0.25f, 0.22f, 0.68f),
            BubbleShapePoint(0.30f, 0.25f, 0.65f),
        )
        val positioned = layoutTranslationBlocks(listOf(blockWithShape(shape)))

        assertEquals(1, positioned.size)
        val pos = positioned[0]
        // Ohraničující obdélník tvaru, ŽÁDNÁ heuristická expanze k okrajům stránky.
        assertEquals(0.22f, pos.leftF, 0.001f)
        assertEquals(0.68f, pos.rightF, 0.001f)
        assertEquals(0.20f, pos.minTopF, 0.001f)
        assertEquals(0.30f, pos.maxBottomF, 0.001f)
    }

    @Test
    fun `blocks with and without shape can coexist in the same page`() {
        val shape = listOf(BubbleShapePoint(0.10f, 0.10f, 0.30f), BubbleShapePoint(0.15f, 0.10f, 0.30f))
        val plain = block(0.60f, 0.60f, 0.80f, 0.65f)
        val positioned = layoutTranslationBlocks(listOf(blockWithShape(shape), plain))

        assertEquals(2, positioned.size)
        // Blok bez tvaru pořád projde starou heuristikou nezávisle na tom shape-based bloku
        // (nepřekrývají se, takže by se navzájem neměly nijak omezovat) - ověřuje se, že
        // heuristika pořád běží, ne přesná cílová hodnota (ta závisí na pozici bloku na
        // stránce, viz dedikované heuristické testy výše).
        val plainPositioned = positioned.first { it.block === plain }
        assertTrue("heuristic must still expand a shape-less block beyond its own OCR bounds", plainPositioned.leftF < plain.leftF)
        assertTrue(plainPositioned.leftF >= 0f)
    }

    @Test
    fun `heuristic block does not expand across an adjacent shape-based bubble`() {
        // Reprodukce nahlášeného bugu: tvarová bublina ("Budeme se učit spolu") vedle
        // heuristické bubliny ("C'mon"/"No tak"), o které heuristika vůbec nevěděla a
        // klidně skrz ni (a přes kresbu za ní) protáhla svůj bílý box - viz uživatelský
        // screenshot, bílý pruh z "NO TAK." přes sousední bublinu i do obrázku.
        val shape = listOf(
            BubbleShapePoint(0.40f, 0.50f, 0.62f),
            BubbleShapePoint(0.45f, 0.48f, 0.64f),
            BubbleShapePoint(0.50f, 0.50f, 0.62f),
        )
        val heuristic = block(0.20f, 0.48f, 0.30f, 0.55f) // bgUniform=true (výchozí) => expandFactor 3x

        val positioned = layoutTranslationBlocks(listOf(blockWithShape(shape), heuristic))
        val heuristicPos = positioned.first { it.block === heuristic }
        val shapePos = positioned.first { it.block.shape != null }

        assertTrue(
            "heuristic block must not expand past the shape block's left edge (${heuristicPos.rightF} vs ${shapePos.leftF})",
            heuristicPos.rightF <= shapePos.leftF + 1e-4f,
        )
    }
}

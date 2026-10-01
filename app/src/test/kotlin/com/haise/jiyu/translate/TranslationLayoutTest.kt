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
        // Uniformní bublina bez souseda smí narůst nejvýše 3× vlastní OCR šířku.
        assertEquals(0.6f, uniformWidth, 0.01f)
        // Pořád musí krýt aspoň vlastní OCR rozsah, jen se štědře nenafukovat navíc.
        assertTrue(nonUniformPositioned.leftF <= nonUniformBlock.leftF + 1e-4f)
        assertTrue(nonUniformPositioned.rightF >= nonUniformBlock.rightF - 1e-4f)
    }

    @Test
    fun `single block expands up to 3x own width but caps vertical growth when no neighbors`() {
        // Vodorovně beze zbytku sousedů roste symetricky kolem středu, ale nikdy víc
        // než 3× vlastní OCR šířku - jinak by box přetékal přes bublinu do kresby.
        // Svisle ALE MUSÍ mít strop i bez souseda: box teď fyzicky vyplňuje aspoň vlastní
        // rozsah bubliny (viz ReaderScreen.kt .heightIn(min=)), takže "žádný soused dole
        // = roztáhni box přes zbytek stránky" by v reálné appce vytvořilo obří box přes
        // spoustu prázdného pozadí (reprodukováno a opraveno na reálném zařízení).
        val positioned = layoutTranslationBlocks(listOf(block(0.4f, 0.2f, 0.6f, 0.25f)))
        assertEquals(1, positioned.size)
        assertEquals(0.2f, positioned[0].leftF, 0.01f)
        assertEquals(0.8f, positioned[0].rightF, 0.01f)
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
    fun `degenerate shape much smaller than its text falls back to heuristic layout`() {
        // Audit Vagabondu ch.1 ("SOMEONE'S THERE"): OCR box "SOMEONE" byl zdravý
        // (14 % šířky stránky), ale detekce bubliny vrátila tvar o velikosti bodu
        // (0,05 % x 0,2 %). S takovým tvarem by se box i clip zmenšily na bod a
        // překlad byl na stránce neviditelný - tvar, co neobsáhne vlastní text, se má
        // zahodit a blok přejít na heuristiku (záplata přes vlastní OCR oblast).
        val degenerate = listOf(
            BubbleShapePoint(0.56f, 0.483f, 0.485f),
            BubbleShapePoint(0.57f, 0.483f, 0.486f),
        )
        val block = TranslatedBlock(
            originalText = "SOMEONE", translatedText = "Někdo",
            leftF = 0.486f, topF = 0.563f, rightF = 0.626f, bottomF = 0.582f,
            shape = degenerate,
            bgUniform = false,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()

        assertTrue("degenerate shape must be dropped", positioned.block.shape == null)
        // Heuristický box musí krýt aspoň vlastní OCR rozsah textu, ne bod.
        assertTrue(positioned.rightF - positioned.leftF >= block.rightF - block.leftF - 1e-4f)
        assertTrue(positioned.maxBottomF - positioned.minTopF >= block.bottomF - block.topF - 1e-4f)
    }

    @Test
    fun `leaked shape much larger than text falls back to heuristic layout`() {
        // Audit RWS ch.215 str. 77: NARRATION blok na tmavé scéně - flood-fill unikl
        // z bubliny a obrys pokryl 0..1 × 0.21-0.99 stránky. Výplň oříznutá touto
        // konturou zakryla půlku obrázku a překlad se vysázel doprostřed kresby
        // místo do bubliny. Tvar obří + text u jeho kraje = zahodit.
        val leaked = listOf(
            BubbleShapePoint(0.2125f, 0.05f, 0.95f),
            BubbleShapePoint(0.40f, 0.0f, 1.0f),
            BubbleShapePoint(0.70f, 0.0f, 1.0f),
            BubbleShapePoint(0.99375f, 0.02f, 0.9f),
        )
        val block = TranslatedBlock(
            originalText = "BUT THEY CAN'T MOVE A LARGE AMOUNT OF PEOPLE TO A DIFFERENT DIMENSION LIKE I CAN",
            translatedText = "Ale nedokážou přesunout spoustu lidí do jiné dimenze tak jako já.",
            leftF = 0.1525f, topF = 0.2609375f, rightF = 0.835f, bottomF = 0.459375f,
            shape = leaked,
            bgUniform = false,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()

        assertTrue("leaked shape must be dropped", positioned.block.shape == null)
        // Heuristický box zůstane kolem vlastního OCR rozsahu - žádná půlka stránky.
        assertTrue(positioned.maxBottomF - positioned.minTopF < 0.4f)
    }

    @Test
    fun `shape whose bbox contains the text but whose rows do not is dropped`() {
        // Audit RWS ch.215 ("FALLING DRAGON STRIKE"): flood-fill se chytil bílé
        // diagonální víry v kresbě VEDLE nápisu - obalový obdélník tvaru je dost velký
        // na poměrové kontroly (0.55 / leak), ale řádky víru na výšce letteringového
        // textu ho fyzicky neobsahují. Výsledek na zařízení: vepsaný obdélník ->
        // mikroskopická čeština, clip na siluetu víry -> obří anglický nápis odkrytý.
        val swirl = listOf(
            BubbleShapePoint(yF = 0.52f, leftF = 0.40f, rightF = 0.52f),
            BubbleShapePoint(yF = 0.60f, leftF = 0.30f, rightF = 0.42f),
            BubbleShapePoint(yF = 0.70f, leftF = 0.18f, rightF = 0.30f),
            BubbleShapePoint(yF = 0.80f, leftF = 0.08f, rightF = 0.20f),
            BubbleShapePoint(yF = 0.90f, leftF = 0.00f, rightF = 0.12f),
        )
        val block = TranslatedBlock(
            originalText = "FALLING DRAGON STRIKE", translatedText = "Úder padajícího draka",
            leftF = 0.04f, topF = 0.65f, rightF = 0.55f, bottomF = 0.89f,
            shape = swirl,
            bgUniform = false,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()

        assertTrue("tvar, co text v řádkovém profilu neobsahuje, se má zahodit", positioned.block.shape == null)
    }

    @Test
    fun `a wide bubble outline whose rows contain the text is kept`() {
        // Kontrolní pól nové podmínky: skutečná bublina drží všechny řádky textu
        // uvnitř řádkového profilu obrysu - musí zůstat tvarová, ne spadnout na heuristiku.
        val bubble = (0..10).map { i ->
            val y = 0.55f + i * 0.035f
            val bulge = kotlin.math.sin(i / 10f * Math.PI).toFloat() * 0.05f
            BubbleShapePoint(yF = y, leftF = 0.10f - bulge, rightF = 0.80f + bulge)
        }
        val block = TranslatedBlock(
            originalText = "FALLING DRAGON STRIKE", translatedText = "Úder padajícího draka",
            leftF = 0.15f, topF = 0.62f, rightF = 0.72f, bottomF = 0.82f,
            shape = bubble,
            bgUniform = true,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()
        assertTrue(positioned.block.shape != null)
    }

    @Test
    fun `seamCover fragment keeps even a non-containing shape`() {
        // Řezový krycí fragment se nerenderuje jako text - jeho tvar řídí jen výplňový
        // clip a zahození by krytí zúžilo. Nová podmínka se na něj nevztahuje.
        // Tvar projde velikostní/leak kontrolami (bbox je dost velký), ale řádky sedí
        // VEDLE textu (fragment uříznutý řezem) - u běžného bloku by se zahodil.
        val offsetShape = listOf(
            BubbleShapePoint(yF = 0.48f, leftF = 0.10f, rightF = 0.55f),
            BubbleShapePoint(yF = 0.62f, leftF = 0.10f, rightF = 0.55f),
        )
        val block = TranslatedBlock(
            originalText = "YOU LIKE THE MANS YOU...?", translatedText = "x",
            leftF = 0.45f, topF = 0.50f, rightF = 0.85f, bottomF = 0.56f,
            shape = offsetShape,
            seamCover = true,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()
        assertTrue(positioned.block.shape != null)

        // Kontrolní pól: stejná geometrie na BĚŽNÉM bloku se má zahodit.
        val normal = layoutTranslationBlocks(listOf(block.copy(seamCover = false))).single()
        assertTrue(normal.block.shape == null)
    }

    @Test
    fun `tall shape with centered text is kept`() {
        // Obrácený případ: velká bublina (výkřik) - text sedí uprostřed obrysu, takže
        // i když je tvar násobně vyšší než text, není to leak a zahodit se nesmí.
        val big = listOf(
            BubbleShapePoint(0.10f, 0.30f, 0.70f),
            BubbleShapePoint(0.30f, 0.25f, 0.75f),
            BubbleShapePoint(0.50f, 0.25f, 0.75f),
            BubbleShapePoint(0.65f, 0.30f, 0.70f),
        )
        val block = TranslatedBlock(
            originalText = "AAAH!", translatedText = "ÁÁÁ!",
            leftF = 0.35f, topF = 0.36f, rightF = 0.60f, bottomF = 0.40f,
            shape = big,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()
        assertTrue(positioned.block.shape != null)
    }

    @Test
    fun `shape properly containing its text is kept`() {
        // Kontrola, že se zahazují jen rozpadlé tvary - reálný obrys bubliny (větší než
        // OCR box textu, jak má být) se používá dál jako dosud.
        val shape = listOf(
            BubbleShapePoint(0.50f, 0.44f, 0.68f),
            BubbleShapePoint(0.58f, 0.42f, 0.70f),
            BubbleShapePoint(0.62f, 0.44f, 0.68f),
        )
        val block = TranslatedBlock(
            originalText = "hi", translatedText = "ahoj",
            leftF = 0.50f, topF = 0.55f, rightF = 0.62f, bottomF = 0.57f,
            shape = shape,
        )
        val positioned = layoutTranslationBlocks(listOf(block)).single()
        assertTrue(positioned.block.shape != null)
        assertEquals(0.42f, positioned.leftF, 0.001f)
        assertEquals(0.70f, positioned.rightF, 0.001f)
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
    fun `uniform background block does not expand all the way to a distant neighbor`() {
        // Živý nález (audit): malá SFX bublina ("GULP GULP") osamocená ve velkém panelu bez
        // blízkého souseda - nejbližší dalsí text (titulek příští scény) je daleko dole. Box
        // dřív expandoval AŽ K NĚMU bez ohledu na vzdálenost = bílý box přes půl panelu.
        val ownHeight = 0.02f
        val small = block(0.30f, 0.10f, 0.40f, 0.10f + ownHeight, text = "GULP")
        val farBelow = block(0.20f, 0.70f, 0.80f, 0.72f, text = "far away caption")
        val positioned = layoutTranslationBlocks(listOf(small, farBelow))
        val smallPos = positioned.first { it.block === small }

        assertTrue(
            "vertical growth toward a distant neighbor must stay capped, not reach it (${smallPos.maxBottomF})",
            smallPos.maxBottomF < 0.60f,
        )
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

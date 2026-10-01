package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regrese z cíleného auditu (fáze 13): BubbleOverlayLayer počítá layout dvakrát -
 * jednou na blocích přemapovaných na ořezanou plochu (kreslení) a jednou na původních
 * (říznutí záplat z bitmapy). Pozice i-tého prvku v těchto dvou seznamech NENÍ stabilní
 * identita: `layoutTranslationBlocks` přerovnává výstup (bloky s obrysem jdou před
 * heuristické) a clamp souřadnic při ořezu může překlapnout verdikt
 * `isDegenerateShapeForText`/`mergeUntranslatedSiblingBlocks`. Klíčují-li se záplaty/
 * flip stav pozicí v `positioned`, přistanou po překlapnutí verdiktu na CIZÍ bublině -
 * záplata přes špatný kus obrázku.
 *
 * Oprava: [PositionedTranslationBlock.sourceIndex] = pozice ve vstupním `blocks`, která
 * je shodná v obou layotech, a call-site mapuje klíče fixů přes něj.
 */
class SourceIndexStabilityTest {

    private fun block(
        left: Float = 0.30f,
        top: Float = 0.40f,
        right: Float = 0.50f,
        bottom: Float = 0.46f,
        originalText: String = "TEXT",
        shape: List<BubbleShapePoint>? = null,
        bgUniform: Boolean = false,
        isSfx: Boolean = false,
        isUntranslated: Boolean = false,
        seamCover: Boolean = false,
    ) = TranslatedBlock(
        originalText = originalText,
        translatedText = originalText,
        leftF = left,
        topF = top,
        rightF = right,
        bottomF = bottom,
        shape = shape,
        bgUniform = bgUniform,
        isSfx = isSfx,
        isUntranslated = isUntranslated,
        seamCover = seamCover,
        lineCount = 2,
    )

    /** Kruhová bublina kolem výchozího text boxu [0.30,0.50] - totéž schéma jako TextPatchPlanTest.circleShape. */
    private fun circleShape(centerX: Float = 0.40f): List<BubbleShapePoint> = (0..10).map { i ->
        val yF = 0.34f + 0.18f * i / 10f
        val half = 0.16f * kotlin.math.sin(Math.PI * i / 10.0).toFloat().coerceAtLeast(0.02f)
        BubbleShapePoint(yF = yF, leftF = centerX - half, rightF = centerX + half)
    }

    @Test
    fun `sourceIndex points back at the input block after shape-first reordering`() {
        // Heuristický blok vlevo od obrys bubliny, ale obrys jde ve výstupu PRVNÍ -
        // pozice ve výstupu se proti vstupu prohodí a sourceIndex musí vrátit ten správný.
        val h = block(originalText = "HEUR", left = 0.55f, right = 0.75f)
        val s = block(originalText = "SHAPED", shape = circleShape())
        val blocks = listOf(h, s)
        val positioned = layoutTranslationBlocks(blocks)

        positioned.forEach { pos ->
            assertEquals(
                "sourceIndex musí ukazovat na ten samý vstupní blok",
                pos.block.originalText,
                blocks[pos.sourceIndex].originalText,
            )
        }
        // Pojistka proti "test nic netestuje": pořadí se skutečně přerovnalo.
        assertEquals("obrys bublina stojí ve výstupu první", "SHAPED", positioned.first().block.originalText)
        assertEquals(1, positioned[0].sourceIndex)
        assertEquals(0, positioned[1].sourceIndex)
    }

    @Test
    fun `a patch keyed by sourceIndex lands on its block when the second layout reorders positions`() {
        // Simulace crop situace z BubbleOverlayLayer: "originalPositioned" vidí obrys,
        // "positioned" (po clampu) už ho zahodil jako degenerate => oba bloky jsou
        // heuristic a pořadí výstupu se prohodí.
        val h = block(originalText = "HEUR", left = 0.55f, right = 0.75f)
        val shaped = block(originalText = "SHAPED", shape = circleShape())
        val unshaped = shaped.copy(shape = null)

        val originalPositioned = layoutTranslationBlocks(listOf(h, shaped))
        val positioned = layoutTranslationBlocks(listOf(h, unshaped))

        // Tentýž blok ("SHAPED") stojí v obou layotech na JINÉ pozici - přesně ta
        // situace, kvůli které by poziční klíč přilepil záplatu na cizí bublinu.
        val sOriginal = originalPositioned.single { it.block.originalText == "SHAPED" }
        val sCropped = positioned.single { it.block.originalText == "SHAPED" }
        assertNotEquals(
            "test předpokládá, že se pozice SHAPED mezi layouty posunula",
            originalPositioned.indexOf(sOriginal), positioned.indexOf(sCropped),
        )

        // Takto klíčuje fixy BubbleOverlayLayer po F13.
        val fixes = patchPlan(originalPositioned).mapKeys { (i, _) -> originalPositioned[i].sourceIndex }

        assertEquals(
            "záplata musí patřit geometrii SHAPED, ne tomu, co náhodou stojí na jeho indexu",
            renderBoxRect(sOriginal),
            fixes.getValue(sCropped.sourceIndex),
        )
    }

    @Test
    fun `a seamCover fragment is never absorbed into a sibling mask`() {
        // Poražený fragment přešité bubliny kryje jen vlastní OCR rozsah (coverOnly).
        // Bez guardu by ho merge absorboval do přeloženého souseda a jeho maska by se
        // natáhla přes šev - tentýž řez by se překryl dvakrát.
        val t = block(originalText = "TARGET")
        val u = block(
            originalText = "SEAM",
            top = 0.47f,
            bottom = 0.49f,
            isUntranslated = true,
            seamCover = true,
        )
        val out = mergeUntranslatedSiblingBlocks(listOf(t, u))

        assertEquals("maska souseda se přes seamCover fragment nesmí natáhnout", 0.46f, out[0].bottomF, 0f)
        assertEquals(u, out[1])
    }

    @Test
    fun `a plain untranslated fragment still gets absorbed - the guard only excludes seamCover`() {
        // Kontrolní pól: beze změny se musí chovat obyčejný nepřeložený útržek.
        val t = block(originalText = "TARGET")
        val u = block(originalText = "BIT", top = 0.47f, bottom = 0.49f, isUntranslated = true)
        val out = mergeUntranslatedSiblingBlocks(listOf(t, u))

        assertEquals("sourozenec pořád absorbuje nepřeložený útržek", 0.49f, out[0].bottomF, 0f)
    }
}

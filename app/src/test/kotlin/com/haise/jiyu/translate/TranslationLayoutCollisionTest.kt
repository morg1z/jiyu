package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kolize vykreslovacich boxu - shape-vs-shape pass, listovy (TOC) rezim a SFX
 * jako pevne prekazky (viz audit Vagabond ch1 TOC stranky a prekryv "JEDOVATY"
 * pres SFX "FWIE").
 */
class TranslationLayoutCollisionTest {

    private fun rectShape(leftF: Float, topF: Float, rightF: Float, bottomF: Float): List<BubbleShapePoint> {
        // Obdelnikovy obrys = jeden BubbleShapePoint na radek (y s rozsahem left/right).
        val rows = 8
        return (0..rows).map { i ->
            val y = topF + (bottomF - topF) * i / rows
            BubbleShapePoint(yF = y, leftF = leftF, rightF = rightF)
        }
    }

    private fun block(
        leftF: Float, topF: Float, rightF: Float, bottomF: Float,
        shape: List<BubbleShapePoint>? = null,
        isSfx: Boolean = false,
        bgUniform: Boolean = true,
        isUntranslated: Boolean = false,
        lineCount: Int = 1,
    ) = TranslatedBlock(
        originalText = "orig", translatedText = "PREKLAD",
        leftF = leftF, topF = topF, rightF = rightF, bottomF = bottomF,
        shape = shape, isSfx = isSfx, bgUniform = bgUniform,
        isUntranslated = isUntranslated, lineCount = lineCount,
    )

    private fun overlapsX(a: PositionedTranslationBlock, b: PositionedTranslationBlock) =
        minOf(a.rightF, b.rightF) - maxOf(a.leftF, b.leftF)

    private fun overlapsY(a: PositionedTranslationBlock, b: PositionedTranslationBlock) =
        minOf(a.maxBottomF, b.maxBottomF) - maxOf(a.minTopF, b.minTopF)

    private fun overlaps(a: PositionedTranslationBlock, b: PositionedTranslationBlock) =
        overlapsX(a, b) > 0f && overlapsY(a, b) > 0f

    @Test
    fun `two vertically overlapping shape bubbles split at the text gap midpoint`() {
        // Dve bubliny, jejichz obrysy zasahuji do sebe, ale texty maji mezeru 0.08-0.14.
        val a = block(0.3f, 0.02f, 0.6f, 0.08f, shape = rectShape(0.28f, 0.01f, 0.62f, 0.12f))
        val b = block(0.32f, 0.14f, 0.58f, 0.20f, shape = rectShape(0.30f, 0.10f, 0.60f, 0.22f))

        val (pa, pb) = layoutTranslationBlocks(listOf(a, b)).let { list -> list.first { it.block === a } to list.first { it.block === b } }

        assertFalse("shape boxy se nesmi prekryvat", overlaps(pa, pb))
        // Hranice v puli textove mezery (0.08+0.14)/2 = 0.11.
        assertEquals(0.11f, pa.maxBottomF, 0.001f)
        assertEquals(0.11f, pb.minTopF, 0.001f)
        // Oba texty musi zustat cele v boxu.
        assertTrue(pa.maxBottomF >= pa.block.bottomF)
        assertTrue(pb.minTopF <= pb.block.topF)
    }

    @Test
    fun `two horizontally overlapping shape bubbles split at the text gap midpoint`() {
        val a = block(0.10f, 0.30f, 0.40f, 0.40f, shape = rectShape(0.08f, 0.28f, 0.45f, 0.42f))
        val b = block(0.50f, 0.32f, 0.80f, 0.42f, shape = rectShape(0.42f, 0.30f, 0.85f, 0.44f))

        val (pa, pb) = layoutTranslationBlocks(listOf(a, b)).let { list -> list.first { it.block === a } to list.first { it.block === b } }

        assertFalse("shape boxy se nesmi prekryvat", overlaps(pa, pb))
        // Vertikalni split nemuze (texty se prekryvaji v y) -> horizontalni v 0.45.
        assertEquals(0.45f, pa.rightF, 0.001f)
        assertEquals(0.45f, pb.leftF, 0.001f)
    }

    @Test
    fun `shape bubbles whose text rects intersect keep their overlap`() {
        // Opravdu protinajici se texty: zmensit pod glyphy by zakrylo pismo - dvojice
        // se necha byt (radsi prekryv nez schovany original).
        val a = block(0.3f, 0.05f, 0.6f, 0.12f, shape = rectShape(0.28f, 0.04f, 0.62f, 0.15f))
        val b = block(0.32f, 0.10f, 0.58f, 0.18f, shape = rectShape(0.30f, 0.09f, 0.60f, 0.20f))

        val result = layoutTranslationBlocks(listOf(a, b))
        // Nesmi se "opravit" pod text: box zustava dost velky na oba glyph regiony.
        val pa = result.first { it.block === a }
        val pb = result.first { it.block === b }
        assertTrue(pa.maxBottomF >= a.bottomF)
        assertTrue(pb.minTopF <= b.topF)
    }

    @Test
    fun `heuristic box never overlaps a shape obstacle`() {
        val shapeBlock = block(0.55f, 0.30f, 0.75f, 0.40f, shape = rectShape(0.54f, 0.29f, 0.76f, 0.41f))
        val heuristicBlock = block(0.30f, 0.32f, 0.50f, 0.38f, bgUniform = true)

        val result = layoutTranslationBlocks(listOf(shapeBlock, heuristicBlock))
        val ps = result.first { it.block === shapeBlock }
        val ph = result.first { it.block === heuristicBlock }

        assertFalse("heuristicky box nesmi zasahnout do shape bubliny", overlaps(ps, ph))
        assertTrue(ph.rightF >= heuristicBlock.rightF - 0.001f)
    }

    @Test
    fun `sfx heuristic blocks hold their own rect and act only as obstacles`() {
        val sfx = block(0.40f, 0.50f, 0.60f, 0.58f, isSfx = true)
        val result = layoutTranslationBlocks(listOf(sfx))
        val pos = result.single()

        assertEquals(sfx.leftF, pos.leftF, 0.0001f)
        assertEquals(sfx.rightF, pos.rightF, 0.0001f)
        assertEquals(sfx.bottomF, pos.maxBottomF, 0.0001f)
        assertEquals(sfx.topF, pos.minTopF, 0.0001f)
    }

    @Test
    fun `dialogue box keeps clear of an sfx obstacle instead of covering it`() {
        // JEDOVATY/FWIE scenar: SFX nalepkovany blok tesne vedle dialogu - dialogova
        // expanze se musi zastavit na midpointu mezery, ne pres SFX.
        val dialogue = block(0.10f, 0.30f, 0.30f, 0.38f, bgUniform = true)
        val sfx = block(0.36f, 0.32f, 0.60f, 0.40f, isSfx = true)

        val result = layoutTranslationBlocks(listOf(dialogue, sfx))
        val pd = result.first { it.block === dialogue }
        val ps = result.first { it.block === sfx }

        assertFalse(overlaps(pd, ps))
        assertTrue(pd.rightF <= ps.leftF + 0.001f)
    }

    @Test
    fun `dense TOC-like page produces no overlapping boxes`() {
        // 10 radku stylu "Chapter N: title" - kazdy rect 0.08 vysoky, krok 0.07 (prekryv!).
        val rows = (0..9).map { i ->
            val top = 0.05f + i * 0.07f
            block(0.15f, top, 0.65f, top + 0.04f, bgUniform = true)
        }

        val result = layoutTranslationBlocks(rows)
        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                assertFalse(
                    "radky $i a $j se nesmi prekryvat",
                    overlaps(result[i], result[j]),
                )
            }
        }
    }

    @Test
    fun `isDenseListPage detects stacked rows but not scattered bubbles`() {
        val tocRows = (0..7).map { i ->
            val top = 0.05f + i * 0.09f
            block(0.15f, top, 0.65f, top + 0.05f)
        }
        assertTrue(isDenseListPage(tocRows))

        val scattered = listOf(
            block(0.10f, 0.05f, 0.40f, 0.10f),
            block(0.60f, 0.15f, 0.90f, 0.20f),
            block(0.05f, 0.30f, 0.30f, 0.36f),
            block(0.50f, 0.45f, 0.95f, 0.52f),
            block(0.15f, 0.60f, 0.45f, 0.66f),
            block(0.60f, 0.75f, 0.85f, 0.80f),
            block(0.20f, 0.85f, 0.50f, 0.90f),
        )
        assertFalse(isDenseListPage(scattered))
        assertFalse(isDenseListPage(tocRows.take(4)))
    }

    // ── mergeUntranslatedSiblingBlocks (audit Vagabondu: "MATA-HACHI'S BEEN" zustalo
    //    EN vedle prelozene "PTAM SE TE." v jedne bubline) ──

    @Test
    fun `untranslated sibling stacked inside one bubble widens the translated block`() {
        // Dve OCR skupiny jedne bubliny; dolni nedostala preklad (UNTRANSLATED marker).
        // Silny horizontalni prekryv + mezera ~1 radek -> sloucit masku.
        val translated = block(0.30f, 0.40f, 0.70f, 0.46f)
        val untranslated = block(0.32f, 0.47f, 0.68f, 0.52f, isUntranslated = true)

        val merged = mergeUntranslatedSiblingBlocks(listOf(translated, untranslated))

        assertEquals("seznam zustava stejne dlouhy (indexy klucuji zaplaty)", 2, merged.size)
        val t = merged[0]
        assertEquals(0.52f, t.bottomF, 0.001f)
        assertEquals(0.30f, t.leftF, 0.001f)
        assertTrue(merged[1].isUntranslated)
    }

    @Test
    fun `untranslated block centered inside sibling shape is absorbed into the shape`() {
        // Stred bloku uvnitr radkového profilu sourodcova tvaru -> union radku.
        val shape = rectShape(0.25f, 0.30f, 0.75f, 0.60f)
        val translated = block(0.35f, 0.35f, 0.65f, 0.42f, shape = shape)
        val untranslated = block(0.40f, 0.50f, 0.60f, 0.55f, isUntranslated = true)

        val merged = mergeUntranslatedSiblingBlocks(listOf(translated, untranslated))

        val t = merged[0]
        // Tvar musi pokryvat i rect absorbovaneho bloku (0.40-0.60 je uvnitr - ale
        // rect rozsiril block.bottomF na 0.55, coz je v ramci tvaru; dulezite je,
        // ze radky tvaru na y=0.50-0.55 uz pokryvaji [0.40, 0.60]).
        val rowAt = t.shape!!.minByOrNull { kotlin.math.abs(it.yF - 0.52f) }!!
        assertTrue(rowAt.leftF <= 0.40f && rowAt.rightF >= 0.60f)
        assertEquals(0.55f, t.bottomF, 0.001f)
    }

    @Test
    fun `untranslated block far from any sibling stays untouched`() {
        // Samotna neprelozena bublina (zadny prelozeny sourozenec blizko) - nic nemeni,
        // originál prosvita jak ma.
        val translated = block(0.10f, 0.10f, 0.30f, 0.16f)
        val untranslated = block(0.60f, 0.60f, 0.90f, 0.66f, isUntranslated = true)

        val merged = mergeUntranslatedSiblingBlocks(listOf(translated, untranslated))

        assertEquals(translated.bottomF, merged[0].bottomF)
        assertEquals(translated.leftF, merged[0].leftF)
    }

    @Test
    fun `two separate side-by-side bubbles never merge`() {
        // Dve bubliny vedle sebe (různí mluvčí) - bez horizontalniho prekryvu se merge
        // nesmi spustit, i kdyz jedna z nich nema preklad.
        val translated = block(0.05f, 0.30f, 0.45f, 0.36f)
        val untranslated = block(0.55f, 0.30f, 0.95f, 0.36f, isUntranslated = true)

        val merged = mergeUntranslatedSiblingBlocks(listOf(translated, untranslated))

        assertEquals(translated.rightF, merged[0].rightF)
        assertEquals(translated.bottomF, merged[0].bottomF)
    }
}

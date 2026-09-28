package com.haise.jiyu.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test detektoru hustých "seznamových" stránek ([isDenseTextPage]) -
 * audit Vagabondu: TOC stránka vyprodukovala ~10+ drobných OCR bloků bez bublin,
 * model na nich vyrobil gibberish a šedé patche překryly původní sazbu. Stránka
 * se má poznat a přeskočit, ne překládat.
 */
class PageDensityGuardTest {

    private fun bubble(
        text: String,
        leftF: Float,
        topF: Float,
        rightF: Float,
        bottomF: Float,
        isSfx: Boolean = false,
        shape: List<BubbleShapePoint>? = null,
    ) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = leftF, topF = topF, rightF = rightF, bottomF = bottomF, shape = shape),
        sizeTag = SizeTag.MEDIUM,
        bubbleType = if (isSfx) BubbleType.SFX else BubbleType.SPEECH,
        isSfx = isSfx,
        lineCount = 1,
    )

    @Test
    fun `a page-wide grid of many short list items is dense`() {
        // TOC: 12 krátkých položek posetých přes celou stránku, žádný nalezený tvar.
        val items = (0..11).map { i ->
            bubble(
                "Chapter ${i + 1}",
                leftF = 0.1f + (i % 3) * 0.3f,
                topF = 0.05f + (i / 3) * 0.2f,
                rightF = 0.35f + (i % 3) * 0.3f,
                bottomF = 0.07f + (i / 3) * 0.2f,
            )
        }
        assertTrue(isDenseTextPage(items))
    }

    @Test
    fun `a normal dialogue page is never dense`() {
        val blocks = listOf(
            bubble("WE'RE STILL ALIVE.", .1f, .1f, .5f, .2f),
            bubble("AND WE INTEND TO KEEP IT THAT WAY.", .1f, .3f, .6f, .4f),
            bubble("RIGHT.", .55f, .5f, .8f, .56f),
        )
        assertFalse(isDenseTextPage(blocks))
    }

    @Test
    fun `many blocks with long sentences are not a list`() {
        // 10 bloků, ale každý je dlouhá věta - medián délky přes práh -> není seznam
        // (a zároveň nejsou "drobné" - výška 6 % stránky).
        val items = (0..9).map { i ->
            bubble(
                "A FAIRLY LONG SENTENCE ABOUT THE WAR OF THE ROSES NUMBER $i.",
                leftF = 0.05f, topF = i * 0.09f, rightF = 0.9f, bottomF = i * 0.09f + 0.06f,
            )
        }
        assertFalse(isDenseTextPage(items))
    }

    @Test
    fun `shaped bubbles rule out a list page`() {
        // Mnoho krátkých bloků, ale třetina má nalezený obrys bubliny -> dialog, ne seznam.
        val shape = listOf(BubbleShapePoint(0.5f, 0.1f, 0.4f))
        val items = (0..11).map { i ->
            bubble(
                "HI $i",
                leftF = 0.1f + (i % 3) * 0.3f,
                topF = 0.05f + (i / 3) * 0.2f,
                rightF = 0.3f + (i % 3) * 0.3f,
                bottomF = 0.07f + (i / 3) * 0.2f,
                shape = if (i < 4) shape else null,
            )
        }
        assertFalse(isDenseTextPage(items))
    }

    @Test
    fun `a tight cluster of short text is not a page-wide list`() {
        // 10 krátkých bloků natěsno pohromadě (malé rozpětí) -> ne seznam pokrývající stránku.
        val items = (0..9).map { i ->
            bubble("NO $i", leftF = 0.4f, topF = 0.4f + i * 0.01f, rightF = 0.55f, bottomF = 0.41f + i * 0.01f)
        }
        assertFalse(isDenseTextPage(items))
    }

    @Test
    fun `sfx and letter-less blocks do not count toward density`() {
        // 4 obsahové krátké + 6 SFX - jen 4 obsahové, pod minimem -> false.
        val items = (0..3).map { i -> bubble("HI $i", .1f, i * 0.2f, .3f, i * 0.2f + 0.02f) } +
            (0..5).map { i -> bubble("BOOM", .5f, i * 0.15f, .6f, i * 0.15f + 0.02f, isSfx = true) }
        assertFalse(isDenseTextPage(items))
    }
}

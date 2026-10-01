package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy k tomu, KDE se počítá záplata pozadí (viz [patchPlan]).
 *
 * Nález, kvůli kterému to vzniklo: záplata se počítala z OCR boxu textu, ale vykreslovala se
 * přes celý box bubliny. Kreslí se přes `ContentScale.FillBounds`, takže se malý obrázek
 * roztáhl přes velkou plochu - a zbytky tahů, které inpainting nedočistil, se tím zvětšily
 * a posunuly. V bublině se pak uprostřed českého překladu vznášel rozmazaný cizí text.
 */
class TextPatchPlanTest {

    private fun block(
        left: Float = 0.30f,
        top: Float = 0.40f,
        right: Float = 0.50f,
        bottom: Float = 0.46f,
        shape: List<BubbleShapePoint>? = null,
        bgUniform: Boolean = false,
        isSfx: Boolean = false,
        isUntranslated: Boolean = false,
    ) = TranslatedBlock(
        originalText = "TEXT",
        translatedText = "TEXT",
        leftF = left,
        topF = top,
        rightF = right,
        bottomF = bottom,
        shape = shape,
        bgUniform = bgUniform,
        isSfx = isSfx,
        isUntranslated = isUntranslated,
        lineCount = 2,
    )

    /** Kruhová bublina - obrys je mnohem vyšší i širší než OCR box textu uvnitř. */
    private fun circleShape(centerX: Float = 0.40f): List<BubbleShapePoint> = (0..10).map { i ->
        val yF = 0.34f + 0.18f * i / 10f
        val half = 0.16f * kotlin.math.sin(Math.PI * i / 10.0).toFloat().coerceAtLeast(0.02f)
        BubbleShapePoint(yF = yF, leftF = centerX - half, rightF = centerX + half)
    }

    @Test
    fun `a bubble with an outline but a patterned interior still gets a patch`() {
        // JÁDRO NÁLEZU: dřív tady stálo, že bublina s obrysem záplatu nikdy nechce. Neplatí to
        // pro balónky s vzorovaným vnitřkem (jemná vlnitá textura). Flood-fill se o texturní
        // čáry zastaví, takže vyjde obrys menší než balónek, a jednolitá výplň přes něj zakryje
        // vzorek jen uprostřed - po okrajích prosvítá originál. Uživatel to hlásil jako bílou
        // nálepku nalepenou přes kresbu. Záplata vzorek zachová, protože zakrývá jen písmena.
        val positioned = layoutTranslationBlocks(listOf(block(shape = circleShape(), bgUniform = false)))

        assertEquals("bublina s vzorovaným vnitřkem chce záplatu", 1, patchPlan(positioned).size)
    }

    @Test
    fun `a bubble with an outline and a genuinely flat interior gets no patch`() {
        // Druhá strana téhož: u obyčejného bílého balónku je oříznutá výplň od originálu
        // k nerozeznání, takže záplata nemá co zlepšit a jen riskuje artefakty.
        val positioned = layoutTranslationBlocks(listOf(block(shape = circleShape(), bgUniform = true)))

        assertTrue("jednolitý balónek nesmí chtít záplatu", patchPlan(positioned).isEmpty())
    }

    @Test
    fun `lettering drawn directly onto artwork must get a patch - it is the only clean redraw`() {
        // Od pravidla `art_lettering` (viz BubbleOverlayLayer): text bez obrysu bubliny na
        // pestré kresbě se překresluje VÝHRADNĚ záplatou - smaže tahy původního písma,
        // zbytek malby zůstane, překlad se napíše barvou originálu. Bez záplaty by tam
        // ležela pevná výplň = placka (nahlášené "THE BATTLE OF SEKIGAHARA" na akvarelu),
        // proto render takový blok přeskočí - záplata je pro něj jediná cesta na obrazovku.
        val positioned = layoutTranslationBlocks(listOf(block(shape = null)))

        assertEquals(1, patchPlan(positioned).size)
    }

    @Test
    fun `the patch is computed over the box that will be painted, not over the OCR box`() {
        val b = block(shape = circleShape())
        val positioned = layoutTranslationBlocks(listOf(b))
        val pos = positioned.single()
        val rect = patchPlan(positioned).getValue(0)

        assertEquals(pos.leftF, rect.leftF, 0f)
        assertEquals(pos.minTopF, rect.topF, 0f)
        assertEquals(pos.rightF, rect.rightF, 0f)
        assertEquals(pos.maxBottomF, rect.bottomF, 0f)
    }

    @Test
    fun `the painted box really is bigger than the OCR box - otherwise this bug could not exist`() {
        // Pojistka proti "test, co nic netestuje": kdyby rozvržení box nezvětšovalo, sedělo by
        // i staré počítání z OCR boxu a předchozí test by prošel omylem.
        val b = block(shape = null)
        val pos = layoutTranslationBlocks(listOf(b)).single()

        assertTrue(
            "rozvržení musí box roztáhnout (${pos.leftF}..${pos.rightF} proti ${b.leftF}..${b.rightF})",
            pos.leftF < b.leftF && pos.rightF > b.rightF,
        )
        assertTrue(
            "rozvržení musí box protáhnout dolů (${pos.maxBottomF} proti ${b.bottomF})",
            pos.maxBottomF > b.bottomF,
        )
    }

    @Test
    fun `a uniform bubble without a detected outline gets a patch - flat fill would spill past the bubble edge`() {
        // Jádro hlášení "výplň jde přes kraj bubliny": bez obrysu se jednolité pozadí
        // kreslilo jako zaoblený obdélník přes heuristický box, který je širší než
        // skutečná bublina (obláček/vlnitý obrys) - bílá placka přečnívá do kresby.
        // Záplata kopíruje skutečné pixely stránky, takže její okraj je neviditelný
        // a obrys bubliny zůstane zachovaný.
        val positioned = layoutTranslationBlocks(listOf(block(shape = null, bgUniform = true)))
        assertEquals(1, patchPlan(positioned).size)
    }

    @Test
    fun `sound effects and untranslated blocks get no patch`() {
        val positioned = layoutTranslationBlocks(
            listOf(block(shape = null, isSfx = true), block(left = 0.6f, right = 0.8f, isUntranslated = true)),
        )
        assertTrue(patchPlan(positioned).isEmpty())
    }

    @Test
    fun `keys are positions in the positioned list, so equal blocks cannot swap patches`() {
        // Dřív se záplata dohledávala přes blocks.indexOf(block). Dva shodné bloky (stejný
        // text i souřadnice po zaokrouhlení) jsou si podle data class rovny, takže indexOf
        // vrátil pořád ten první a druhá bublina dostala cizí záplatu.
        val a = block(left = 0.10f, right = 0.30f, shape = circleShape(0.25f))
        val b = block(left = 0.60f, right = 0.80f, shape = circleShape(0.70f))
        val positioned = layoutTranslationBlocks(listOf(a, b))
        val plan = patchPlan(positioned)

        assertEquals(setOf(0, 1), plan.keys)
        positioned.forEachIndexed { index, pos ->
            assertEquals(
                "záplata $index musí patřit svému boxu",
                renderBoxRect(pos),
                plan.getValue(index),
            )
        }
    }

    @Test
    fun `the render box rect is exactly what the renderer draws`() {
        // renderBoxRect je jediný zdroj pravdy pro obě strany - viz TranslationOverlay.
        val pos = layoutTranslationBlocks(listOf(block(shape = circleShape()))).single()
        val rect = renderBoxRect(pos)

        assertEquals(pos.leftF, rect.leftF, 0f)
        assertEquals(pos.minTopF, rect.topF, 0f)
        assertEquals(pos.rightF, rect.rightF, 0f)
        assertEquals(pos.maxBottomF, rect.bottomF, 0f)
    }

    @Test
    fun `an empty page asks for nothing`() {
        assertTrue(patchPlan(emptyList()).isEmpty())
        assertNull(patchPlan(emptyList())[0])
        assertFalse(patchPlan(emptyList()).containsKey(0))
    }
}

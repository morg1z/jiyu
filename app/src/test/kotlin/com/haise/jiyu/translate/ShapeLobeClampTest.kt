package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy oříznutí obrysu bubliny, aby nesahal přes text sousední bubliny.
 *
 * Nahlášený případ (kaskádová replika ve dvou bublinách): obě bublinky se fyzicky překrývají,
 * takže tvoří JEDNU spojitou bílou plochu. Flood-fill hledající obrys spodní bubliny se přes
 * ten pas přelil nahoru a vrátil tvar pokrývající OBA laloky. Výplň se pak natáhla přes obojí
 * a protože se spodní bublina kreslí až po horní (řadí se shora dolů), přemalovala její text.
 *
 * Nejhorší důsledek: zmizel i text, který appka VŮBEC NEPŘELOŽILA. Ať už horní bublinu
 * nenašlo OCR, nebo ji vynechal model, originál skončil pod bílou plochou. Nechat tam
 * nepřeložený originál je vždycky lepší než ho vygumovat.
 */
class ShapeLobeClampTest {

    private fun box(topF: Float, bottomF: Float, leftF: Float = 0.10f, rightF: Float = 0.40f) =
        RawTextBlock(text = "x", leftF = leftF, topF = topF, rightF = rightF, bottomF = bottomF)

    /** Obrys jako svislý sloupec vzorků od [fromF] do [toF]. */
    private fun shape(fromF: Float, toF: Float, steps: Int = 12) =
        (0 until steps).map { i ->
            val t = fromF + (toF - fromF) * i / (steps - 1f)
            BubbleShapePoint(yF = t, leftF = 0.08f, rightF = 0.42f)
        }

    @Test
    fun `a shape leaking over the bubble above is cut at the midpoint between them`() {
        // JÁDRO NAHLÁŠENÉ CHYBY: spodní bublina (0.30-0.50) ma obrys sahajici az k 0.10,
        // tedy pres horni bublinu (0.10-0.20).
        val upper = box(topF = 0.10f, bottomF = 0.20f)
        val lower = box(topF = 0.30f, bottomF = 0.50f)

        val clamped = clampShapeToOwnLobe(shape(0.10f, 0.50f), own = lower, others = listOf(upper))

        val highest = clamped.minOf { it.yF }
        assertTrue("obrys nesmi sahat na horni bublinu, sahal na $highest", highest > upper.bottomF)
    }

    @Test
    fun `the shape keeps its own bubble fully covered`() {
        val upper = box(topF = 0.10f, bottomF = 0.20f)
        val lower = box(topF = 0.30f, bottomF = 0.50f)

        val clamped = clampShapeToOwnLobe(shape(0.10f, 0.50f), own = lower, others = listOf(upper))

        assertTrue("vlastni bublina musi zustat cela zakryta", clamped.minOf { it.yF } <= lower.topF)
        assertTrue(clamped.maxOf { it.yF } >= lower.bottomF)
    }

    @Test
    fun `a shape leaking downwards over the bubble below is cut too`() {
        val upper = box(topF = 0.10f, bottomF = 0.20f)
        val lower = box(topF = 0.30f, bottomF = 0.50f)

        val clamped = clampShapeToOwnLobe(shape(0.10f, 0.50f), own = upper, others = listOf(lower))

        val lowest = clamped.maxOf { it.yF }
        assertTrue("obrys nesmi sahat na spodni bublinu, sahal na $lowest", lowest < lower.topF)
    }

    @Test
    fun `a bubble standing alone keeps its shape untouched`() {
        val alone = box(topF = 0.30f, bottomF = 0.50f)
        val original = shape(0.28f, 0.52f)

        assertEquals(original, clampShapeToOwnLobe(original, own = alone, others = emptyList()))
    }

    @Test
    fun `a bubble side by side does not clip anything`() {
        // Vedle sebe, ne pod sebou - vodorovne se neprekryvaji, takze si obrysy neprekazi.
        val own = box(topF = 0.30f, bottomF = 0.50f, leftF = 0.05f, rightF = 0.30f)
        val other = box(topF = 0.30f, bottomF = 0.50f, leftF = 0.60f, rightF = 0.95f)
        val original = shape(0.28f, 0.52f)

        assertEquals(original, clampShapeToOwnLobe(original, own = own, others = listOf(other)))
    }

    @Test
    fun `a shape that never reaches the neighbour is left alone`() {
        val upper = box(topF = 0.02f, bottomF = 0.08f)
        val lower = box(topF = 0.30f, bottomF = 0.50f)
        val original = shape(0.28f, 0.52f)

        assertEquals(original, clampShapeToOwnLobe(original, own = lower, others = listOf(upper)))
    }

    @Test
    fun `clamping never returns an empty shape`() {
        // Degenerovany pripad - sousedi tesne nad i pod. Radsi ponechat puvodni tvar nez
        // vratit nic, protoze prazdny tvar by znamenal, ze se bublina vubec nezakryje.
        val own = box(topF = 0.30f, bottomF = 0.34f)
        val above = box(topF = 0.26f, bottomF = 0.30f)
        val below = box(topF = 0.34f, bottomF = 0.38f)

        val clamped = clampShapeToOwnLobe(shape(0.20f, 0.45f), own = own, others = listOf(above, below))
        assertTrue("tvar nesmi zmizet uplne", clamped.isNotEmpty())
    }

    // ── Laloky posunute do stran (druhe nahlaseni) ──────────────────────────────

    /**
     * Kaskadova bublina, jak vypada doopravdy: horni lalok posunuty VPRAVO, spodni VLEVO.
     * Prave to jim dava ten schodovity tvar - a prave proto se jejich OCR boxy vodorovne
     * prekryvaji jen malo.
     *
     * Prvni verze orezu vyzadovala prekryv boxu aspon ze ctvrtiny, takze tady neprosla a oba
     * bloky dostaly totozny tvar celeho balonu (zmereno na zarizeni). Rozhodovat musi to, jestli
     * tvar POKRYVA cizi text, ne jak na sebe boxy vodorovne dosednou.
     */
    private fun wideShape(fromF: Float, toF: Float, leftF: Float, rightF: Float, steps: Int = 12) =
        (0 until steps).map { i ->
            val t = fromF + (toF - fromF) * i / (steps - 1f)
            BubbleShapePoint(yF = t, leftF = leftF, rightF = rightF)
        }

    @Test
    fun `offset lobes are still clamped even though their boxes barely overlap`() {
        // Horni text vpravo, spodni vlevo - prekryv boxu jen zlomek sirky.
        val upper = box(topF = 0.148f, bottomF = 0.237f, leftF = 0.36f, rightF = 0.74f)
        val lower = box(topF = 0.399f, bottomF = 0.484f, leftF = 0.12f, rightF = 0.44f)
        // Tvar celeho balonu pokryva oba laloky.
        val whole = wideShape(0.102f, 0.637f, leftF = 0.08f, rightF = 0.82f)

        val clamped = clampShapeToOwnLobe(whole, own = lower, others = listOf(upper))

        val highest = clamped.minOf { it.yF }
        assertTrue(
            "tvar spodni bubliny nesmi sahat na horni text, sahal na $highest",
            highest > upper.bottomF,
        )
    }

    @Test
    fun `the upper lobe of an offset pair is clamped downwards as well`() {
        val upper = box(topF = 0.148f, bottomF = 0.237f, leftF = 0.36f, rightF = 0.74f)
        val lower = box(topF = 0.399f, bottomF = 0.484f, leftF = 0.12f, rightF = 0.44f)
        val whole = wideShape(0.102f, 0.637f, leftF = 0.08f, rightF = 0.82f)

        val clamped = clampShapeToOwnLobe(whole, own = upper, others = listOf(lower))

        val lowest = clamped.maxOf { it.yF }
        assertTrue("tvar horni bubliny nesmi sahat na spodni text, sahal na $lowest", lowest < lower.topF)
    }

    @Test
    fun `a bubble that the shape does not cover at all is ignored`() {
        // Soused lezi mimo obrys (jina bublina vedle) - nema co orezavat.
        val own = box(topF = 0.30f, bottomF = 0.50f, leftF = 0.10f, rightF = 0.40f)
        val faraway = box(topF = 0.05f, bottomF = 0.12f, leftF = 0.75f, rightF = 0.95f)
        val narrow = wideShape(0.28f, 0.52f, leftF = 0.08f, rightF = 0.42f)

        assertEquals(narrow, clampShapeToOwnLobe(narrow, own = own, others = listOf(faraway)))
    }

    // ── Laloky vedle sebe, ne nad/pod (treti nahlaseni) ─────────────────────────
    //
    // "Spojena" (peanut) bublina se dvema replikami VEDLE SEBE na stejne vysce, ne nad
    // sebou - viz uzivatelska zpetna vazba (dvouhrba bublina "WE NEED TO HURRY THE HARVEST!"
    // / "THE FOOD WON'T LAST MUCH LONGER..." se v prekladu objevila jen s jednou vetou,
    // uprostred cele spojene plochy). Puvodni clampShapeToOwnLobe resil jen soused nad/pod
    // (other.bottomF <= own.topF / other.topF >= own.bottomF) - pro souseda, ktery se svisle
    // PREKRYVA (vedle sebe), obe podminky selzou a smycka souseda proste preskoci beze zmeny
    // limitu, takze tvar zustane roztazeny pres oba laloky neopraveny.

    @Test
    fun `a shape leaking sideways over a bubble next to it is cut at the horizontal midpoint`() {
        val left = box(topF = 0.30f, bottomF = 0.40f, leftF = 0.10f, rightF = 0.35f)
        val right = box(topF = 0.30f, bottomF = 0.40f, leftF = 0.45f, rightF = 0.70f)
        val whole = wideShape(0.28f, 0.42f, leftF = 0.05f, rightF = 0.75f)

        val clamped = clampShapeToOwnLobe(whole, own = right, others = listOf(left))

        val (leftEdge, _) = shapeBoundsAtYF(clamped, 0.35f)
        assertTrue("tvar prave bubliny nesmi sahat na levy text, sahal na $leftEdge", leftEdge > left.rightF)
    }

    @Test
    fun `the other side of a sideways pair is clamped as well`() {
        val left = box(topF = 0.30f, bottomF = 0.40f, leftF = 0.10f, rightF = 0.35f)
        val right = box(topF = 0.30f, bottomF = 0.40f, leftF = 0.45f, rightF = 0.70f)
        val whole = wideShape(0.28f, 0.42f, leftF = 0.05f, rightF = 0.75f)

        val clamped = clampShapeToOwnLobe(whole, own = left, others = listOf(right))

        val (_, rightEdge) = shapeBoundsAtYF(clamped, 0.35f)
        assertTrue("tvar leve bubliny nesmi sahat na pravy text, sahal na $rightEdge", rightEdge < right.leftF)
    }

    @Test
    fun `a sideways clamp still keeps its own bubble fully covered`() {
        val left = box(topF = 0.30f, bottomF = 0.40f, leftF = 0.10f, rightF = 0.35f)
        val right = box(topF = 0.30f, bottomF = 0.40f, leftF = 0.45f, rightF = 0.70f)
        val whole = wideShape(0.28f, 0.42f, leftF = 0.05f, rightF = 0.75f)

        val clamped = clampShapeToOwnLobe(whole, own = right, others = listOf(left))

        val (leftEdge, rightEdge) = shapeBoundsAtYF(clamped, 0.35f)
        assertTrue("vlastni bublina musi zustat cela zakryta", leftEdge <= right.leftF)
        assertTrue(rightEdge >= right.rightF)
    }
}

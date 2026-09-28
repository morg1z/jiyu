package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy záplaty textu pod bublinou.
 *
 * Proč to vzniklo: když text leží přímo na kresbě (žádná bublina), appka dosud přes celý box
 * natáhla JEDNU navzorkovanou barvu. Na barevné nebo členité kresbě z toho vznikla placka -
 * odtud uživatelské stížnosti na "hnědou skvrnu přes titulní kresbu" a "černou skvrnu přes
 * obličej". Jedna barva prostě nemůže nahradit kus obrázku.
 *
 * Nově se zakryjí jen samotné tahy písmen a každý takový pixel se dopočítá z okolního pozadí,
 * takže kresba mezi písmeny přežije. Není to skutečný inpainting (ten potřebuje neuronový
 * model a server), ale proti jednolité placce je to úplně jiná úroveň - a je to zadarmo.
 */
class TextPatchTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(255, 255, 255)
    private val black = argb(0, 0, 0)
    private val red = argb(220, 40, 40)
    private val blue = argb(40, 60, 220)

    /** Zdroj z 2D pole - [0][0] je levý horní roh. */
    private fun sourceOf(rows: List<List<Int>>) = PixelSource { x, y ->
        rows.getOrNull(y)?.getOrNull(x) ?: white
    }

    private fun grid(w: Int, h: Int, fill: Int, marks: Map<Pair<Int, Int>, Int> = emptyMap()) =
        (0 until h).map { y -> (0 until w).map { x -> marks[x to y] ?: fill } }

    @Test
    fun `text strokes are replaced by the surrounding background`() {
        // Bílé pozadí s jedním černým "tahem" uprostřed.
        val rows = grid(9, 9, white, mapOf((4 to 4) to black))
        val patch = buildTextPatch(sourceOf(rows), 9, 9, 0, 0, 9, 9, bgArgb = white)

        assertEquals("černý pixel se má nahradit pozadím", white, patch[4 * 9 + 4])
    }

    @Test
    fun `the background itself is left untouched`() {
        val rows = grid(9, 9, white, mapOf((4 to 4) to black))
        val patch = buildTextPatch(sourceOf(rows), 9, 9, 0, 0, 9, 9, bgArgb = white)

        assertEquals(white, patch[0])
        assertEquals(white, patch[8 * 9 + 8])
    }

    @Test
    fun `a colour gradient in the art survives instead of being flattened`() {
        // JÁDRO NÁLEZU: levá půlka červená, pravá modrá, uprostřed černý text. Stará cesta
        // přebarvila celý box jednou "průměrnou" barvou - fialovou, která na obrázku vůbec
        // není. Záplata musí barvy po stranách nechat být.
        val rows = (0 until 9).map { y ->
            (0 until 9).map { x ->
                when {
                    x == 4 && y in 3..5 -> black
                    x < 4 -> red
                    else -> blue
                }
            }
        }
        val patch = buildTextPatch(sourceOf(rows), 9, 9, 0, 0, 9, 9, bgArgb = red)

        assertEquals("červená strana zůstává červená", red, patch[4 * 9 + 0])
        assertEquals("modrá strana zůstává modrá", blue, patch[4 * 9 + 8])
        assertNotEquals("zakrytý pixel nesmí zůstat černý", black, patch[4 * 9 + 4])
    }

    @Test
    fun `a patched pixel takes a colour that actually occurs nearby`() {
        val rows = (0 until 9).map { y ->
            (0 until 9).map { x -> if (x == 4 && y in 3..5) black else red }
        }
        val patch = buildTextPatch(sourceOf(rows), 9, 9, 0, 0, 9, 9, bgArgb = red)

        assertEquals("uprostřed červené plochy musí vzniknout červená", red, patch[4 * 9 + 4])
    }

    @Test
    fun `a box that is entirely text still produces something instead of failing`() {
        // Degenerovaný případ - nemá se z čeho dopočítat. Nesmí spadnout ani vrátit prázdno.
        val rows = grid(6, 6, black)
        val patch = buildTextPatch(sourceOf(rows), 6, 6, 0, 0, 6, 6, bgArgb = white)

        assertEquals(36, patch.size)
        assertTrue("všechny pixely musí být neprůhledné", patch.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `only the requested region is returned`() {
        val rows = grid(20, 20, white)
        val patch = buildTextPatch(sourceOf(rows), 20, 20, 5, 5, 12, 10, bgArgb = white)

        assertEquals("šířka 7 × výška 5", 7 * 5, patch.size)
    }

    @Test
    fun `a region reaching outside the image is clamped, not crashing`() {
        val rows = grid(10, 10, white)
        val patch = buildTextPatch(sourceOf(rows), 10, 10, -4, -4, 14, 14, bgArgb = white)

        assertEquals(10 * 10, patch.size)
    }

    @Test
    fun `an empty region yields an empty result`() {
        val rows = grid(10, 10, white)
        assertEquals(0, buildTextPatch(sourceOf(rows), 10, 10, 5, 5, 5, 5, bgArgb = white).size)
    }

    @Test
    fun `thick strokes are covered too, not just their edges`() {
        // Tah široký 3 px - kdyby se doplňovalo jen z přímých sousedů jednou, zůstal by
        // uprostřed neopravený proužek.
        val rows = (0 until 11).map {
            (0 until 11).map { x -> if (x in 4..6) black else white }
        }
        val patch = buildTextPatch(sourceOf(rows), 11, 11, 0, 0, 11, 11, bgArgb = white)

        (4..6).forEach { x ->
            assertEquals("sloupec $x se má celý zaplnit pozadím", white, patch[5 * 11 + x])
        }
    }

    // -- Textová oblast: záplata je větší než OCR box, prahovat se smí jen uvnitř ----------

    @Test
    fun `art outside the text region is copied through untouched`() {
        // Záplata pokrývá celý box, přes který se bublina kreslí, a ten je větší než OCR box
        // (viz patchPlan). Kresba mimo text nesmí projít prahováním ani dopočítáváním - jinak
        // by se rozmazala tam, kde žádné písmo nikdy nebylo.
        val rows = (0 until 15).map { y ->
            (0 until 15).map { x ->
                when {
                    x in 6..8 && y in 6..8 -> black // "písmeno" uvnitř textové oblasti
                    x == 1 && y in 2..12 -> black // detail kresby daleko od textu
                    else -> white
                }
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), 15, 15, 0, 0, 15, 15, bgArgb = white,
            textLeft = 5, textTop = 5, textRight = 10, textBottom = 10,
        )

        assertEquals("tah uvnitř textové oblasti se zakryje", white, patch[7 * 15 + 7])
        assertEquals("kresba mimo textovou oblast zůstává", black, patch[7 * 15 + 1])
    }

    @Test
    fun `without a text region the whole patch is thresholded as before`() {
        // Zpětná kompatibilita - volání bez textové oblasti se chová přesně jako dřív.
        val rows = (0 until 15).map { y ->
            (0 until 15).map { x -> if (x == 1 && y in 2..12) black else white }
        }
        val patch = buildTextPatch(sourceOf(rows), 15, 15, 0, 0, 15, 15, bgArgb = white)

        assertNotEquals("bez omezení se tah zakryje kdekoliv", black, patch[7 * 15 + 1])
    }

    @Test
    fun `the text region is read in image coordinates, not patch coordinates`() {
        // Záplata začíná na (4,4) obrázku, textová oblast je zadaná v souřadnicích OBRÁZKU -
        // kdyby se braly jako souřadnice záplaty, posunula by se o celý offset a maska by
        // dopadla na jiné písmeno.
        val rows = (0 until 20).map { y ->
            (0 until 20).map { x -> if (x in 12..13 && y in 12..13) black else white }
        }
        val patch = buildTextPatch(
            sourceOf(rows), 20, 20, 4, 4, 20, 20, bgArgb = white,
            textLeft = 11, textTop = 11, textRight = 15, textBottom = 15,
        )

        val w = 16
        assertEquals("tah na (12,12) obrázku leží v záplatě na (8,8)", white, patch[8 * w + 8])
    }

    // -- Tlusté komiksové písmo přes kresbu (nahlášené "...HAS ENDED.") -------------------

    /**
     * Kus caption pásu tak, jak vypadá na barevné stránce: nízký a široký, přes pestrou kresbu,
     * a písmo je BÍLÉ JÁDRO S TMAVÝM OBRYSEM - takhle se sází text ležící rovnou v obrázku,
     * aby byl čitelný přes cokoli pod sebou.
     */
    private fun captionOnArtwork(w: Int, h: Int, strokeWidth: Int): List<List<Int>> {
        val coreStart = w / 2 - strokeWidth / 2
        val coreEnd = coreStart + strokeWidth - 1
        return (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    x in coreStart..coreEnd -> white // jádro písmene
                    x == coreStart - 1 || x == coreEnd + 1 -> black // obrys kolem něj
                    // Pestrá kresba pod textem - žádná souvislá barva.
                    (x / 5 + y / 5) % 2 == 0 -> red
                    else -> blue
                }
            }
        }
    }

    /** Kolik pixelů jádra tahu zůstalo po záplatě pořád bílých, tedy nezakrytých. */
    private fun leftoverCorePixels(patch: IntArray, w: Int, h: Int, strokeWidth: Int): Int {
        val coreStart = w / 2 - strokeWidth / 2
        var leftover = 0
        for (y in 0 until h) {
            for (x in coreStart until coreStart + strokeWidth) {
                if (patch[y * w + x] == white) leftover++
            }
        }
        return leftover
    }

    @Test
    fun `a thick outlined caption stroke is covered, not left showing`() {
        // Ověřená DOMNĚNKA, která se nepotvrdila, a proto tu zůstává jako regresní test.
        //
        // Nabízelo se, že za nedočištěný caption "...HAS ENDED." může okno adaptivního
        // prahování: odvozuje se z rozměrů záplaty (min(w,h)/6) a caption pás je nízký, takže
        // u tlustého tahu by okno skončilo celé uvnitř písma - průměr okolí by vyšel jako barva
        // tahu a pixel by se za text neoznačil. Změřeno: nestává se to, protože označené okraje
        // tahu se ještě dilatují (MASK_DILATION), a to vnitřek devítipixelového tahu uzavře.
        val w = 240
        val h = 36
        val strokeWidth = 9
        val rows = captionOnArtwork(w, h, strokeWidth)
        val patch = buildTextPatch(sourceOf(rows), w, h, 0, 0, w, h, bgArgb = red)

        val leftover = leftoverCorePixels(patch, w, h, strokeWidth)
        assertEquals("z tahu zůstalo $leftover bílých pixelů - písmo prosvítá dál", 0, leftover)
    }

    @Test
    fun `a thin stroke in the same low strip still works`() {
        // Protipól: tenký tah ve stejně nízkém pásu fungoval i dřív a musí fungovat dál.
        val w = 240
        val h = 36
        val rows = captionOnArtwork(w, h, strokeWidth = 3)
        val patch = buildTextPatch(sourceOf(rows), w, h, 0, 0, w, h, bgArgb = red)

        assertEquals(0, leftoverCorePixels(patch, w, h, 3))
    }

    @Test
    fun `the artwork around a covered caption is not smeared away`() {
        // Pojistka proti přestřelení: větší okno nesmí začít označovat za text i samotnou
        // kresbu, jinak by se z opravy stala ta placka, kvůli které záplata vznikla.
        val w = 240
        val h = 36
        val rows = captionOnArtwork(w, h, strokeWidth = 9)
        val patch = buildTextPatch(sourceOf(rows), w, h, 0, 0, w, h, bgArgb = red)

        val farX = 20
        val farY = 18
        assertEquals("kresba mimo písmo se nesmí dopočítávat", rows[farY][farX], patch[farY * w + farX])
    }

    @Test
    fun `letters reaching a hair outside the OCR box are still covered`() {
        // JÁDRO NÁLEZU. Maska se ořezává přesně na OCR box, jenže ten box je jen aproximace -
        // ML Kit ho běžně vede o pár pixelů uvnitř skutečného otisku písma (naměřeno sondou na
        // zařízení: u repliky kreslené od x=180 vrátil levý okraj až na 190). Co přesáhne,
        // zůstane v obraze nedotčené: lem kolem písmen, spodek dotahů, tečky na hranici.
        //
        // Přesně tak vypadal nahlášený caption "...HAS ENDED." - po překladu pořád čitelný.
        val w = 60
        val h = 40
        // Svislý bílý tah přes celou výšku, na pestré kresbě.
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    x in 28..33 -> white
                    (x / 5 + y / 5) % 2 == 0 -> red
                    else -> blue
                }
            }
        }
        // OCR box tah TĚSNĚ míjí po obou stranách - hlásí 30..31 místo skutečných 28..33.
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = red,
            textLeft = 30, textTop = 0, textRight = 32, textBottom = h,
        )

        val leftover = (0 until h).sumOf { y ->
            (28..33).count { x -> patch[y * w + x] == white }
        }
        assertEquals("z tahu zůstalo $leftover bílých pixelů mimo hlášený OCR box", 0, leftover)
    }

    // -- Jádrová barva písma (meanTextArgbOut) ------------------------------------------
    // Překlad se nad záplatou kreslí barvou originálu, ne černou/bílou podle pozadí - jinak
    // by bílý caption na světlé kresbě vylezl černě a nevypadal jako součást malby.

    @Test
    fun `the reported text colour is the light core of an outlined caption, not its dark edge`() {
        // Bílé jádro s černým lemem na pestré kresbě: prostý průměr masky by spadl do šedé,
        // jádrová barva musí vyjít světlá.
        val w = 240
        val h = 36
        val rows = captionOnArtwork(w, h, strokeWidth = 9)
        val out = IntArray(1)
        buildTextPatch(sourceOf(rows), w, h, 0, 0, w, h, bgArgb = red, meanTextArgbOut = out)

        val lum = (0.299 * ((out[0] shr 16) and 0xFF) + 0.587 * ((out[0] shr 8) and 0xFF) + 0.114 * (out[0] and 0xFF))
        assertTrue("jádro bílého captionu má vyjít světlé, vyšlo ${lum.toInt()}", lum > 180.0)
    }

    @Test
    fun `the reported text colour of a dark title is dark`() {
        // Opačná polarita: černý nápis na světlé kresbě má vyjít tmavě, ne smíchaně.
        val w = 60
        val h = 40
        val rows = (0 until h).map { y ->
            (0 until w).map { x -> if (x in 28..33) black else argb(235, 230, 220) }
        }
        val out = IntArray(1)
        buildTextPatch(sourceOf(rows), w, h, 0, 0, w, h, bgArgb = white, meanTextArgbOut = out)

        val lum = (0.299 * ((out[0] shr 16) and 0xFF) + 0.587 * ((out[0] shr 8) and 0xFF) + 0.114 * (out[0] and 0xFF))
        assertTrue("jádro černého nápisu má vyjít tmavé, vyšlo ${lum.toInt()}", lum < 75.0)
    }

    @Test
    fun `no text pixels means no reported colour`() {
        // Jednolitá oblast bez písma - volající se podle nuly pozná, že barva není.
        val rows = grid(12, 12, argb(120, 140, 160))
        val out = IntArray(1)
        buildTextPatch(sourceOf(rows), 12, 12, 0, 0, 12, 12, bgArgb = white, meanTextArgbOut = out)

        assertEquals("bez textových pixelů se hlásí 0", 0, out[0])
    }

    @Test
    fun `the tolerance around the OCR box does not reach into unrelated artwork`() {
        // Pojistka: lem kolem textové oblasti se počítá z její VELIKOSTI, takže u malého boxu
        // zůstane malý. Kresba dál od textu se nesmí začít prahovat ani dopočítávat.
        val w = 200
        val h = 40
        val rows = (0 until h).map { y ->
            (0 until w).map { x -> if ((x / 5 + y / 5) % 2 == 0) red else blue }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = red,
            textLeft = 95, textTop = 15, textRight = 105, textBottom = 25,
        )

        listOf(10 to 5, 190 to 35, 10 to 35).forEach { (x, y) ->
            assertEquals("kresba na ($x,$y) je daleko od textu", rows[y][x], patch[y * w + x])
        }
    }
}

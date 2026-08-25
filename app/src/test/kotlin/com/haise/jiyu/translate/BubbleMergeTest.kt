package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test [mergeNearbyLines]/[hasWallBetween] (žádná Android/Bitmap závislost) -
 * reprodukuje uživatelskou zpětnou vazbu: bublina "HOW DID YOU MANAGE TO ATTACK HER..."
 * úplně zmizela (sloučila se s jinou, sousední bublinou) a stránka s reklamou na anime
 * dostala jednu přebujelou barevnou plochu přes tři původně samostatné captions.
 */
class BubbleMergeTest {

    private fun block(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        RawTextBlock(text = text, leftF = left, topF = top, rightF = right, bottomF = bottom)

    private class FakeCanvas(val width: Int, val height: Int, fill: Int) : PixelSource {
        val pixels = IntArray(width * height) { fill }
        override fun colorAt(x: Int, y: Int): Int = pixels[(y.coerceIn(0, height - 1)) * width + x.coerceIn(0, width - 1)]
        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top..bottom) for (x in left..right) pixels[y * width + x] = color
        }
    }

    // ── mergeNearbyLines (bez wall-check, výchozí = stará čistě geometrická logika) ──

    @Test
    fun `merges two vertically stacked lines within the same bubble`() {
        val a = block("Ahoj", 0.30f, 0.10f, 0.50f, 0.14f)
        val b = block("light", 0.30f, 0.145f, 0.50f, 0.185f)

        val merged = mergeNearbyLines(listOf(a, b))

        assertEquals(1, merged.size)
        assertEquals("Ahoj light", merged[0].text)
        assertEquals(2, merged[0].lineCount)
    }

    @Test
    fun `merges a bold emphasis word with the normal-size rest of the same bubble`() {
        // Reprodukuje uzivatelskou zpetnou vazbu (Vagabond kap. 2, str. 10): bublina
        // "WHY DON'T YOU ASK ME ANYTHING?" ma prvni slovo VYSAZENE VETSIM/TUCNYM pismem
        // (bezny komiksovy duraz), zbytek vety normalni velikosti o kus niz. Skutecne
        // souradnice z OCR (RawTextBlock) na tehle strance:
        //   "WH" (cast "WHY", tucne)  t=0.5400 b=0.5483 (vyska 0.0083)
        //   "YOU ASK ME ANY..."       t=0.5708 b=0.6000 (2 radky, prumerna vyska 0.01083)
        // Mezera mezi nimi (0.5708-0.5483=0.0225) je pres 2x prumerne vysky obou radku
        // (0.0096), takze puvodni prah (0.9x) merge odmitl - "WH" pak zustalo osamocene,
        // s bgUniform=false (lezi na okraji bubliny, ne v jejim rovnem vyplnenem stredu),
        // coz spustilo SFX heuristiku ([BubbleClassifier.detectSfx]) a bublina se ukazala
        // napůl anglicky, napůl cesky. hasWallBetween zustava skutecnou pojistkou proti
        // spojeni doopravdy ruznych bublin - tady jde jen o uvolneni geometrie.
        val a = block("WH", 0.7809f, 0.5400f, 0.8127f, 0.5483f)
        val b = block("YOU ASK ME ANY", 0.7576f, 0.5708f, 0.8433f, 0.6000f)

        val merged = mergeNearbyLines(listOf(a, b))

        assertEquals(1, merged.size)
        // Pomer vysek (0.01083/0.0083 =~ 1.30x) je pod STRUCTURED_FIELD_HEIGHT_RATIO - jde o
        // bezne zvyrazneni prvniho slova v JEDNE vete, ne oddelena pole - text musi zustat
        // spojeny mezerou, aby preklad slo prirozene preformatovat (viz merge-radku spojene
        // mezerou vs. \n nize).
        assertFalse(merged[0].text.contains('\n'))
    }

    @Test
    fun `merges structurally distinct fields with line breaks preserved instead of flattened into one sentence`() {
        // Reprodukuje uzivatelskou zpetnou vazbu (herni "system" stat-box, napr.
        // "God's Legion Support" / "Lucian" / "L-Rank Stellar-Commander"): tri radky ruzne
        // vysky (popisek/jmeno/podtitul), drive spojene MEZEROU do jedne prosaicke vety,
        // ktera pak pretekla pres box a ztratila vizualni hierarchii. Pomer nejvyssi/nejnizsi
        // vysky (0.05/0.02 = 2.5x) je jasne nad STRUCTURED_FIELD_HEIGHT_RATIO (na rozdil od
        // testu "bold emphasis" vys, kde byl pomer jen ~1.30x) - jde o odlisna pole, ne o
        // vetu s durazem.
        val label = block("God's Legion Support", 0.20f, 0.10f, 0.80f, 0.12f) // vyska 0.02
        val name = block("Lucian", 0.20f, 0.135f, 0.80f, 0.185f) // vyska 0.05
        val subtitle = block("L-Rank Stellar-Commander", 0.20f, 0.20f, 0.80f, 0.22f) // vyska 0.02

        val merged = mergeNearbyLines(listOf(label, name, subtitle))

        assertEquals(1, merged.size)
        assertEquals("God's Legion Support\nLucian\nL-Rank Stellar-Commander", merged[0].text)
    }

    @Test
    fun `does not merge lines with a large gap`() {
        val a = block("Ahoj", 0.30f, 0.10f, 0.50f, 0.14f)
        val b = block("Nazdar", 0.30f, 0.50f, 0.50f, 0.54f)

        val merged = mergeNearbyLines(listOf(a, b))

        assertEquals(2, merged.size)
    }

    @Test
    fun `merged block carries the average height of the ORIGINAL individual lines, not the whole bubble`() {
        // Kazda vstupni radka je jeste jeden samostatny radek z ML Kit (pred slouceni) - oba
        // maji vysku 0.04 (topF..bottomF). Sloucena bublina je vysoka 0.085 (0.10 az 0.185),
        // ale nativni velikost pisma se ma odvodit od JEDNOHO radku (0.04), ne od cele bubliny.
        val a = block("Ahoj", 0.30f, 0.10f, 0.50f, 0.14f)
        val b = block("light", 0.30f, 0.145f, 0.50f, 0.185f)

        val merged = mergeNearbyLines(listOf(a, b))

        assertEquals(1, merged.size)
        assertEquals(0.04f, merged[0].nativeLineHeightF, 0.001f)
    }

    @Test
    fun `a single unmerged line keeps its own height as the native line height`() {
        val a = block("Ahoj", 0.30f, 0.10f, 0.50f, 0.16f)

        val merged = mergeNearbyLines(listOf(a))

        assertEquals(0.06f, merged[0].nativeLineHeightF, 0.001f)
    }

    @Test
    fun `native line height is averaged across lines of differing height`() {
        val a = block("Ahoj", 0.30f, 0.10f, 0.50f, 0.13f) // vyska 0.03
        val b = block("light", 0.30f, 0.135f, 0.50f, 0.185f) // vyska 0.05

        val merged = mergeNearbyLines(listOf(a, b))

        assertEquals(1, merged.size)
        assertEquals(0.04f, merged[0].nativeLineHeightF, 0.001f) // prumer (0.03+0.05)/2
    }

    @Test
    fun `wall veto blocks a merge that geometry alone would allow`() {
        val a = block("Ahoj", 0.30f, 0.10f, 0.50f, 0.14f)
        val b = block("Nazdar", 0.30f, 0.145f, 0.50f, 0.185f)
        // Bez veta by se sloučily (stejné jako "merges two vertically stacked lines" výše) -
        // noWallBetween teď vrátí false, jako by tam skutečně byla vizuální hranice.
        val merged = mergeNearbyLines(listOf(a, b), noWallBetween = { _, _ -> false })

        assertEquals(2, merged.size)
    }

    // ── hasWallBetween (skutečná pixelová detekce hranice) ──

    @Test
    fun `no wall between two lines inside the same uniform bubble`() {
        val canvas = FakeCanvas(200, 200, 0xFF000000.toInt())
        canvas.fillRect(20, 20, 180, 100, 0xFFFFFFFF.toInt()) // jedna bílá bublina

        val a = block("Ahoj", 0.15f, 0.15f, 0.60f, 0.30f)
        val b = block("light", 0.15f, 0.32f, 0.60f, 0.47f)

        assertFalse(hasWallBetween(canvas, 200, 200, a, b))
    }

    @Test
    fun `wall detected between two separate bubbles with art in between`() {
        // Kreslené bubliny sahají o kousek DÁL než OCR box (reálná bublina je vždycky
        // o něco větší než text uvnitř) - proto mají navíc 6px odsazení oproti bloku a/b,
        // aby ringSeeds (margin 4px) sáhl pořád na bílou výplň, ne mimo ni.
        val canvas = FakeCanvas(400, 400, 0xFF000000.toInt()) // černá kresba/pozadí mezi bublinami
        canvas.fillRect(14, 14, 186, 86, 0xFFFFFFFF.toInt())    // bublina A (bílá)
        canvas.fillRect(14, 194, 186, 266, 0xFFFFFFFF.toInt())  // bublina B (bílá), stejná barva jako A!

        val a = block("HOW DID YOU MANAGE", 20f / 400, 20f / 400, 180f / 400, 80f / 400)
        val b = block("THIS IS MAKIMA", 20f / 400, 200f / 400, 180f / 400, 260f / 400)

        // I když obě bubliny mají STEJNOU barvu výplně, mezi nimi je pruh černé kresby -
        // vzorkované body na úsečce střed-střed ho musí zachytit.
        assertTrue(hasWallBetween(canvas, 400, 400, a, b))
    }

    @Test
    fun `wall detected between two differently colored caption boxes`() {
        val canvas = FakeCanvas(300, 300, 0xFF808080.toInt()) // šedá ilustrace na pozadí
        canvas.fillRect(20, 20, 280, 90, 0xFF2E7D32.toInt())   // zelený box ("MAPPA")
        canvas.fillRect(20, 150, 280, 220, 0xFFAD1457.toInt()) // růžový box (jiná caption)

        val a = block("FROM THE MAKERS OF JUJUTSU KAISEN", 0.10f, 0.08f, 0.90f, 0.28f)
        val b = block("A MESSAGE FROM THE STUDIO", 0.10f, 0.55f, 0.90f, 0.68f)

        assertTrue(hasWallBetween(canvas, 300, 300, a, b))
    }

    @Test
    fun `no wall reported when blocks are adjacent inside one continuous caption box`() {
        val canvas = FakeCanvas(300, 300, 0xFF2E7D32.toInt()) // celý box jedna barva
        val a = block("MAPPA", 0.10f, 0.10f, 0.90f, 0.30f)
        val b = block("A MESSAGE FROM THE STUDIO", 0.10f, 0.32f, 0.90f, 0.50f)

        assertFalse(hasWallBetween(canvas, 300, 300, a, b))
    }

    @Test
    fun `no wall reported inside a pinched two-hump speech bubble whose lines are horizontally staggered`() {
        // Reprodukuje uzivatelskou zpetnou vazbu: bublina "IF I'D KNOWN THE ROAD WOULD BE
        // LIKE THIS, I WOULDN'T HAVE MADE THE CONTRACT IN THE FIRST PLACE." se v prekladu
        // objevila jen jako "THE FIRST PLACE." - druhy radek/odstavec byl vykreslen posunuty
        // (kaskadova/"dvouhrba" bublina, bezna u rucne sazenych komiksovych bublin), takze
        // stred-stred usecka mezi oběma OCR radky prochazi SIKMO a u uzkeho hrdla mezi
        // vydutěmi minula bilou vypln - narazila na cerne pozadi vedle ni, coz stary kod
        // vyhodnotil jako "zed" a radky rozdelil na dve samostatne bubliny.
        // Kreslena bublina sahá dal nez OCR box (viz komentar u "wall detected between two
        // separate bubbles" vys - stejny duvod: ringSeeds margin 4px musi sahnout na bilou
        // vypln, ne mimo ni), proto maji vyduti 6px odsazeni oproti bloku a/b.
        val canvas = FakeCanvas(240, 240, 0xFF000000.toInt())
        canvas.fillRect(14, 14, 146, 96, 0xFFFFFFFF.toInt())    // horni vyduť (1. radek)
        canvas.fillRect(115, 90, 125, 130, 0xFFFFFFFF.toInt())  // uzke hrdlo (jen 10 px sirokce)
        canvas.fillRect(94, 124, 226, 206, 0xFFFFFFFF.toInt())  // dolni vyduť (2. radek), posunuta doprava

        val a = block("IF I'D KNOWN THE ROAD WOULD BE LIKE THIS,", 20f / 240, 20f / 240, 140f / 240, 90f / 240)
        val b = block("I WOULDN'T HAVE MADE THE CONTRACT IN THE FIRST PLACE.", 100f / 240, 130f / 240, 220f / 240, 200f / 240)

        assertFalse(hasWallBetween(canvas, 240, 240, a, b))
    }

    @Test
    fun `no wall reported when a thin tiled watermark stamp crosses only part of the gap`() {
        // Reprodukuje uzivatelskou zpetnou vazbu (Half Blood kap. 1, "Baxter/merchants"
        // bublina): "VORTEXSCANS.COM" vodoznak lezi FYZICKY mezi dvema pulkami textu jedne
        // bubliny. Puvodni kod bere jako "zed" UZ jeden jediny bod z 5 na usecce, ktery
        // nesedi na barvu ani jednoho bloku - tenky/diagonalni vodoznak snadno protne
        // jen 1 z 5 vzorku, i kdyz zbytek mezery je poradna spojita bila vypln stejne
        // bubliny. Oprava: az VETSINA vzorku musi ukazovat na cizi barvu.
        val canvas = FakeCanvas(400, 400, 0xFF000000.toInt())
        canvas.fillRect(20, 20, 380, 380, 0xFFFFFFFF.toInt()) // jedna velka bila bublina
        // Tenky vodorovny pruh vodoznaku presne v miste t=0.5 vzorku (y=160), zbylych
        // 4 vzorku (y=144,152,168,176) zustava cistou bilou vyplni bubliny.
        canvas.fillRect(190, 158, 210, 162, 0xFF444444.toInt())

        val a = block("IF THOSE DAMN MERCHANTS AREN'T LYING,", 0.10f, 0.10f, 0.90f, 0.30f)
        val b = block("DOESN'T THAT MEAN SOME EXTERNAL FACTOR COLLECTIVELY ALTERED THE TERRAIN?", 0.10f, 0.50f, 0.90f, 0.70f)

        assertFalse(hasWallBetween(canvas, 400, 400, a, b))
    }
}

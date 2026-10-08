package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Reprodukce nahlášených artefaktů v překladové záplatě (TextPatch):
 *
 * - "barcode" pruhy: adaptivní prahování označí raster/stínování za text a difuzní
 *   výplň ho přemaluje - vzniknou pruhy/střídající se sloupce tam, kde byl vzorek.
 * - koncentrické obdélníkové prstence: iterativní průměrování zvenčí dovnitř
 *   doplňuje pixel po prstech a každý prst zprůměruje předchozí = viditelné kruhové
 *   pásy na větší masce.
 *
 * Tyto testy nejdřív SELHOU (dokumentují bug), pak projdou po opravě.
 */
class TextPatchArtifactTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(255, 255, 255)
    private val black = argb(0, 0, 0)

    private fun sourceOf(rows: List<List<Int>>) = PixelSource { x, y ->
        rows.getOrNull(y)?.getOrNull(x) ?: white
    }

    private fun luminance(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    // -- 1) Screentone -> barcode ------------------------------------------------------

    /**
     * Halftone raster (3x3 tmavé tečky v rastru 6 px na bílém) + černý "glyph" uprostřed
     * textové oblasti. Po záplatě musí tečky MIMO glyph přežít - jsou to kresba, ne text.
     */
    @Test
    fun `screentone dots inside the text region survive the patch`() {
        val w = 120
        val h = 60
        // Textová oblast (OCR box) kolem glyphu.
        val tL = 45; val tT = 15; val tR = 76; val tB = 50
        // Glyph: plný černý blok (písmeno) 54..65 x 22..42.
        fun isGlyph(x: Int, y: Int) = x in 54..65 && y in 22..42
        // Tečka rastru: 3x3 px každých 6 px.
        fun isDot(x: Int, y: Int) = x % 6 < 3 && y % 6 < 3
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    isGlyph(x, y) -> black
                    isDot(x, y) -> black
                    else -> white
                }
            }
        }

        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = white,
            textLeft = tL, textTop = tT, textRight = tR, textBottom = tB,
        )

        // Tečka rastru UVNITŘ textové oblasti, MIMO glyph: (48,18) -> 48%6=0,18%6=0 -> tečka.
        val dot = patch[18 * w + 48]
        assertEquals(
            "rastrový bod uvnitř textové oblasti musí přežít (jinak vzniká barcode placka)",
            black, dot,
        )
        // Glyph uprostřed se má zakrýt a doplnit barvou, co v okolí skutečně existuje
        // (bílá mezera nebo tečka rastru) - tedy žádná vymyšlená šedá.
        val glyph = patch[30 * w + 60]
        assertTrue(
            "glyph má kopírovat nejbližší zdrojovou barvu (černou/bílou), ne směs",
            glyph == black || glyph == white,
        )
    }

    /**
     * Šrafování (svislé 2px čáry každých 6 px) - to je ta "barcode" situace ze screenshotu.
     * Čáry uvnitř textové oblasti nesmí zmizet do šedé směsi.
     */
    @Test
    fun `hatch lines inside the text region survive the patch`() {
        val w = 140
        val h = 50
        val tL = 30; val tT = 10; val tR = 110; val tB = 44
        fun isGlyph(x: Int, y: Int) = x in 60..80 && y in 20..34
        fun isHatch(x: Int) = x % 6 < 2
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    isGlyph(x, y) -> black
                    isHatch(x) -> black
                    else -> white
                }
            }
        }

        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = white,
            textLeft = tL, textTop = tT, textRight = tR, textBottom = tB,
        )

        // Šrafová čára x=36 (36%6=0) uvnitř textové oblasti, mimo glyph.
        val hatch = patch[30 * w + 36]
        assertEquals(
            "šrafová čára uvnitř textové oblasti musí přežít",
            black, hatch,
        )
    }

    // -- 2) Velká maska na nepůsobivém pozadí -> prstence -------------------------------

    /**
     * Černý podklad + bílá kostková hrana nahoře + velký bílý glyph hluboko pod ní.
     * Ring-averaging produkuje uprostřed masky šedé prstence (každé kolo průměruje
     * předchozí). Nearest-source výplň musí dát čistou černou.
     */
    @Test
    fun `fill on black background stays clean black without ring banding`() {
        val w = 100
        val h = 100
        val tL = 10; val tT = 20; val tR = 90; val tB = 90
        // Glyph: velký bílý blok 30..70 x 40..80.
        fun isGlyph(x: Int, y: Int) = x in 30..70 && y in 40..80
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    y < 12 -> white // jasná hrana nahoře (kousek jiné oblasti)
                    isGlyph(x, y) -> white // bílé písmo na černém
                    else -> black
                }
            }
        }

        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = black,
            textLeft = tL, textTop = tT, textRight = tR, textBottom = tB,
        )

        // Střed glyphu (50,60): nejbližší nemaskovaný pixel je černá kresba -> černá.
        // Ring averaging by sem po ~20 kolech natáhl světlejší průměr (šedá), což jsou
        // právě ty prstence ze screenshotu.
        val center = luminance(patch[60 * w + 50])
        assertTrue(
            "střed vyplněného glyphu má být temný (černá oblast), byl $center",
            center < 40,
        )
        // Všechny dříve-glyphové pixely mají být jednolitě temné - žádné pásy.
        var maxLum = 0
        for (y in 42..78 step 6) for (x in 32..68 step 6) {
            maxLum = maxOf(maxLum, luminance(patch[y * w + x]))
        }
        assertTrue("vnitřek masky má zůstat jednotně temný, maxLum=$maxLum", maxLum < 60)
    }

    // -- 3) Barva palety: výplň nesmí vymýšlet nové barvy ------------------------------

    /**
     * Na ostré hraně dvou barev má výplň kopírovat barvu nejbližšího zdroje - nesmí
     * vznikat průměrová šedá, která v obrázku nikde neexistuje (barva "mimo paletu").
     */
    @Test
    fun `filled pixels reuse a real nearby colour, not an invented average`() {
        val w = 60
        val h = 60
        // Levá půlka černá, pravá bílá; černý glyph přes hranici.
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    x in 26..34 && y in 24..36 -> black // glyph přes hranu
                    x < 30 -> black
                    else -> white
                }
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = white,
            textLeft = 26, textTop = 24, textRight = 35, textBottom = 36,
        )
        // Nearest-source výplň kopíruje skutečné barvy - v celé záplatě nesmí být
        // ani jediný pixel "vymyšlené" mezishody (šedá na černobílém obrázku).
        for (y in 0 until h) for (x in 0 until w) {
            val lum = luminance(patch[y * w + x])
            assertTrue(
                "pixel ($x,$y) má být reálná barva ze zdroje (černá/bílá), byl lum=$lum",
                lum < 60 || lum > 195,
            )
        }
    }

    // -- 4) Regrese: jednoduchý glyph na bílém pořád mizí čistě -------------------------

    @Test
    fun `plain glyph on white still disappears cleanly`() {
        val rows = (0 until 20).map { y ->
            (0 until 20).map { x -> if (x in 8..12 && y in 6..14) black else white }
        }
        val patch = buildTextPatch(
            sourceOf(rows), 20, 20, 0, 0, 20, 20, bgArgb = white,
            textLeft = 7, textTop = 5, textRight = 14, textBottom = 15,
        )
        for (y in 7..13) for (x in 9..11) {
            assertEquals("glyph na bílém se má stát bílým", white, patch[y * 20 + x])
        }
    }

    // -- 4b) Obrightený nápis přes kresbu - klasický caption styl ---------------------

    /**
     * Bílé písmo s černým obrysem přes barevnou kresbu (dvě barvy). Maska chytne oba
     * póly (jádro i lem) a výplň má pokračovat kresbou - žádná placka, žádný zbytek
     * textu, žádná vymyšlená barva.
     */
    @Test
    fun `outlined caption over two-colour art fills with surrounding art colours`() {
        val w = 120
        val h = 80
        val darkRed = 0xFF7A1F1F.toInt()
        val darkBlue = 0xFF1F2F6E.toInt()
        // Glyph s obrysem: vnitřek bílý, rámeček černý.
        fun glyphPixel(x: Int, y: Int): Int? = when {
            x in 40..80 && y in 25..55 -> if (x in 43..77 && y in 28..52) white else black
            else -> null
        }
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                glyphPixel(x, y) ?: if (x < 60) darkRed else darkBlue
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = darkRed,
            textLeft = 38, textTop = 23, textRight = 82, textBottom = 57,
        )

        // Střed glyphu (60,40) - byl bílý - má teď nést barvu kresby z okolí.
        val center = patch[40 * w + 60]
        val centerLum = luminance(center)
        assertTrue(
            "střed captionu má kopírovat kresbu (tmavý tón), byl lum=$centerLum",
            centerLum in 20..140,
        )
        // Obrys glyphu na modré straně (78,40): nejbližší zdroj je modrá kresba.
        val blueSide = patch[40 * w + 79]
        val lumB = luminance(blueSide)
        assertTrue("obrys na modré straně má být tmavý (kresba), byl $lumB", lumB < 140)
        // Žádný pixel uvnitř bývalého glyphu nesmí zůstat ani bílý ani černý glyph.
        for (y in 30..50 step 4) for (x in 46..74 step 4) {
            val p = patch[y * w + x]
            assertTrue(
                "uvnitř captionu nesmí zůstat inkoust ($x,$y = ${Integer.toHexString(p)})",
                p != white && p != black,
            )
        }
    }

    // -- 5b) Glyph oříznutý hranou OCR boxu (ink-flood) ---------------------------------

    /**
     * OCR box sekl tlustý tah doprostřed: část glyphu leží ZA pravým okrajem textové
     * oblasti. Seal díru nepozná (interiér je k okraji otevřený), maska za hranicí
     * regionu neexistuje -> pahýl by přežil. Ink-flood má inkoust doleptat i venku.
     */
    @Test
    fun `glyph clipped by the OCR box edge is removed beyond the box too`() {
        val w = 80
        val h = 40
        val gray = argb(180, 180, 180)
        val ink = argb(20, 20, 20)
        // Tlustý tah x=30..60 (plná výška písma), OCR box končí na x=45.
        val rows = (0 until h).map { y ->
            (0 until w).map { x -> if (x in 30..60 && y in 10..30) ink else gray }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = gray,
            textLeft = 10, textTop = 6, textRight = 45, textBottom = 34,
        )
        // Pahýl za hranou regionu (x=50..60) má být dopočtený do pozadí.
        for (x in 52..58 step 2) {
            val lum = luminance(patch[20 * w + x])
            assertTrue(
                "oříznutý pahýl tahu na x=$x má kopírovat pozadí, byl lum=$lum",
                lum > 120,
            )
        }
        // A otevřený interiér uvnitř boxu taky (x=33..44 byl uvnitř tahu).
        val inner = luminance(patch[20 * w + 37])
        assertTrue("interiér oříznutého tahu má být dopočtený, byl $inner", inner > 120)
    }

    /**
     * Světlý nápis oříznutý hranou boxu na tmavém pozadí - opačný pól inkoustu
     * (bílý caption na černé kresbě).
     */
    @Test
    fun `light glyph clipped by the OCR box edge is removed on dark background`() {
        val w = 80
        val h = 40
        val dark = argb(15, 15, 15)
        val ink = argb(245, 245, 245)
        val rows = (0 until h).map { y ->
            (0 until w).map { x -> if (x in 20..55 && y in 12..28) ink else dark }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = dark,
            textLeft = 10, textTop = 8, textRight = 40, textBottom = 32,
        )
        // Pahýl za pravou hranou regionu (x=46..55) - původně bílý inkoust.
        for (x in 48..54 step 2) {
            val lum = luminance(patch[20 * w + x])
            assertTrue(
                "oříznutý světlý pahýl na x=$x má ztmavnout na pozadí, byl lum=$lum",
                lum < 100,
            )
        }
    }

    /**
     * Negativa: samostatný tmavý tah ZA hranou regionu, který s glyphu nespojuje,
     * je kresba a má přežít - ink-flood seeduje jen z masky dotýkající se hrany.
     */
    @Test
    fun `a separate dark art stroke beyond the OCR box survives the ink flood`() {
        val w = 90
        val h = 40
        val gray = argb(180, 180, 180)
        val ink = argb(20, 20, 20)
        // Glyph uvnitř boxu x=30..40 + NESPOJITÝ černý pás x=62..70 (obrys panelu atd.).
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    x in 30..40 && y in 10..30 -> ink
                    x in 62..70 -> ink
                    else -> gray
                }
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = gray,
            textLeft = 10, textTop = 6, textRight = 45, textBottom = 34,
        )
        // Pás kresby za boxem nesmí být zaplaven ani přemalován - flood nemá k němu cestu.
        assertEquals("nespojený tah kresby má přežít netknutý", ink, patch[20 * w + 66])
    }

    // -- 5c) Periodická textura - raster se má pod maskou DOKRESLIT, ne roztáhnout -----

    /**
     * Glyph přes tečkový raster: Voronoi kopíruje nejbližší pixel, takže se pod maskou
     * roztáhne jedna tečka/mezera do skvrny. Periodická výplň má do mřížky doplnit
     * skutečnou tečku (tmavé) i mezeru (světlé) - vzorek pokračuje ve správné fázi.
     */
    @Test
    fun `masked glyph on a dot screentone is refilled with the lattice pattern`() {
        val w = 120
        val h = 60
        // Tečka rastru: 2x2 px každých 6 px (černá na bílém).
        fun isDot(x: Int, y: Int) = x % 6 < 2 && y % 6 < 2
        // Glyph: tmavě šedý blok 54..66 x 20..40 (písmeno, NE černá - ať je vidět,
        // že se do něj kopíruje raster a ne vlastní barva).
        val ink = argb(60, 60, 60)
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    x in 54..66 && y in 20..40 -> ink
                    isDot(x, y) -> black
                    else -> white
                }
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = white,
            textLeft = 50, textTop = 16, textRight = 70, textBottom = 44,
        )
        // Místo, kde má raster tečku: 60%6=0, 24%6=0 -> tečka -> černá.
        val dotSpot = luminance(patch[24 * w + 60])
        assertTrue(
            "uvnitř glyphu má pokračovat tečka rastru (tmavá), byl lum=$dotSpot",
            dotSpot < 60,
        )
        // Místo mezi tečkami: 63%6=3 -> mezera -> světlá, ne roztahaná tečka.
        val gapSpot = luminance(patch[26 * w + 63])
        assertTrue(
            "mezera rastru uvnitř glyphu má zůstat světlá (ne směs), byl lum=$gapSpot",
            gapSpot > 200,
        )
    }

    /**
     * Glyph přes šrafování (svislé čáry): periodická výplň má čáru protáhnout maskou -
     * pozice na lince tmavá, mimo ni světlá.
     */
    @Test
    fun `masked glyph on hatch lines keeps the lines running through`() {
        val w = 120
        val h = 50
        fun isHatch(x: Int) = x % 6 < 2
        val ink = argb(60, 60, 60)
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    x in 54..66 && y in 15..35 -> ink
                    isHatch(x) -> black
                    else -> white
                }
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = white,
            textLeft = 50, textTop = 10, textRight = 70, textBottom = 40,
        )
        // Na lince uvnitř bývalého glyphu: x=60 -> 60%6=0 -> linka -> tmavá.
        val onLine = luminance(patch[25 * w + 60])
        assertTrue("čára rastru má projít maskou (tmavá), byl lum=$onLine", onLine < 60)
        // Mezi linkami: x=63 -> 63%6=3 -> mezera -> světlá.
        val offLine = luminance(patch[25 * w + 63])
        assertTrue("mezera mezi čarami má zůstat světlá, byl lum=$offLine", offLine > 200)
    }

    // -- 5d) Lokální resample - jitterovaná/nepravidelná textura bez mřížky -----------

    /**
     * Reprodukce auditu Vagabond ch.6 (reálná stránka z cocomic): černý glyph s bílým
     * halo lemem leží na světlém poli s náhodnými tečkami (reálný tištěný halftone má
     * jitterovanou ~5.5px periodu - striktní mřížková výplň na něm skóruje jen ~67 % a
     * drift fáze z glyphu dřív udělala flat bílou siluetu, která četla jako "zbylý
     * bílý text"). Lokální resample má glyph rozpustit do pole: uvnitř musí zůstat
     * tečky i mezery v podobné hustotě jako okolí - ne jedna barva.
     */
    @Test
    fun `masked glyph on irregular speckle field dissolves into the field, not a flat blob`() {
        val w = 160
        val h = 110
        // Náhodně bodnuté pole: deterministický hash -> ~20 % tmavých pixelů, bez
        // pravidelné periody (autokorelace nesmí poznat mřížku).
        fun isSpeckle(x: Int, y: Int) = ((x * 73 + y * 149 + x * y * 11) % 5) == 0
        // Glyph + bílý halo lem (caption lettering: černé jádro + světlý obrys).
        fun isGlyph(x: Int, y: Int) = x in 60..99 && y in 40..64
        fun isHalo(x: Int, y: Int) = x in 57..102 && y in 37..67 && !isGlyph(x, y)
        val paper = argb(235, 235, 235)
        val rows = (0 until h).map { y ->
            (0 until w).map { x ->
                when {
                    isGlyph(x, y) -> black
                    isHalo(x, y) -> white
                    isSpeckle(x, y) -> argb(50, 50, 50)
                    else -> paper
                }
            }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = paper,
            textLeft = 55, textTop = 35, textRight = 105, textBottom = 70,
        )

        // Uvnitř bývalého glyphu: pole má pokračovat - tj. nesmí to být flat barva.
        // Tečková hustota pole ~20 % -> uvnitř očekáváme aspoň něco tmavého i světlého.
        var dark = 0
        var light = 0
        var total = 0
        for (y in 45 until 60) {
            for (x in 65 until 95) {
                val l = luminance(patch[y * w + x])
                if (l < 100) dark++
                if (l > 180) light++
                total++
            }
        }
        assertTrue(
            "uvnitř glyphu na tečkovaném poli musí pokračovat textura - " +
                "flat placka by byla vidět jako zbylý 'bílý text' (dark=$dark/$total)",
            dark > total / 20,
        )
        assertTrue(
            "uvnitř glyphu musí zůstat i světlá mezera pole (light=$light/$total)",
            light > total / 3,
        )
    }

    /**
     * Proti-test: glyph na HLADKÉ šedé ploše se má vyplnit hladce (Voronoi), resample
     * se nemá zapnout - jinak by do hladké plochy naséval šum.
     */
    @Test
    fun `masked glyph on flat field stays smooth, resample does not add noise`() {
        val w = 140
        val h = 90
        val gray = argb(190, 190, 190)
        fun isGlyph(x: Int, y: Int) = x in 55..95 && y in 35..60
        val rows = (0 until h).map { y ->
            (0 until w).map { x -> if (isGlyph(x, y)) black else gray }
        }
        val patch = buildTextPatch(
            sourceOf(rows), w, h, 0, 0, w, h, bgArgb = gray,
            textLeft = 50, textTop = 30, textRight = 100, textBottom = 65,
        )
        var offTone = 0
        var total = 0
        for (y in 42 until 54) {
            for (x in 62 until 88) {
                val l = luminance(patch[y * w + x])
                if (abs(l - 190) > 45) offTone++
                total++
            }
        }
        assertTrue(
            "hladká plocha má zůstat hladká - resample nesmí přidat šum (offTone=$offTone/$total)",
            offTone < total / 20,
        )
    }

    // -- 5) Celá textová oblast = textura -> zůstane nedotčená, ne "placka" -----------

    @Test
    fun `a region that is entirely texture is left intact, not flattened`() {
        // Checkerboard 1px - extrémní textura. Nic tu není písmem a cele pole se
        // chova jako textura: vysledek ma zkopirovat zdroj beze zmeny (zadna placka).
        val rows = (0 until 12).map { y -> (0 until 12).map { x -> if ((x + y) % 2 == 0) black else white } }
        val patch = buildTextPatch(
            sourceOf(rows), 12, 12, 0, 0, 12, 12, bgArgb = white,
            textLeft = 0, textTop = 0, textRight = 12, textBottom = 12,
        )
        assertEquals(12 * 12, patch.size)
        for (y in 0 until 12) for (x in 0 until 12) {
            assertEquals("textura na ($x,$y) ma zustat nedotcena", rows[y][x], patch[y * 12 + x])
        }
    }
}

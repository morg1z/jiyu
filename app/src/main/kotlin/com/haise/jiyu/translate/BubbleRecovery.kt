package com.haise.jiyu.translate

import kotlin.math.max
import kotlin.math.min

/**
 * Znovunalezení obrysu bubliny při VYKRESLOVÁNÍ stránky - záchrana pro bloky, u kterých
 * selhala detekce tvaru při OCR (výsledek se drží jen v paměti, viz [TextPatchProvider] -
 * do Room nic nepřibývá, takže se kvůli tomu nemusí zvedat PIPELINE_VERSION a opraví se i
 * už přeložené stránky).
 *
 * ## Proč OCR-time detekce selhává
 * Barvu pozadí i startovní body flood-fillu bere [OcrEngine.sampleBackgroundColor] z tenkého
 * prstence pixelů KOLEM OCR boxu. Když ten box přesáhne bublinu do okraje panelu (ML Kit box
 * bývá větší než samotná bublina), prstenec leží částečně mimo ni - vzorky se smíchají
 * (bublina + okraj + obrys), `bgUniform` padne na false a referenční barva pro flood-fill je
 * znečištěná. `edgeAwareShape` se pro nerovnoměrné pozadí ani nespustí. Výsledek: žádný tvar,
 * heuristická expanze boxu přes hranici bubliny a záplata věrně ukáže bílý okraj stránky
 * místo barvy bubliny - nahlášené "bílé místo zelené" na přetékajících bublinách.
 *
 * ## Proč interiér místo prstence
 * Správná otázka není "jaká je barva KOLEM boxu", ale "jaká je barva POD textem". Interiér
 * OCR boxu je totiž garantovaně uvnitř bubliny (písmena leží na jejím pozadí), i když box
 * samotný přetéká ven. Adaptivní prahování [markTextPixels] (stejné jako u záplat) oddělí
 * tahy písmen; dominantní barva zbylých pixelů = skutečná barva interiéru. U textu přímo na
 * kresbě žádná barva nedominuje - recovery se přeskočí a kresba dál dostane záplatu jako
 * dosud.
 *
 * ## Jak se najde obrys
 * Z interiéru se vezmou jen pixely dominantní barvy ve STŘEDNÍ části boxu jako seedy pro
 * [BubbleShapeDetector.detectShape] - flood-fill pak vyplní přesně tu souvislou plochu, na
 * které text leží (bublinu), a zastaví se na jejím obrysu. Výsledek projde
 * [clampShapeToOwnLobe] jako při OCR, a nakonec VALIDACÍ: aspoň [MIN_INK_COVERAGE] tahů
 * písmen musí ležet uvnitř nalezeného obrysu - jinak by se originální písmo nezakrylo a
 * obrys se zahodí (fallback na dnešní heuristický box; nikdy se neudělá hůř než teď).
 */
data class RecoveredBubble(
    /** Obrys interiéru bubliny (bez obrysu samotného - flood-fill se zastaví před ním). */
    val shape: List<BubbleShapePoint>,
    /** Dominantní barva interiéru pod textem - přesnější důkaz než prstenec kolem boxu. */
    val interiorArgb: Int,
)

/**
 * @param own OCR box bubliny, které obrys patří (pro [clampShapeToOwnLobe])
 * @param others OCR boxy ostatních bloků na stránce (včetně nepřeložených - jejich text se
 *   nesmí přemalovat)
 * @param onReject diagnostika: při zamítnutí se zavolá s krátkým důvodem ("tiny_box",
 *   "no_dominant_color", ...) - viz recordRender v TranslationDiagnostics, který z toho
 *   sestavuje přesný výsledek vykreslení místo házení ze statických flagů bloku.
 * @return null, když se interiér nedá spolehlivě určit nebo nalezený tvar nepokrývá text -
 *   volající pak zachová dosavadní vykreslení.
 */
internal fun recoverBubble(
    source: PixelSource,
    width: Int,
    height: Int,
    leftF: Float,
    topF: Float,
    rightF: Float,
    bottomF: Float,
    own: RawTextBlock,
    others: List<RawTextBlock>,
    onReject: (String) -> Unit = {},
): RecoveredBubble? {
    fun rejected(reason: String): Nothing? {
        onReject(reason)
        return null
    }
    if (width <= 0 || height <= 0) return rejected("bad_dims")
    val boxLeft = (leftF * width).toInt().coerceIn(0, width - 1)
    val boxTop = (topF * height).toInt().coerceIn(0, height - 1)
    val boxRight = (rightF * width).toInt().coerceIn(boxLeft + 1, width)
    val boxBottom = (bottomF * height).toInt().coerceIn(boxTop + 1, height)
    val boxW = boxRight - boxLeft
    val boxH = boxBottom - boxTop
    if (boxW < MIN_BOX_PX || boxH < MIN_BOX_PX) return rejected("tiny_box")

    // Vzorkovací okno = střed OCR boxu, ne celý box: střed je nejspíš uvnitř bubliny i při
    // přesahu boxu do okraje, a u obřího boxu se tím drží cena integrálního obrazu v
    // [markTextPixels] při zemi.
    val winW = min(boxW, MAX_SAMPLE_WINDOW_PX)
    val winH = min(boxH, MAX_SAMPLE_WINDOW_PX)
    val winLeft = boxLeft + (boxW - winW) / 2
    val winTop = boxTop + (boxH - winH) / 2

    val pixels = IntArray(winW * winH)
    val luminance = IntArray(winW * winH)
    for (y in 0 until winH) {
        for (x in 0 until winW) {
            val c = source.colorAt(winLeft + x, winTop + y) or OPAQUE
            pixels[y * winW + x] = c
            luminance[y * winW + x] = luminanceOf(c)
        }
    }

    // Adaptivní prahování oddělí tahy písmen - barva se počítá jen z NETEXTOVÝCH pixelů,
    // takže nemůže vyhrát barva písma (u hustého textu by prostý dominantní kbelík bez
    // masky klidně vrátil černou místo pozadí).
    val isText = markTextPixels(luminance, winW, winH)
    dilate(isText, winW, winH, INK_MASK_DILATION)

    var inkCount = 0
    var nonTextCount = 0
    // kbelíky po COLOR_BUCKET_SIZE úrovních na kanál - stejná kvantizace jako colorFor v
    // OcrEngine (velikost je privátní tam, proto vlastní konstanta zde).
    // hodnota = [count, sumR, sumG, sumB]
    val buckets = HashMap<Int, IntArray>()
    for (y in 0 until winH) {
        for (x in 0 until winW) {
            val i = y * winW + x
            if (isText[i]) {
                inkCount++
                continue
            }
            nonTextCount++
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val key = ((r / COLOR_BUCKET_SIZE) shl 16) or ((g / COLOR_BUCKET_SIZE) shl 8) or (b / COLOR_BUCKET_SIZE)
            val acc = buckets.getOrPut(key) { IntArray(4) }
            acc[0]++
            acc[1] += r
            acc[2] += g
            acc[3] += b
        }
    }
    // Málo netextových pixelů (text pokrývá skoro celý box) = málo důkazů o interiéru.
    if (nonTextCount < max(MIN_NONTEXT_ABS, (winW * winH * MIN_NONTEXT_FRACTION).toInt())) return rejected("dense_text")
    // Bez nalezených tahů není co ověřit (validace pokrytí inkoustu níž) ani co zakrýt.
    if (inkCount < MIN_INK_PIXELS) return rejected("no_ink")

    val dominant = buckets.maxByOrNull { it.value[0] } ?: return rejected("no_nontext_pixels")
    // Interiér "jednolitý" = jedna barva dominuje dostatečně. Na kresbě žádný kbelík
    // nedominuje -> null -> záplata jako dosud. Na dvoubarevné/gradientní bublině dominují
    // dva kbelíky zhruba stejně -> null -> zůstane dosavadní gradient/záplata, ne regrese.
    if (dominant.value[0] < nonTextCount * DOMINANT_SHARE) return rejected("no_dominant_color")
    val interiorArgb = OPAQUE or
        ((dominant.value[1] / dominant.value[0]) shl 16) or
        ((dominant.value[2] / dominant.value[0]) shl 8) or
        (dominant.value[3] / dominant.value[0])
    val dominantKey = dominant.key

    // Seedy jen z netextových pixelů dominantní barvy - flood-fill pak proleze jen tou
    // správnou souvislou plochou. Mřížkový podvzorek stačí: seedů stačí pár desítek, každý
    // navíc jen urychlí čelo vlny.
    val seedStepX = max(1, winW / SEED_GRID)
    val seedStepY = max(1, winH / SEED_GRID)
    val seeds = ArrayList<Pair<Int, Int>>(SEED_GRID * SEED_GRID)
    for (y in 0 until winH step seedStepY) {
        for (x in 0 until winW step seedStepX) {
            val i = y * winW + x
            if (isText[i]) continue
            val c = pixels[i]
            if (bucketKeyOf(c) != dominantKey) continue
            seeds += (winLeft + x) to (winTop + y)
        }
    }
    if (seeds.isEmpty()) return rejected("no_seeds")

    // "Cizí" pixely = nejsou text ani dominantní interiér (obrys bubliny, okraj stránky,
    // cizí kresba). Jako důkaz pro validaci obrysu níž počítá jen tahy, jejichž širší okolí
    // (~FOREIGN_RADIUS px) cizí pixel neobsahuje - sedají čistě uvnitř interiéru.
    // Adaptivní maska totiž označí za "text" i každou prudkou hranici v okně - včetně
    // vlastního obrysu bubliny a tahů přetékajících do okraje - a ty leží TĚSNĚ ZA konturou
    // by design, takže nemůžou svědčit o tom, jestli obrys sedí. Bez odfiltrování by
    // coverage check zamítl právě ty přetékající bubliny, které tahle funkce opravuje.
    // Integrální obraz = dotaz "je v okolí cizí pixel" v O(1).
    val foreignIntegral = IntArray((winW + 1) * (winH + 1))
    for (y in 0 until winH) {
        var rowSum = 0
        for (x in 0 until winW) {
            val i = y * winW + x
            val foreign = !isText[i] && bucketKeyOf(pixels[i]) != dominantKey
            rowSum += if (foreign) 1 else 0
            foreignIntegral[(y + 1) * (winW + 1) + (x + 1)] = foreignIntegral[y * (winW + 1) + (x + 1)] + rowSum
        }
    }

    // textAreaPx=0 záměrně: interní poměrová kontrola v detectShape běží ještě PŘED ořezem
    // na vlastní lalok (clampShapeToOwnLobe) a je kalibrovaná na plochu OCR boxu - u
    // spojených/kaskádových bublin by zamítla i obrys, který clamp správně zúží na náš
    // díl. Poměr proto kontrolujeme sami níže, až po clampu a proti inkoustu (přísnější
    // míra než nafouklý OCR box - uniklé vylití do okraje panelu tak nemůže projít).
    val shape = BubbleShapeDetector.detectShape(
        source = source,
        width = width,
        height = height,
        seeds = seeds,
        bgColorArgb = interiorArgb,
        textAreaPx = 0,
    ) ?: return rejected("shape_detect_failed")
    val clamped = clampShapeToOwnLobe(shape = shape, own = own, others = others)

    // Ověřitelný inkoust = tahy čistě uvnitř interiéru (žádný cizí pixel v širším okolí).
    // Z jejich ohraničení se odvozuje i měřítko pro poměrový strop obrysu.
    var verifiableInk = 0
    var coveredInk = 0
    var vLeft = Int.MAX_VALUE
    var vTop = Int.MAX_VALUE
    var vRight = Int.MIN_VALUE
    var vBottom = Int.MIN_VALUE
    for (y in 0 until winH) {
        for (x in 0 until winW) {
            if (!isText[y * winW + x]) continue
            val ax = max(0, x - FOREIGN_RADIUS)
            val bx = min(winW - 1, x + FOREIGN_RADIUS)
            val ay = max(0, y - FOREIGN_RADIUS)
            val by = min(winH - 1, y + FOREIGN_RADIUS)
            val foreign = foreignIntegral[(by + 1) * (winW + 1) + (bx + 1)] -
                foreignIntegral[ay * (winW + 1) + (bx + 1)] -
                foreignIntegral[(by + 1) * (winW + 1) + ax] +
                foreignIntegral[ay * (winW + 1) + ax]
            if (foreign > 0) continue
            verifiableInk++
            val px = winLeft + x
            val py = winTop + y
            if (px < vLeft) vLeft = px
            if (px > vRight) vRight = px
            if (py < vTop) vTop = py
            if (py > vBottom) vBottom = py
            // Validace po řádcích přes skutečný kontur (shapeBoundsAtYF), ne jen přes
            // obalový obdélník - u kulaté bubliny by bbox v rozích "pokryl" i písmena mimo
            // kontur.
            if (shapeContainsPixel(clamped, px / width.toFloat(), py / height.toFloat())) coveredInk++
        }
    }
    // Žádný tah obalený interiérem = buď je text přímo na hraně/kresbě (nemá co obnovit),
    // nebo dominantní barva byla okraj a interiér je "cizí" - obojí konzervativní null.
    if (verifiableInk < MIN_INK_PIXELS) return rejected("no_verifiable_ink")

    // Strop na plochu obrysu proti ohraničení tahů. Slušná bublina je řádově ~10-30x
    // ohraničení svého textu (bublina = text + volný prostor kolem), zatímco vylití přes
    // mezeru v obrysu do okraje/kresby se táhne na stovky násobků - a toho si coverage
    // níž ani nevšimne, protože písmena v něm leží pořád "uvnitř".
    val inkAreaPx = (vRight - vLeft + 1).toLong() * (vBottom - vTop + 1).toLong()
    if (shapeAreaFraction(clamped) * width * height > inkAreaPx.coerceAtLeast(1) * MAX_SHAPE_TO_INK_RATIO) return rejected("shape_too_big")

    // Nalezený obrys musí pokrývat (skoro) všechny ověřitelné tahy - jinak by překlad
    // zakryl jen část originálu a okraj zůstal viset venku. Malý díl mimo se toleruje
    // (antialias lem obrysu, chyba masky u samotné hrany).
    if (coveredInk < verifiableInk * MIN_INK_COVERAGE) return rejected("low_ink_coverage")

    return RecoveredBubble(shape = clamped, interiorArgb = interiorArgb)
}

/** Kbelíková kvantizace barvy - stejná jako COLOR_BUCKET_SIZE v OcrEngine. */
private fun bucketKeyOf(c: Int): Int =
    (((c shr 16) and 0xFF) / COLOR_BUCKET_SIZE shl 16) or
        (((c shr 8) and 0xFF) / COLOR_BUCKET_SIZE shl 8) or
        ((c and 0xFF) / COLOR_BUCKET_SIZE)

/** Přibližná plocha obrysu jako zlomek stránky - lichoběžníky mezi sousedními řádky vzorků. */
private fun shapeAreaFraction(shape: List<BubbleShapePoint>): Float {
    if (shape.size < 2) return 0f
    var area = 0f
    for (i in 0 until shape.size - 1) {
        val a = shape[i]
        val b = shape[i + 1]
        area += ((a.rightF - a.leftF) + (b.rightF - b.leftF)) / 2f * (b.yF - a.yF)
    }
    return area
}

/** Leží stránkový pixel (normalizované souřadnice) uvnitř obrysu bubliny v dané výšce? */
private fun shapeContainsPixel(shape: List<BubbleShapePoint>, xF: Float, yF: Float): Boolean {
    if (shape.isEmpty() || yF < shape.first().yF || yF > shape.last().yF) return false
    val (left, right) = shapeBoundsAtYF(shape, yF)
    return xF in left..right
}

/** Menší než tohle OCR box nemá smysl analyzovat (práhové okno by se tam nevešlo). */
private const val MIN_BOX_PX = 8

/** Strop vzorkovacího okna (střed OCR boxu) - drží integrální obraz [markTextPixels] malý. */
private const val MAX_SAMPLE_WINDOW_PX = 384

/** Minimální podíl/počet netextových pixelů v okně - jinak není dost důkazů o barvě interiéru. */
private const val MIN_NONTEXT_FRACTION = 0.12f
private const val MIN_NONTEXT_ABS = 16

/** Pod ním nemá smysl nic validovat - maska byla prázdná, recovery by jen házelo. */
private const val MIN_INK_PIXELS = 8

/** Kolik netextových pixelů musí padnout do nejčastějšího kbelíku, aby šel interiér za jednolitý. */
private const val DOMINANT_SHARE = 0.55f

/** Kolik ověřitelných tahů musí ležet uvnitř nalezeného obrysu - jinak by originál koukal ven. */
private const val MIN_INK_COVERAGE = 0.85f

/** Vzdálenost (v px okna), do které nesmí tah "vidět" cizí pixel, aby sloužil jako důkaz
 * obrysu. Větší než šířka okrajového pásu, který adaptivní maska označí za text
 * (~poloměr jejího okna + dilatace), menší než typická rezerva mezi textem a obrysem. */
private const val FOREIGN_RADIUS = 10

/** Strop plochy obrysu proti ohraničení tahů - analogie MAX_SHAPE_TO_TEXT_AREA_RATIO, jen
 * měřená po ořezu na vlastní lalok a proti inkoustu (užší míra než OCR box, proto vyšší
 * než oněch 45). */
private const val MAX_SHAPE_TO_INK_RATIO = 60L

/** Stejná hodnota jako MASK_DILATION u záplat - lem antialiasu kolem tahů není pozadí. */
private const val INK_MASK_DILATION = 2

/** Kbelíková kvantizace barev - stejná jako COLOR_BUCKET_SIZE v OcrEngine. */
private const val COLOR_BUCKET_SIZE = 32

/** Mřížka pro podvzorek seedů flood-fillu. */
private const val SEED_GRID = 32

private const val OPAQUE = 0xFF shl 24

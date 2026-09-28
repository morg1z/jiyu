package com.haise.jiyu.translate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zakryje TAHY PÍSMEN v zadané oblasti a každý zakrytý pixel dopočítá z okolního pozadí,
 * takže kresba mezi písmeny zůstane vidět.
 *
 * Proč to existuje: dokud text leží v obyčejné bublině, stačí ji vyplnit jednou barvou a
 * vypadá to dokonale. Jenže když text leží PŘÍMO NA KRESBĚ (viz [RawTextBlock.bgUniform] =
 * false), jedna barva nahrazuje kus obrázku - a tak vznikaly hlášené placky: hnědá skvrna
 * přes barevnou titulní kresbu, černá přes obličej postavy. Žádná jediná barva tam být
 * nemůže, protože pod textem žádná jediná barva není.
 *
 * Skutečné řešení je inpainting neuronovým modelem (LaMa apod.), ten ale chce GPU server.
 * Tohle je levná náhrada: pozná se, co je text, a dopočítá se jen on.
 *
 * ## Proč MÍSTNÍ kontrast, a ne odchylka od navzorkované barvy
 * První verze označovala za text vše, co se dost lišilo od jedné referenční barvy pozadí.
 * Na jednobarevném podkladu to funguje, jenže právě u `bgUniform = false` žádný jednobarevný
 * podklad neexistuje: na kresbě půl červené a půl modré se celá modrá polovina od "pozadí"
 * (červené) liší a označila se za text - tedy by se přemalovala. Odhalil to test
 * `a colour gradient in the art survives instead of being flattened`.
 *
 * Text se proto hledá adaptivním prahováním (Bradleyho postup): pixel je text, když se jeho
 * jas výrazně liší od PRŮMĚRU SVÉHO OKOLÍ. Souvislá barevná plocha svému okolí odpovídá bez
 * ohledu na odstín, kdežto tah písma se od něj liší vždycky - a je jedno, jestli je tmavý na
 * světlém, nebo naopak. Průměry okolí se počítají z integrálního obrazu, takže cena na pixel
 * nezávisí na velikosti okna.
 *
 * ## Proč se hledá jen uvnitř [textLeft]..[textBottom]
 * Záplata musí pokrýt celý box, přes který se bublina kreslí, a ten je větší než OCR box
 * samotného textu (viz [renderBoxRect]). Prahovat i ten přesah by znamenalo dopočítávat
 * kresbu tam, kde žádné písmo nikdy nebylo - tedy rozmazávat obraz bez důvodu. Mimo textovou
 * oblast se proto pixely jen opíší.
 *
 * @param left/top/right/bottom oblast v pixelech; ořízne se na rozměry obrázku
 * @param textLeft/textTop/textRight/textBottom oblast, kde se smí hledat písmo (OCR box);
 *   výchozí -1 znamená "celá záplata", jako to bylo dřív
 * @param bgArgb navzorkované pozadí - použije se jen jako záchrana, když nelze dopočítat nic
 * @param meanTextArgbOut volitelný výstup velikosti 1: doplní se do něj JÁDROVÁ barva
 *   původního písma (viz [coreTextArgb]) - render ji použije pro překlad, aby lettering
 *   na kresbě držel barvu originálu (bílý caption s tmavým lemem -> bílá čeština s tmavým
 *   obrysem, ne černý text vybíraný podle jasu světlého podkladu). Bez textových pixelů
 *   se do něj zapíše 0 (= transparentní, nenastalo).
 * @return ARGB pixely oblasti, řádek po řádku; prázdné pole pro prázdnou oblast
 */
internal fun buildTextPatch(
    source: PixelSource,
    imageWidth: Int,
    imageHeight: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    bgArgb: Int,
    textLeft: Int = -1,
    textTop: Int = -1,
    textRight: Int = -1,
    textBottom: Int = -1,
    meanTextArgbOut: IntArray? = null,
): IntArray {
    val x0 = left.coerceIn(0, imageWidth)
    val y0 = top.coerceIn(0, imageHeight)
    val x1 = right.coerceIn(0, imageWidth)
    val y1 = bottom.coerceIn(0, imageHeight)
    val w = x1 - x0
    val h = y1 - y0
    if (w <= 0 || h <= 0) return IntArray(0)

    val pixels = IntArray(w * h)
    val luminance = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val c = source.colorAt(x0 + x, y0 + y) or OPAQUE
            pixels[i] = c
            luminance[i] = luminanceOf(c)
        }
    }

    val isText = markTextPixels(luminance, w, h)
    dilate(isText, w, h, MASK_DILATION)
    // Až PO dilataci - jinak by lem rozšířený z písma u kraje textové oblasti zůstal viset
    // venku a dopočítal by se z něj kus kresby.
    val hasTextRegion = textLeft >= 0 && textTop >= 0 && textRight > textLeft && textBottom > textTop
    val pad = if (hasTextRegion) textRegionPadding(textBottom - textTop) else 0
    restrictToTextRegion(
        mask = isText,
        w = w,
        h = h,
        left = textLeft - x0 - pad,
        top = textTop - y0 - pad,
        right = textRight - x0 + pad,
        bottom = textBottom - y0 + pad,
        enabled = hasTextRegion,
    )

    // Nemá se z čeho počítat (celá oblast vyšla jako text) - vrátí se navzorkované pozadí.
    // Lepší než nic a nikdy to nespadne.
    if (isText.all { it }) return IntArray(w * h) { bgArgb or OPAQUE }

    if (meanTextArgbOut != null && meanTextArgbOut.isNotEmpty()) {
        meanTextArgbOut[0] = coreTextArgb(pixels, luminance, isText)
    }

    fillFromNeighbours(pixels, isText, w, h)
    return pixels
}

/**
 * Odhad barvy PÍSMA z maskovaných pixelů - pro překlad vykreslený přes záplatu, aby držel
 * vizuální styl originálu (viz parametr meanTextArgbOut u [buildTextPatch]).
 *
 * Prostý průměr by u comiksového lettering "světlé jádro + tmavý obrys" spadl do šedé a
 * překlad by nevypadal jako originál. Proto se z textových pixelů vybere jen ta polovina,
 * která leží na STRANĚ OPAČNÉ k pozadí - světlý caption na tmavé/průměrné kresbě vrátí
 * barvu jádra (skoro bílou), tmavý nápis na světlé vrátí inkoust. Obrys se tak přirozeně
 * vyloučí: je to ta část masky, která jasově sedí blíž k pozadí.
 *
 * Vrací 0, když maska žádné textové pixely nemá.
 */
private fun coreTextArgb(pixels: IntArray, luminance: IntArray, isText: BooleanArray): Int {
    var bgSum = 0L
    var bgCount = 0L
    var minTextLum = Int.MAX_VALUE
    var maxTextLum = Int.MIN_VALUE
    for (i in pixels.indices) {
        if (isText[i]) {
            val l = luminance[i]
            if (l < minTextLum) minTextLum = l
            if (l > maxTextLum) maxTextLum = l
        } else {
            bgSum += luminance[i]
            bgCount++
        }
    }
    if (bgCount == 0L || minTextLum > maxTextLum) return 0
    val bgLum = (bgSum / bgCount).toInt()
    // Písmo je extrém OD pozadí - vezmeme vzdálenější konec (jádro tahu), průměrujeme jen
    // pixely za hranicí mezi ním a pozadím, čímž odpadne protilehlý obrys.
    val lightCore = (maxTextLum - bgLum) >= (bgLum - minTextLum)
    val edge = if (lightCore) maxTextLum else minTextLum
    val threshold = (edge + bgLum) / 2
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    for (i in pixels.indices) {
        if (!isText[i]) continue
        val l = luminance[i]
        if ((lightCore && l <= threshold) || (!lightCore && l >= threshold)) continue
        val c = pixels[i]
        r += (c shr 16) and 0xFF
        g += (c shr 8) and 0xFF
        b += c and 0xFF
        n++
    }
    if (n == 0L) return 0
    return OPAQUE or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

/**
 * Adaptivní prahování: pixel je text, když se jeho jas dost liší od průměru okolního okna.
 * Chytá tmavé písmo na světlém i světlé na tmavém, protože se porovnává absolutní rozdíl.
 *
 * internal (ne private): sdílí ho i [recoverBubble], který maskou oddělí tahy písmen od
 * skutečné barvy interiéru bubliny - viz tam.
 */
internal fun markTextPixels(luminance: IntArray, w: Int, h: Int): BooleanArray {
    val integral = LongArray((w + 1) * (h + 1))
    for (y in 0 until h) {
        var rowSum = 0L
        for (x in 0 until w) {
            rowSum += luminance[y * w + x]
            integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
        }
    }

    // Okno musí být větší než tah písma, ale menší než celá oblast - jinak by se z něj stal
    // globální průměr a byli bychom zpátky u původního problému.
    val window = max(MIN_WINDOW, min(w, h) / WINDOW_DIVISOR)
    val radius = window / 2

    val isText = BooleanArray(w * h)
    for (y in 0 until h) {
        val ay = max(0, y - radius)
        val by = min(h - 1, y + radius)
        for (x in 0 until w) {
            val ax = max(0, x - radius)
            val bx = min(w - 1, x + radius)
            val count = (bx - ax + 1).toLong() * (by - ay + 1).toLong()
            val sum = integral[(by + 1) * (w + 1) + (bx + 1)] -
                integral[ay * (w + 1) + (bx + 1)] -
                integral[(by + 1) * (w + 1) + ax] +
                integral[ay * (w + 1) + ax]
            val mean = (sum / count).toInt()
            val delta = abs(luminance[y * w + x] - mean)
            isText[y * w + x] = delta > max(MIN_TEXT_DELTA, mean * TEXT_DELTA_RATIO / 100)
        }
    }
    return isText
}

/**
 * O kolik pixelů se textová oblast rozšíří, než se jí ořeže maska.
 *
 * OCR box je jen aproximace otisku písma, ne jeho přesná obálka - ML Kit ho běžně vede o kus
 * uvnitř skutečných tahů (naměřeno sondou na zařízení: u repliky kreslené od x=180 vrátil levý
 * okraj až na 190). Bez rezervy zůstane všechno, co přesáhne, v obraze nedotčené: lem kolem
 * písmen, spodky dotahů, tečky na hranici boxu. Přesně tak vypadal nahlášený caption
 * "...HAS ENDED.", který po překladu zůstal čitelný.
 *
 * Rezerva se odvozuje z VÝŠKY textové oblasti, protože ta odpovídá velikosti písma - přesah je
 * úměrný jemu, ne rozměrům stránky. Strop i podlaha jsou tam proto, aby u obřího nadpisu
 * nezasáhla rezerva půl kresby a u drobného textu nebyla nulová.
 */
private fun textRegionPadding(textHeight: Int): Int =
    (textHeight / TEXT_PAD_DIVISOR).coerceIn(MIN_TEXT_PAD, MAX_TEXT_PAD)

/**
 * Vymaže z masky všechno mimo zadaný obdélník - viz komentář u [buildTextPatch] k textové
 * oblasti. Souřadnice jsou už relativní k záplatě a smí přesahovat přes její okraj.
 */
private fun restrictToTextRegion(
    mask: BooleanArray,
    w: Int,
    h: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    enabled: Boolean,
) {
    if (!enabled) return
    for (y in 0 until h) {
        val insideRow = y >= top && y < bottom
        for (x in 0 until w) {
            if (insideRow && x >= left && x < right) continue
            mask[y * w + x] = false
        }
    }
}

/** Rozšíří masku o [radius] pixelů - zachytí antialiasový lem, který by jinak zůstal jako duch. */
internal fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int) {
    repeat(radius) {
        val previous = mask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (previous[y * w + x]) continue
                val touchesText =
                    (x > 0 && previous[y * w + x - 1]) ||
                        (x < w - 1 && previous[y * w + x + 1]) ||
                        (y > 0 && previous[(y - 1) * w + x]) ||
                        (y < h - 1 && previous[(y + 1) * w + x])
                if (touchesText) mask[y * w + x] = true
            }
        }
    }
}

/**
 * Vyplňuje maskované pixely zvenčí dovnitř: v každém kole dostane pixel průměr už hotových
 * sousedů. Tlustý tah se uzavře stejně spolehlivě jako tenký, jen potřebuje víc kol.
 */
private fun fillFromNeighbours(pixels: IntArray, isText: BooleanArray, w: Int, h: Int) {
    val pending = isText.copyOf()
    var guard = 0
    while (guard++ < MAX_FILL_ROUNDS) {
        var filledAny = false
        val resolvedThisRound = mutableListOf<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!pending[i]) continue
                var r = 0; var g = 0; var b = 0; var n = 0
                fun take(j: Int) {
                    if (pending[j]) return
                    val c = pixels[j]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                if (x > 0) take(i - 1)
                if (x < w - 1) take(i + 1)
                if (y > 0) take(i - w)
                if (y < h - 1) take(i + w)
                if (n == 0) continue
                pixels[i] = OPAQUE or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                resolvedThisRound += i
                filledAny = true
            }
        }
        // Až PO celém kole - jinak by pixel doplněný na začátku řádku hned sloužil jako
        // "hotový" soused tomu vedle a barva by se táhla jedním směrem místo ze všech stran.
        resolvedThisRound.forEach { pending[it] = false }
        if (!filledAny) return
    }
}

internal fun luminanceOf(c: Int): Int {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
}

private const val OPAQUE = 0xFF shl 24

/** Minimální rozdíl jasu proti okolí, aby šlo o text - drží mimo hru jemné přechody v kresbě. */
private const val MIN_TEXT_DELTA = 30

/** Relativní složka prahu (v procentech průměru) - na světlém podkladu chce text větší odstup. */
private const val TEXT_DELTA_RATIO = 15

private const val MIN_WINDOW = 7
private const val WINDOW_DIVISOR = 6

private const val MASK_DILATION = 2

/** Rezerva kolem textové oblasti jako podíl její výšky - viz [textRegionPadding]. */
private const val TEXT_PAD_DIVISOR = 4
private const val MIN_TEXT_PAD = 2
private const val MAX_TEXT_PAD = 12

/** Pojistka proti nekonečné smyčce; při ~2px za kolo pokryje i velmi tlusté tahy. */
private const val MAX_FILL_ROUNDS = 64

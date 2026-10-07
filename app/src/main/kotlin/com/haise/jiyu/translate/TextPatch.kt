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
    val hasTextRegion = textLeft >= 0 && textTop >= 0 && textRight > textLeft && textBottom > textTop
    val pad = if (hasTextRegion) textRegionPadding(textBottom - textTop) else 0
    // Ořez na textovou oblast už PŘED filtrováním - komponenty/densita se hodnotí jen
    // na masce uvnitř ní (kresba mimo oblast v komponentách nemá co dělat).
    restrictToTextRegion(
        mask = isText, w = w, h = h,
        left = textLeft - x0 - pad, top = textTop - y0 - pad,
        right = textRight - x0 + pad, bottom = textBottom - y0 + pad,
        enabled = hasTextRegion,
    )
    // Adaptivní práh nerozezná písmo od TEXTURY: na rasteru (halftone tečky) a
    // šrafování označí každou tečku/čárku jako "text" a výplň je pak přemaluje -
    // přesně tak vznikaly nahlášené "barcode" pruhy a šedé placky. Glyphy jsou na
    // rozdíl od textury MÁLO větších souvislých komponent, takže pole komponent
    // s mnoha drobnými kousky se filtruje.
    suppressTextureNoise(
        isText, luminance, w, h,
        rl = textLeft - x0 - pad, rt = textTop - y0 - pad,
        rr = textRight - x0 + pad, rb = textBottom - y0 + pad,
        enabled = hasTextRegion,
    )
    dilate(isText, w, h, MASK_DILATION)
    // Po dilataci znovu ořezat - rozšíření mohlo vytéct přes hranici textové oblasti.
    restrictToTextRegion(
        mask = isText, w = w, h = h,
        left = textLeft - x0 - pad, top = textTop - y0 - pad,
        right = textRight - x0 + pad, bottom = textBottom - y0 + pad,
        enabled = hasTextRegion,
    )
    // Lokální kontrast označí jen OKRAJE tahu - uvnitř velkého jednotného glyphu je
    // průměr okna = inkoust, delta 0, a jádro zůstane nemaskované (= zbylý kus
    // originálního textu, další nahlášený symptom). Co je uzavřeno kruhem maskovaných
    // hran a nedá se dosáhnout z okraje textové oblasti, je interiér písma - domaskovat.
    sealEnclosedHoles(
        mask = isText, w = w, h = h,
        left = textLeft - x0 - pad, top = textTop - y0 - pad,
        right = textRight - x0 + pad, bottom = textBottom - y0 + pad,
        enabled = hasTextRegion,
    )

    // Nemá se z čeho počítat (celá oblast vyšla jako text) - vrátí se navzorkované pozadí.
    // Lepší než nic a nikdy to nespadne.
    if (isText.all { it }) return IntArray(w * h) { bgArgb or OPAQUE }

    if (meanTextArgbOut != null && meanTextArgbOut.isNotEmpty()) {
        meanTextArgbOut[0] = coreTextArgb(pixels, luminance, isText)
    }

    fillNearestSource(pixels, isText, w, h)
    return pixels
}

/**
 * Potlačí falešné "textové" pixely, které adaptivní práh označí v texturách
 * (halftone raster, šrafování, hustá kresba). Bez filtrace se maska rozleze přes
 * celou texturu a výplň ji přemaluje na pruhy/placku - viz reprodukční testy
 * `screentone dots inside the text region survive the patch` a `hatch lines ...`.
 *
 * Rozlišení text vs textura (v kontextu komiksové stránky):
 * - Písmo = MÁLO větších souvislých komponent (tahy písmen jsou tlusté a souvislé).
 * - Textura = HODNĚ drobných komponent (rastrové tečky) NEBO tenké dlouhé komponenty
 *   (šrafové čáry protínající celou oblast).
 *
 * Filtr komponentů se zapíná jen když je jich hodně - viz MIN_TEXTURE_COMPONENTS;
 * u pár komponentů (běžný text) je každá podezřelá čára klidně skutečné písmeno.
 * Branka hustoty (pole-split) řeší slitou dvoufázovou texturu, kde adaptivní práh
 * označil tečky i mezery najednou - tam se maska rozseká na inkoustový (menšinový)
 * pól a teprve ten se komponentově filtruje.
 */
private fun suppressTextureNoise(
    mask: BooleanArray,
    luminance: IntArray,
    w: Int,
    h: Int,
    rl: Int,
    rt: Int,
    rr: Int,
    rb: Int,
    enabled: Boolean,
) {
    if (!enabled) return
    val l = rl.coerceIn(0, w)
    val t = rt.coerceIn(0, h)
    val r = rr.coerceIn(l, w)
    val b = rb.coerceIn(t, h)
    val regionW = r - l
    val regionH = b - t
    if (regionW <= 0 || regionH <= 0) return

    // 1) Densita masky v textové oblasti. Ridka maska (bezny text na klidnem
    //    pozadi) = pismeno i s obrysem - pole-split by tam prastskytnul.
    var masked = 0
    for (y in t until b) for (x in l until r) if (mask[y * w + x]) masked++
    val regionArea = regionW * regionH
    val dense = masked * 100 > regionArea * DENSE_MASK_PERCENT

    // 2) HUSTA maska = textura: na rastru/srafure se lokalni prah oznaci OBEMI
    //    fazemi (tecky i mezery se od prumeru obe lisi) a maska = cele pole.
    //    Rozdeleni podle polu histogramu: inkoust pisma je vzdy MENSINA - pole
    //    kandidatu se rozdeli na tmavy/svetly a vetsinovy se zahodi. Tim se i
    //    slite dvoufazove textury rozpadnou na jednotlive komponenty, ktere
    //    pak area/thin-long filtr dolabely (tecky a srafove cary).
    if (dense) {
        // Median jasu regionu z histogramu.
        val hist = IntArray(256)
        for (y in t until b) for (x in l until r) hist[luminance[y * w + x]]++
        var acc = 0
        var median = 0
        val half = regionArea / 2
        while (median < 255 && acc + hist[median] <= half) { acc += hist[median]; median++ }
        val delta = max(MIN_TEXT_DELTA, median * TEXT_DELTA_RATIO / 100)
        var dark = 0
        var light = 0
        for (y in t until b) {
            for (x in l until r) {
                val i = y * w + x
                if (!mask[i]) continue
                if (luminance[i] < median - delta) dark++ else light++
            }
        }
        if (dark > 0 && light > 0) {
            // Zahodit vetsinovy pol; pri shode radsi svetly (inkoust byva tmavy).
            val dropDark = dark >= light
            for (y in t until b) {
                for (x in l until r) {
                    val i = y * w + x
                    if (!mask[i]) continue
                    val isDark = luminance[i] < median - delta
                    if (isDark == dropDark) mask[i] = false
                }
            }
        }
    }

    // 3) Olabelovat souvisle komponenty masky uvlnittr textove oblasti (4-conn BFS).
    val compId = IntArray(w * h) { -1 }
    val areas = ArrayList<Int>()
    val thinLong = ArrayList<Boolean>() // komponenta je tenka a dlouha = srafova cara
    val stack = IntArray(w * h)
    var compCount = 0
    for (y in t until b) {
        for (x in l until r) {
            val i = y * w + x
            if (!mask[i] || compId[i] >= 0) continue
            var sp = 0
            stack[sp++] = i
            compId[i] = compCount
            var area = 0
            var minX = x; var maxX = x; var minY = y; var maxY = y
            while (sp > 0) {
                val j = stack[--sp]
                area++
                val jx = j % w
                val jy = j / w
                if (jx < minX) minX = jx
                if (jx > maxX) maxX = jx
                if (jy < minY) minY = jy
                if (jy > maxY) maxY = jy
                fun push(nx: Int, ny: Int) {
                    val n = ny * w + nx
                    if (nx in l until r && ny in t until b && mask[n] && compId[n] < 0) {
                        compId[n] = compCount
                        stack[sp++] = n
                    }
                }
                push(jx - 1, jy); push(jx + 1, jy); push(jx, jy - 1); push(jx, jy + 1)
            }
            areas.add(area)
            val compW = maxX - minX + 1
            val compH = maxY - minY + 1
            thinLong.add(
                minOf(compW, compH) <= THIN_LINE_PX &&
                    maxOf(compW, compH) >= LONG_LINE_PX,
            )
            compCount++
        }
    }

    // 4) Texturove pole = mnoho komponentu: zahodit drobne (rastrove tecky) a
    //    tenko-dlouhe (srafove cary). Glyphy (par velkych komponentu) preziji.
    if (compCount >= MIN_TEXTURE_COMPONENTS) {
        // Ocekavana plocha tahu pisma z vysky oblasti - skaluje s velikosti pisma.
        val glyphScale = (regionH / GLYPH_SCALE_DIVISOR).coerceIn(2, 8)
        val minArea = (glyphScale * glyphScale).coerceIn(MIN_GLYPH_AREA, MAX_GLYPH_AREA)
        val drop = BooleanArray(compCount) { c -> areas[c] < minArea || thinLong[c] }
        // Jednopruchodove smazani podle compId - per-komponentni pruchody by u pole
        // stovek tecek byly O(komponenty * region).
        for (y in t until b) {
            for (x in l until r) {
                val i = y * w + x
                val c = compId[i]
                if (c >= 0 && drop[c]) mask[i] = false
            }
        }
    }
}

/**
 * Domaskuje "díry": nemaskované pixely uvnitř textové oblasti, které nejsou dosažitelné
 * z jejího okraje cestou přes nemaskované pixely. Adaptivní práh označuje jen hrany tahů
 * (uvnitř souvislého inkoustu se pixel svému okolí neliší), takže jádro velkého písmene -
 * nebo protiskuska "O" - by jinak v obrázku zůstalo jako nedotčený originální text.
 *
 * Kapesku otevřenou k okraji oblasti to nikdy nesežere: její okrajové pixely jsou seedy
 * a zalijí celou kapsu. Flood běží jen uvnitř oblasti, složitost O(region).
 */
private fun sealEnclosedHoles(
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
    val l = left.coerceIn(0, w)
    val t = top.coerceIn(0, h)
    val r = right.coerceIn(l, w)
    val b = bottom.coerceIn(t, h)
    if (r - l <= 0 || b - t <= 0) return

    val reached = BooleanArray(w * h)
    val queue = IntArray(w * h)
    var qs = 0
    var qe = 0
    fun seed(i: Int) {
        if (!mask[i] && !reached[i]) {
            reached[i] = true
            queue[qe++] = i
        }
    }
    for (x in l until r) { seed(t * w + x); seed((b - 1) * w + x) }
    for (y in t until b) { seed(y * w + l); seed(y * w + r - 1) }
    while (qs < qe) {
        val i = queue[qs++]
        val x = i % w
        val y = i / w
        fun visit(nx: Int, ny: Int) {
            if (nx < l || nx >= r || ny < t || ny >= b) return
            seed(ny * w + nx)
        }
        visit(x - 1, y); visit(x + 1, y); visit(x, y - 1); visit(x, y + 1)
    }
    // Nedosažené nemaskované = uzavřený interiér písma -> maskovat.
    for (y in t until b) {
        for (x in l until r) {
            val i = y * w + x
            if (!mask[i] && !reached[i]) mask[i] = true
        }
    }
}

/**
 * Vyplní maskované pixely barvou NEJBLIŽŠÍHO nemaskovaného pixelu (multi-source BFS
 * = Voronoi propagace). Oproti předchozímu iterativnímu průměrování [fillFromNeighbours]:
 *
 * - žádné koncentrické prstence: každý pixel kopíruje reálnou barvu, ne průměr
 *   předchozího prstence (iterativní průměr na velké masce vytvářel viditelné pásy),
 * - výplň drží paletu obrázku: výsledek je vždy barva, která v okolí skutečně
 *   existuje - žádná "vymyšlená" šedá mezi černou a bílou,
 * - textura se POKRAČUJE: pixel uvnitř masky nad rastrem zkopíruje nejbližší
 *   rastrový bod, takže se vzorek dovnitř alespoň přibližně doplňuje,
 * - černé pozadí zůstane čistě černé (žádný šedý průměr).
 *
 * Složitost O(w*h): každý pixel se do fronty dostane jednou.
 */
private fun fillNearestSource(pixels: IntArray, isText: BooleanArray, w: Int, h: Int) {
    val queue = IntArray(w * h)
    var qs = 0
    var qe = 0
    // Seed: všechny nemaskované pixely - každý maskovaný pixel se pak obsadí barvou
    // svého nejbližšího nemaskovaného zdroje (4-conn vzdálenost).
    for (i in pixels.indices) {
        if (!isText[i]) queue[qe++] = i
    }
    while (qs < qe) {
        val i = queue[qs++]
        val x = i % w
        val y = i / w
        fun visit(n: Int) {
            if (!isText[n]) return
            isText[n] = false
            pixels[n] = pixels[i]
            queue[qe++] = n
        }
        if (x > 0) visit(i - 1)
        if (x < w - 1) visit(i + 1)
        if (y > 0) visit(i - w)
        if (y < h - 1) visit(i + w)
    }
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

// -- Potlačení textur v masce (viz [suppressTextureNoise]) ------------------------------

/**
 * Kolik souvislých komponent musí maska v textové oblasti mít, aby se hodnotila jako
 * textura a filtrovala. Skutečný řádek textu má pár komponent (písmena); raster nebo
 * šrafura jich má desítky.
 */
private const val MIN_TEXTURE_COMPONENTS = 8

/**
 * Podíl výšky textové oblasti, ze kterého se odvozuje očekávaná šířka tahu písma -
 * komponenty s plochou pod jeho čtverec se při texturovém poli zahazují jako tečky
 * rastru. Clamped: drobný text nechce zahazovat ani skutečně malé komponenty, obří
 * nadpis zase nesmí vyžadovat absurdně velké tahy.
 */
private const val GLYPH_SCALE_DIVISOR = 8
private const val MIN_GLYPH_AREA = 8
private const val MAX_GLYPH_AREA = 64

/**
 * Šrafová čára: tenká (<= 2 px) a dlouhá (>= 8 px). Glyphy jako "I" nebo "l" jsou taky
 * úzké, ale filtr se zapíná jen u texturového pole (viz MIN_TEXTURE_COMPONENTS).
 */
private const val THIN_LINE_PX = 2
private const val LONG_LINE_PX = 8

/**
 * Kolik procent textové oblasti musí maska pokrýt, aby se považovala za slitou
 * texturu a erodovala - viz [suppressTextureNoise] bod 3.
 */
private const val DENSE_MASK_PERCENT = 55

/** Rezerva kolem textové oblasti jako podíl její výšky - viz [textRegionPadding]. */
private const val TEXT_PAD_DIVISOR = 4
private const val MIN_TEXT_PAD = 2
private const val MAX_TEXT_PAD = 12

/** Pojistka proti nekonečné smyčce; při ~2px za kolo pokryje i velmi tlusté tahy. */
private const val MAX_FILL_ROUNDS = 64

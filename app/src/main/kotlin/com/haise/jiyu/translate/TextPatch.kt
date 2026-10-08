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
    // Glyph OŘÍZNUTÝ hranou textové oblasti (OCR box sekl tah doprostřed) má interiér
    // otevřený k okraji - seal ho jako díru nepozná a maska za hranicí regionu vůbec
    // neexistuje: pahýl písmene by přežil viditelně i po vyplnění. Ink-flood: z
    // maskovaných pixelů na hranici regionu se zaleje inkoust o KONTROLOVANÝ kus
    // ven i dovnitř (viz [floodClippedGlyphs]), pak se maska ořeže na rozšířenou
    // oblast a druhé kolo sealu zavře případné nové kapsy (protiskuska oříznutého
    // písmene, světlý vnitřek tmavého obrysu).
    if (hasTextRegion) {
        val rl = textLeft - x0 - pad
        val rt = textTop - y0 - pad
        val rr = textRight - x0 + pad
        val rb = textBottom - y0 + pad
        // Zaplava doběhnutá na okraj domény uprostřed inkoustu = tah pokračuje dál -
        // dosah se zdvojí a zaplava se zopakuje, jinak by pahýl těsně za dosahem
        // Voronoi výplní "vlezl" zpátky do maskované části. Cap = výška textu (pahýl
        // delší než celý řádek je patologický).
        var reach = (pad + (textBottom - textTop) / INK_FLOOD_REACH_DIVISOR)
            .coerceIn(MIN_INK_FLOOD_REACH, MAX_INK_FLOOD_REACH)
        val reachCap = maxOf(textBottom - textTop, reach)
        while (true) {
            val clipped = floodClippedGlyphs(isText, luminance, w, h, rl, rt, rr, rb, reach)
            if (!clipped || reach >= reachCap) break
            reach = (reach * 2).coerceAtMost(reachCap)
        }
        dilate(isText, w, h, INK_FLOOD_DILATION)
        restrictToTextRegion(
            mask = isText, w = w, h = h,
            left = rl - reach, top = rt - reach, right = rr + reach, bottom = rb + reach,
            enabled = true,
        )
        sealEnclosedHoles(
            mask = isText, w = w, h = h,
            left = rl - reach, top = rt - reach, right = rr + reach, bottom = rb + reach,
            enabled = true,
        )
    }

    // Nemá se z čeho počítat (celá oblast vyšla jako text) - vrátí se navzorkované pozadí.
    // Lepší než nic a nikdy to nespadne.
    if (isText.all { it }) return IntArray(w * h) { bgArgb or OPAQUE }

    if (meanTextArgbOut != null && meanTextArgbOut.isNotEmpty()) {
        meanTextArgbOut[0] = coreTextArgb(pixels, luminance, isText)
    }

    // Raster/šrafura pod textem: Voronoi kopíruje NEJBLIŽŠÍ zdroj, takže tečkové pole
    // se do masky doplní bez rytmu (jedna tečka se roztáhne do skvrny). Když lokální
    // autokorelace pozná silnou periodu, maskované pixely se doplní z mřížkově
    // zarovnaných pozic - vzorek pokračuje ve správné fázi. Bez periodu nic nemění.
    continuePeriodicTexture(pixels, luminance, isText, w, h)
    fillNearestSource(pixels, isText, w, h)
    return pixels
}

/**
 * Pokračuje periodickou texturu (rastrové tečky, šrafování, výplň mřížky) přes
 * maskované pixely - viz volající v [buildTextPatch].
 *
 * 1) Autokorelace: pro každý posun (dx,dy) do [PERIOD_MAX] se změří, s jakou
 *    pravděpodobností mají dva nemaskované pixely posun vzdálené skoro stejný jas.
 *    Skórují se jen páry, kde aspoň jeden pixel odstupuje od dominantního pozadí -
 *    na řídké textuře by jinak vyhrál triviální posun (1,0), protože většina párů
 *    je pozadí->pozadí. Periodická textura má na své periodě skóre ~1; náhodná
 *    kresba žádný posun skórem nenosí.
 * 2) Dva nejkratší nezávislé posuny nad prahem (báze mřížky) = kandidáti na zdroj;
 *    nejkratší proto, že násobky periody skórují stejně a skutečná perioda je ta
 *    nejmenší.
 * 3) Maskovaný pixel se pokusí zkopírovat barvu z pozice p±k·v±m·w (rostoucí
 *    vzdálenost); nenajde-li nemaskovaný zdroj v mřížce, zůstane na Voronoi.
 *
 * Práh [PERIOD_MIN_SCORE] je schválně vysoký - falešná pozitiva (zarovnaný gradient,
 * obličej) by totéž udělala "hnojárek" ze vzorku, který tam není.
 */
private fun continuePeriodicTexture(
    pixels: IntArray,
    luminance: IntArray,
    isText: BooleanArray,
    w: Int,
    h: Int,
) {
    var masked = 0
    for (m in isText) if (m) masked++
    if (masked == 0) return

    // Dominantní jas podkladu (medián nemaskovaných pixelů). Skórují se jen páry
    // ukotvené v "objektu" - pixelu odlišném od pozadí. Bez ukotvení by na řídké
    // textuře (pár teček na bílé) vyhrály triviální posuny typu (1,0), protože
    // drtivá většina párů je pozadí->pozadí.
    val hist = IntArray(256)
    var sampled = 0
    for (i in pixels.indices) {
        if (isText[i]) continue
        hist[luminance[i]]++
        sampled++
    }
    if (sampled == 0) return
    var acc = 0
    var bgLum = 0
    while (bgLum < 255 && acc + hist[bgLum] <= sampled / 2) {
        acc += hist[bgLum]
        bgLum++
    }

    // Feature pixely (rastrové tečky, šrafy...) - plné rozlišení, žádné podvzorkování.
    // Podvzorkovaná mřížka by aliasovala: tečka 2px široká se ve vzorkování krokem 2
    // schová a sub-periodické posuny pak skórují falešně na 100 %.
    val feat = ArrayList<Int>(sampled / 4 + 1)
    for (i in pixels.indices) {
        if (!isText[i] && abs(luminance[i] - bgLum) > PERIOD_FEATURE_MARGIN) feat.add(i)
    }
    if (feat.size < PERIOD_MIN_PAIRS) return

    // Kandidátní posuny: dy>0 se všemi dx, plus dy=0 s dx>0 (opačná znaménka jsou
    // tentýž posun - kopírovací mřížka je symetrická).
    val offsets = ArrayList<Pair<Int, Int>>(PERIOD_MAX * PERIOD_MAX)
    for (dy in 0..PERIOD_MAX) {
        for (dx in -PERIOD_MAX..PERIOD_MAX) {
            if (dy == 0 && dx <= 0) continue
            if (dx == 0 && dy == 0) continue
            offsets.add(dx to dy)
        }
    }

    // Skóre posunu = podíl feature pixelů, jejichž posunutý protějšek má skoro stejný
    // jas. Cena je O(ofsady * #feature), feature pixelů je řádově méně než všech.
    val scored = ArrayList<Triple<Int, Int, Int>>(offsets.size)
    for ((dx, dy) in offsets) {
        var pairs = 0
        var match = 0
        for (i in feat) {
            val nx = i % w + dx
            val ny = i / w + dy
            if (nx !in 0 until w || ny !in 0 until h) continue
            val j = ny * w + nx
            if (isText[j]) continue
            pairs++
            if (abs(luminance[i] - luminance[j]) <= PERIOD_LUM_TOL) match++
        }
        if (pairs >= PERIOD_MIN_PAIRS) scored.add(Triple(dx, dy, match * 100 / pairs))
    }
    // Mezi dobře skórujícími posuny vyhraj nejkratší - skutečná perioda je nejmenší
    // periodický posun. Násobky periody skórují stejně, ale přeskakují zbytečně daleko.
    val good = scored.filter { it.third >= PERIOD_MIN_SCORE }
        .sortedBy { it.first * it.first + it.second * it.second }
    val first = good.firstOrNull() ?: return
    val v = first.first to first.second
    // Druhá báze: nejkratší dobře skórující posun NEKOLINEÁRNÍ s v (jinak by v i w
    // pokryly jen jednu osu a 2D mřížka by se protáhla do čar).
    val wOffset = good.firstOrNull { (dx, dy, _) ->
        v.first * dy - v.second * dx != 0
    }?.let { it.first to it.second }

    for (i in pixels.indices) {
        if (!isText[i]) continue
        val x = i % w
        val y = i / w
        // Pozice stejné fáze v mřížce: p±v, p±w, p±2v, p±2w, p±v±w - první nemaskovaná
        // dává barvu se správnou fází. Víc kombinací není třeba - zbytek dořeší Voronoi.
        for ((ox, oy) in PHASE_OFFSETS) {
            val nx = x + v.first * ox + (wOffset?.first ?: 0) * oy
            val ny = y + v.second * ox + (wOffset?.second ?: 0) * oy
            if (nx !in 0 until w || ny !in 0 until h) continue
            val n = ny * w + nx
            if (!isText[n]) {
                pixels[i] = pixels[n]
                // Odmaskovat - Voronoi výplň by jinak periodický výsledek přepsala
                // nejbližším zdrojem; nově reálný pixel navíc sám poslouží jako zdroj.
                isText[i] = false
                break
            }
        }
    }
}

/** Kombinace (násobek v, násobek w), kterými se hledá nemaskovaný zdroj stejné fáze. */
private val PHASE_OFFSETS = listOf(
    -1 to 0, 1 to 0, 0 to -1, 0 to 1,
    -2 to 0, 2 to 0, 0 to -2, 0 to 2,
    -1 to -1, 1 to -1, -1 to 1, 1 to 1,
    -2 to -1, 2 to -1, -2 to 1, 2 to 1,
    -1 to -2, 1 to -2, -1 to 2, 1 to 2,
)

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
 * Domaskuje inkoust glyphu OŘÍZNUTÉHO hranou textové oblasti - viz volající v
 * [buildTextPatch]. Bez toho pahýl tahu za hranicí regionu (mezi OCR boxem a krajem
 * záplaty) zůstane v obrázku jako viditelný zbytek originálního textu, protože
 * adaptivní práh ani seal za hranici regionu nesahají.
 *
 * Postup: mediana jasu NEMASKOVANÝCH pixelů regionu = referenční "pozadí" (inkoust
 * je menšina, i když maska sahá k hraně). Ze maskovaných pixelů na okrajovém kroužku
 * regionu se pak spustí BFS přes nemaskované pixely, které (a) leží na STEJNÉM pólu
 * jasu jako seed - a to O [INK_FLOOD_POLE_MARGIN] za mediánem pozadí, ne jen "tmavší/
 * světlejší": dilatace masku posouvá o dva pixely i přes pozadí, takže kroužkové pixely
 * bez marginu byly prostě pozadí a zaplava by se utrhla přes celé pole (kroková
 * tolerance sama pozadí stejnomé barvy nezastaví - mezi ním je nulový skok) - a (b)
 * nemění jas o víc než [INK_FLOOD_STEP_TOL] za krok - tj. pokračující tah inkoustu, ne
 * jiná kresba. Běží zvlášť pro tmavý a světlý pól (outlined glyph = tmavý obrys +
 * světlé jádro, každý se doleptává sám).
 *
 * @return `true`, když zaplava doběhla na okraj své domény uvnitř obrázku - tah tedy
 *   pravděpodobně pokračuje za dosahem a má smysl zkusit větší [reach].
 *
 * Bezpečnostní brzdy, proč se to neutrhne přes půl kresby:
 * - seedů je nutný dotyk masky s hranou regionu: běžný text s rezervou [pad] na
 *   hranu nesašáhne a funkce skončí prázdná,
 * - dosah je ohraničený obdélník region + [reach] (řádově jednotky-desítky px,
 *   škáluje s velikostí písma), nic mimo něj se nemaskovat nemůže,
 * - pól + kroková tolerance zastaví zaplavu na ostrém přechodu inkoust->kresba;
 *   když pozadí náhodou inkoustu jasově odpovídá (černý tah na černém panelu),
 *   zaplava sice stejnomá kousek pozadí sebere, ale Voronoi výplň ho nahradí tou
 *   samou barvou = neviditelné.
 */
private fun floodClippedGlyphs(
    mask: BooleanArray,
    luminance: IntArray,
    w: Int,
    h: Int,
    rl: Int,
    rt: Int,
    rr: Int,
    rb: Int,
    reach: Int,
): Boolean {
    val l = rl.coerceIn(0, w)
    val t = rt.coerceIn(0, h)
    val r = rr.coerceIn(l, w)
    val b = rb.coerceIn(t, h)
    if (r - l <= 0 || b - t <= 0) return false
    val el = (l - reach).coerceAtLeast(0)
    val et = (t - reach).coerceAtLeast(0)
    val er = (r + reach).coerceAtMost(w)
    val eb = (b + reach).coerceAtMost(h)

    // Referenční jas pozadí = medián nemaskovaných pixelů regionu.
    val hist = IntArray(256)
    var count = 0
    for (y in t until b) {
        for (x in l until r) {
            val i = y * w + x
            if (!mask[i]) {
                hist[luminance[i]]++
                count++
            }
        }
    }
    if (count == 0) return false
    var acc = 0
    var bgRef = 0
    val half = count / 2
    while (bgRef < 255 && acc + hist[bgRef] <= half) {
        acc += hist[bgRef]
        bgRef++
    }
    val darkLimit = bgRef - INK_FLOOD_POLE_MARGIN
    val lightLimit = bgRef + INK_FLOOD_POLE_MARGIN

    val queue = IntArray(w * h)
    var clipped = false
    for (lightPole in booleanArrayOf(false, true)) {
        var qs = 0
        var qe = 0
        fun seed(i: Int) {
            if (!mask[i]) return
            val onPole = if (lightPole) luminance[i] > lightLimit else luminance[i] < darkLimit
            if (onPole) queue[qe++] = i
        }
        for (x in l until r) {
            seed(t * w + x)
            seed((b - 1) * w + x)
        }
        for (y in t until b) {
            seed(y * w + l)
            seed(y * w + r - 1)
        }
        while (qs < qe) {
            val i = queue[qs++]
            val x = i % w
            val y = i / w
            val lum = luminance[i]
            fun visit(nx: Int, ny: Int) {
                if (nx < el || nx >= er || ny < et || ny >= eb) return
                val n = ny * w + nx
                if (mask[n]) return
                val nl = luminance[n]
                val onPole = if (lightPole) nl > lightLimit else nl < darkLimit
                if (!onPole || abs(nl - lum) > INK_FLOOD_STEP_TOL) return
                mask[n] = true
                queue[qe++] = n
                // Zaplavený pixel na okraji DOMÉNY (ne na kraji obrázku - tam tah
                // reálně končí) = tah za dosahem pravděpodobně pokračuje.
                if ((nx == el && el > 0) || (nx == er - 1 && er < w) ||
                    (ny == et && et > 0) || (ny == eb - 1 && eb < h)
                ) {
                    clipped = true
                }
            }
            visit(x - 1, y)
            visit(x + 1, y)
            visit(x, y - 1)
            visit(x, y + 1)
        }
    }
    return clipped
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

// -- Ink-flood za hranou textové oblasti (viz [floodClippedGlyphs]) ----------------------

/**
 * Maximální změna jasu mezi sousedními pixely, která ještě znamená "pokračující tah
 * inkoustu" při zaplavě za hranou regionu. Menší = unikne antialias lem oříznutého
 * písmene; větší = zaplava protéká do podobně laděné kresby.
 */
private const val INK_FLOOD_STEP_TOL = 36

/**
 * Kolik jasu za mediánem pozadí musí pixel mít, aby platil za inkoustový pól - pro
 * seedy i pro pokračování zaplavy. Bez marginu by maska rozšířená dilatací přes
 * POZADÍ na kroužku regionu seedovala zaplavu do celého pole (pozadí samo má jas
 * == medián, tedy leží "na pólu" na obě strany).
 */
private const val INK_FLOOD_POLE_MARGIN = 12

/**
 * Dosah zaplavy za hranici regionu: [pad] + osmina výšky textové oblasti, ohraničené.
 * Pahýl oříznutého tahu u OCR hrany bývá krátký; větší dosah jen navyšuje šanci,
 * že se při jasově shodném pozadí sebere i kousek kresby (i tak Voronoi-řešitelné).
 */
private const val INK_FLOOD_REACH_DIVISOR = 8
private const val MIN_INK_FLOOD_REACH = 6
private const val MAX_INK_FLOOD_REACH = 32

/** Jednopixelové rozšíření po zaplavě - doleptá antialias lem oříznutého tahu. */
private const val INK_FLOOD_DILATION = 1

// -- Periodická textura (viz [continuePeriodicTexture]) ---------------------------------

/**
 * Největší perioda rastru/šrafury, kterou autokorelace zkouší (px). Typický komiksový
 * raster je ~4-16 px podle rozlišení; delší periody by hledání jen zpomalovaly.
 */
private const val PERIOD_MAX = 16

/** Jasový souhlas dvou pixelů počítaných jako "stejná fáze" (šum/antialias rastru). */
private const val PERIOD_LUM_TOL = 14

/**
 * Odchylka od dominantního jasu pozadí, od které se pixel počítá jako "objekt"
 * (feature) - skórují se jen páry, kde aspoň jeden pixel objekt obsahuje.
 */
private const val PERIOD_FEATURE_MARGIN = 24

/**
 * Minimální skóre posunu (v %), aby se posun uznal za periodu - pod ní není jisté, že
 * jde o skutečný vzorek, a radši se nechá Voronoi než vysévat falešné tečky.
 */
private const val PERIOD_MIN_SCORE = 62

/** Minimální počet nemaskovaných párů pro smysluplné skóre posunu. */
private const val PERIOD_MIN_PAIRS = 60

/** Pojistka proti nekonečné smyčce; při ~2px za kolo pokryje i velmi tlusté tahy. */
private const val MAX_FILL_ROUNDS = 64

package com.haise.jiyu.translate

/**
 * Vypočtená pozice přeloženého bloku po expanzi směrem k okolnímu volnému
 * prostoru - viz [layoutTranslationBlocks].
 *
 * [minTopF]: horní hranice vykreslovaného boxu. Rovna [topF] pro jednořádkové bloky
 * (bez expanze nahoru - dřívější pokus tohle dělal i pro jednořádkové bloky podle
 * nejbližšího souseda, což riskovalo zbytečně vysoký box bez skutečného důvodu).
 * U bloků sloučených z víc OCR řádků (viz [TranslatedBlock.lineCount]) se posune nahoru
 * o odhad výšky jednoho řádku, protože ML Kit u víceřádkového stylizovaného písma
 * občas nahlásí boundingBox kratší, než je skutečná výška bubliny (chybí horní řádek) -
 * expanze je navíc omezená nejbližším sousedem nad blokem, aby nikdy nezasáhla cizí text.
 */
data class PositionedTranslationBlock(
    val block: TranslatedBlock,
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val maxBottomF: Float,
    val minTopF: Float = topF,
    /**
     * Pozice bloku ve VSTUPNÍM seznamu [layoutTranslationBlocks] - ne pozice v tomhle
     * výstupu. Výstup je přerovnaný (bloky s obrysem jdou před heuristické, viz
     * [layoutTranslationBlocks]), takže pozice v `positioned` NENÍ stabilní identita:
     * při ořezu okrajů (BubbleOverlayLayer) se layout počítá dvakrát na trochu jiné
     * geometrii a `dropDegenerateShape`/`mergeUntranslatedSiblingBlocks` můžou verdikt
     * překlapnout - stejný index ve dvou seznamech pak ukazuje na JINOU bublinu a
     * záplata/obnovený obrys přistanou na špatném místě (cílený audit F13). Klíčuj
     * vše, co se přenáší mezi dvěma layouty (záplaty, recovery, flip stav), přes
     * `sourceIndex` - ten je vůči přerovnání imunní, protože oba pre-passy
     * ([mergeUntranslatedSiblingBlocks], [dropDegenerateShape]) drží 1:1 pořadí vůči
     * vstupu. -1 = blok mimo [layoutTranslationBlocks] (ručně sestavené seznamy).
     */
    val sourceIndex: Int = -1,
)

/**
 * OCR bounding box je většinou příliš těsný na to, aby se do něj vešel český
 * překlad (bývá delší než originál) - bez úpravy text buď přeteče přes sousední
 * bublinu (žádný limit výšky), nebo se zalomí do zbytečně mnoha úzkých řádků
 * (šířka svázaná na originál). Tahle funkce každému bloku "půjčí" volný prostor
 * kolem sebe - vodorovně symetricky kolem středu původního textu až po půlku
 * mezery k nejbližšímu sousednímu bloku ve stejné "řadě", svisle dolů až k
 * nejbližšímu bloku pod sebou - aby renderer (viz AutoFitTranslatedText v
 * ReaderScreen.kt) měl s čím pracovat při volbě šířky/velikosti písma bez
 * kolize se sousedy.
 */
/**
 * Bloky se skutečně detekovaným tvarem bubliny (viz [BubbleShapeDetector]) použijí přímo
 * ohraničující obdélník tohohle tvaru - žádná heuristická expanze k sousedům/okrajům
 * stránky, protože už víme přesně, kde bublina končí. Bloky bez tvaru (detekce selhala,
 * nebo starý cache záznam ještě nedoběhl migrací) projdou beze změny starou heuristikou
 * ([layoutHeuristic]) - viz spec docs/superpowers/specs/2026-07-24-bubble-shape-and-font-design.md.
 */
fun layoutTranslationBlocks(blocks: List<TranslatedBlock>): List<PositionedTranslationBlock> {
    // isUntranslated sourozenec ve STEJNÉ bublině jako přeložený blok - rozšířit sourozence
    // přes jeho glyfy (audit Vagabondu: "MATA-HACHI'S BEEN" zůstalo EN vedle přeloženého
    // "PTÁM SE TĚ." v jedné bublině). Vrací seznam STEJNÉ délky - indexy v positioned
    // klíčují TextPatchProvider záplaty a přemapování by je rozbilo; absorbovaný blok se
    // jen nechá vykreslit jako dosud (renderer ho přeskočí přes bubbleSkipReason).
    val effectiveBlocks = mergeUntranslatedSiblingBlocks(blocks).map(::dropDegenerateShape)
    // index == pozice ve VSTUPNÍM `blocks` (oba pre-passy drží 1:1 pořadí) - nese se
    // do PositionedTranslationBlock.sourceIndex, viz jeho doc komentář proč.
    val shapeBased = effectiveBlocks.mapIndexedNotNull { i, b -> if (b.shape != null) IndexedValue(i, b) else null }
    val heuristicBased = effectiveBlocks.mapIndexedNotNull { i, b -> if (b.shape == null) IndexedValue(i, b) else null }

    val shapePositioned = resolveShapeOverlaps(
        shapeBased.map { (sourceIndex, b) ->
            val shape = b.shape!!
            PositionedTranslationBlock(
                block = b,
                leftF = shape.minOf { it.leftF },
                topF = shape.first().yF,
                rightF = shape.maxOf { it.rightF },
                maxBottomF = shape.last().yF,
                minTopF = shape.first().yF,
                sourceIndex = sourceIndex,
            )
        },
    )

    // TOC/seznam: mnoho bloku naskladanych pod sebou s prubeznym horizontalnim prekryvem.
    // V takovem layoutu je volna expanze bez souseda destruktivni (kazdy box se roztahne
    // do radky vedle) - vypne se jen no-neighbor fallback; expanze k realnym sousedum
    // pres midpoint mezer zustava, protoze ta nikdy kolizi nevyrobi.
    val denseList = isDenseListPage(heuristicBased.map { it.value }.filter { !it.isSfx })

    return shapePositioned + layoutHeuristic(heuristicBased, shapePositioned, denseList)
}

/** Kolik procent užšího z obou rectů se musí vodorovně překrývat, aby šlo o jednu bublinu. */
private const val SIBLING_MIN_HORIZONTAL_OVERLAP = 0.45f

/**
 * Obrys bubliny musí svůj text fyzicky obsahovat - pokud je obalový obdélník tvaru menší
 * než ~55 % OCR boxu textu v jednom rozměru, není to bublina, ale rozpadlý konturový
 * fragment (bod/čárka z flood-fillu). Audit Vagabondu ch.1: bloku "SOMEONE" detekce
 * přiřadila tvar o rozměru 0,05 % x 0,2 % stránky - vykreslený box i clip spadly na bod a
 * překlad "Někdo" byl na stránce mikroskopický/neviditelný, takže vypadal nepřeloženě.
 * Zahodit tvar = blok projde heuristikou a kreslí se přes záplatu jako lettering na kresbě.
 */
private const val SHAPE_MIN_TEXT_COVER = 0.55f

/**
 * Obrácený extrém [SHAPE_MIN_TEXT_COVER]: flood-fill "unikl" z bubliny do okolní
 * kresby - obrys je násobně větší než text a text nesedí uvnitř jeho středu, ale u
 * kraje. Audit RWS ch.215: NARRATION blok na tmavé scéně dostal obrys přes
 * 0..1 × 0.21-0.99 stránky (plocha ~6x větší než text, text nalepený nahoře) a
 * výplň oříznutá touhle konturou zakryla půlku obrázku místo bubliny; vepsaný
 * obdélník pak vysázel překlad doprostřed kresby, ne do bubliny. Takový tvar se
 * zahodí a blok přejde na heuristiku/záplatu přes vlastní OCR oblast.
 *
 * Legitimní velká bublina (výkřik v obraťáku, bublina s ocáskem) drží text zhruba
 * uprostřed - proto velikostní poměr ALONE nestačí, kombinuje se s decentrováním.
 */
private const val SHAPE_LEAK_AREA_RATIO = 4f
private const val SHAPE_LEAK_HEIGHT_RATIO = 2.5f
private const val SHAPE_LEAK_OFFCENTER_F = 0.22f

/**
 * Třetí rozměr "degenerate" vedle velikosti ([SHAPE_MIN_TEXT_COVER]) a leaku
 * ([SHAPE_LEAK_*]): obrys musí text obsahovat i v ŘÁDKOVÉM PROFILU, ne jen mít dost
 * velký obalový obdélník. Flood-fill se u lettering-na-kresbě občas chytí vedlejší
 * světlé plochy - audit RWS ch.215: nápis "FALLING DRAGON STRIKE" dostal za obrys
 * bílý VÍR vedle/za písmem. Bbox takového tvaru text "obsahuje" (poměrové kontroly
 * projde), ale jeho řádky na výšce textu jsou tenké/mimo - vepsaný obdélník pak
 * vyjde miniaturní, písmo se smrskne pod čitelnost a záplata oříznutá siluetou víru
 * zakryje jen nepatrný kousek - na stránce zůstane obří anglický nápis s drobným
 * českým popiskem. Zahozením tvaru blok přejde na lettering cestu (záplata přes
 * vlastní OCR oblast + sazba do boxu).
 *
 * Prah je měkký (řádek stačí pokrýt ~60 % šířky textu a stačí 70 % vzorků) - OCR box
 * bývá o chlup širší než skutečné glyfy a flood-fill končí uvnitř tahu obrysu, takže
 * požadovat 100 % by shazovalo i skutečné bubliny.
 */
private const val SHAPE_ROW_MIN_COVER = 0.6f
private const val SHAPE_ROW_SAMPLES = 7
private const val SHAPE_ROW_MIN_PASS_FRACTION = 0.7f

/**
 * Čtvrtý rozměr "degenerate" vedle velikosti/leaku/řádkového obsažení: uniklý PÁS.
 * Flood-fill může z bubliny prolétnout skulinou v obrysu do bílé mezery mezi panely
 * či slicy webtoonu - výsledný "tvar" je pak obdélník, jehož řádky sahají téměř k
 * OBĚMA okrajům stránky po celé výšce (všechny řádky stejně široké, žádná variace).
 * Audit RWS ch.215 p144: oválná bublina "WE GOTTA HURRY BACK TO PEACE!" dostala za
 * obrys bílou mezislicovou škvíru [0.024-0.975]x[0.156-0.577] - všech 24 řádků
 * identicky 0.951 širokých. Výplň oříznutá tímto pásem překryla obrys oválu tam, kde
 * se zužuje, a text stál na placatém pásu sahajícím přes kraj bubliny. Takový tvar
 * se zahodí - blok přejde na recovery (ta najde skutečný ovál) nebo záplatu.
 *
 * Výškový práh drží pryč typické narration stripy: tenké pruhy edge-to-edge jsou
 * legitimní popiskové rámečky a jejich obdélníkový obrys je správný. I kdyby se
 * vyšší narration box chytil, dopad je neškodný - skončí na záplatě ze skutečných
 * pixelů místo ploché výplně.
 */
private const val SHAPE_BAND_MIN_HEIGHT_F = 0.2f
private const val SHAPE_BAND_EDGE_MARGIN_F = 0.07f
private const val SHAPE_BAND_MIN_SPANNING_FRACTION = 0.8f

/** Jsou skoro všechny řádky tvaru přilepené na oba okraje stránky - viz konstanty výše. */
private fun shapeIsEdgeSpanningBand(shape: List<BubbleShapePoint>): Boolean {
    val shapeH = shape.last().yF - shape.first().yF
    if (shape.size < 2 || shapeH < SHAPE_BAND_MIN_HEIGHT_F) return false
    val spanning = shape.count { it.leftF <= SHAPE_BAND_EDGE_MARGIN_F && it.rightF >= 1f - SHAPE_BAND_EDGE_MARGIN_F }
    return spanning.toFloat() / shape.size >= SHAPE_BAND_MIN_SPANNING_FRACTION
}

/** Sedí řádky tvaru na řádky textu - viz [SHAPE_ROW_MIN_COVER] nad isDegenerateShapeForText. */
private fun shapeRowsContainText(shape: List<BubbleShapePoint>, b: TranslatedBlock): Boolean {
    val textW = b.rightF - b.leftF
    if (textW <= 0f || b.bottomF <= b.topF) return true
    var pass = 0
    for (s in 0 until SHAPE_ROW_SAMPLES) {
        val y = b.topF + (b.bottomF - b.topF) * (s + 0.5f) / SHAPE_ROW_SAMPLES
        val (l, r) = shapeBoundsAtYF(shape, y)
        if (minOf(r, b.rightF) - maxOf(l, b.leftF) >= textW * SHAPE_ROW_MIN_COVER) pass++
    }
    return pass.toFloat() / SHAPE_ROW_SAMPLES >= SHAPE_ROW_MIN_PASS_FRACTION
}

// internal (ne private): stejnou pojistku používá i TextPatchProvider na obrys
// znovunalezený při vykreslení - flood-fill tam může uniknout stejně jako při OCR
// a bez kontroly by se zahozený leak vrátil přes recoveredShape (viz audit p77).
internal fun isDegenerateShapeForText(shape: List<BubbleShapePoint>, b: TranslatedBlock): Boolean {
    if (shape.size < 2) return true
    val shapeW = shape.maxOf { it.rightF } - shape.minOf { it.leftF }
    val shapeH = shape.last().yF - shape.first().yF
    val textW = (b.rightF - b.leftF).coerceAtLeast(0f)
    val textH = (b.bottomF - b.topF).coerceAtLeast(0f)
    if (shapeW < textW * SHAPE_MIN_TEXT_COVER || shapeH < textH * SHAPE_MIN_TEXT_COVER) return true
    // Uniklý obrys: plocha mnohonásobně větší než text, hodně vysoký A text u kraje.
    if (shapeW * shapeH > textW * textH * SHAPE_LEAK_AREA_RATIO && shapeH > textH * SHAPE_LEAK_HEIGHT_RATIO) {
        val shapeMid = (shape.first().yF + shape.last().yF) / 2f
        val textMid = (b.topF + b.bottomF) / 2f
        if (kotlin.math.abs(textMid - shapeMid) > shapeH * SHAPE_LEAK_OFFCENTER_F) return true
    }
    // Řádkové obsažení i edge-band kontrola jen pro bloky, které se opravdu vykreslují - u
    // seamCover/SFX/nepřeložených/art se tvar na čtenáře neprojeví a jeho zahození by
    // zbytečně měnilo layout/clip krytí fragmentů na řezu.
    if (!b.seamCover && !b.isSfx && !b.isUntranslated && !b.isArtText) {
        if (!shapeRowsContainText(shape, b)) return true
        if (shapeIsEdgeSpanningBand(shape)) return true
    }
    return false
}

private fun dropDegenerateShape(b: TranslatedBlock): TranslatedBlock {
    val shape = b.shape ?: return b
    return if (isDegenerateShapeForText(shape, b)) b.copy(shape = null) else b
}

/** Svislá mezera mezi půlkami jedné bubliny v násobcích řádkové výšky sourozence. */
private const val SIBLING_MAX_GAP_LINES = 1.5f

/**
 * Předstupňový pass [layoutTranslationBlocks]: blok s `isUntranslated` (model vrátil
 * UNTRANSLATED_MARKER - nečitelné OCR, selhání poskytovatele) nechává prosvítat anglický
 * originál. Když takový blok leží ve STEJNÉ fyzické bublině jako přeložený sourozenec
 * (audit Vagabondu: "MATA-HACHI'S BEEN" EN vedle přeloženého "PTÁM SE TĚ."), rozšíří
 * geometrii sourozence přes jeho rect - maska pak zakryje i původní anglické glyfy a
 * bublina vizuálně zůstane jedna, ne půl-EN/půl-CS.
 *
 * "Stejná bublina" = silný vodorovný překryv (≥ [SIBLING_MIN_HORIZONTAL_OVERLAP] užšího)
 * A svislá mezera do ~[SIBLING_MAX_GAP_LINES] řádků, NEBO střed bloku uvnitř sourodcova
 * detekovaného tvaru. Prah je záměrně přísnější než u `detectContinuations` (0,15) - tam
 * se sdílí jen kontext promptu, tady se fyzicky prodlužuje maska.
 *
 * Seznam má na výstupu STEJNOU délku jako na vstupu (absorbovaný blok zůstává - renderer
 * ho přeskočí) - indexy klíčují záplaty v TextPatchProvider, rozbily by se posunem.
 */
internal fun mergeUntranslatedSiblingBlocks(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
    fun TranslatedBlock.isAbsorbableFragment(): Boolean =
        // Čistě interpunkční SFX ("!", "…") se neabsorbuje - samotný vykřičník může
        // být legitimní dramatická bublina a překrytí by ho zahodilo; jen útržky
        // s písmenem ("F", "S") jsou odřezek sazby, co do sousední masky patří.
        // seamCover fragment se NIKDY neabsorbuje - jeho území kryje vlastní záplata
        // (coverOnly režim) a absorpcí by se maska souseda natáhla přes šev navíc,
        // takže by se tentýž řez překryl dvakrát (audit F13).
        !seamCover && (isUntranslated || (isSfx && shape == null && originalText.count { it.isLetter() } in 1..2))
    if (blocks.none { it.isAbsorbableFragment() }) return blocks

    val absorbedInto = mutableMapOf<Int, Int>()
    for (ui in blocks.indices) {
        val u = blocks[ui]
        // Vedle untranslated bloků se absorbují i mikro-SFX fragmenty (<=2 písmena bez
        // vlastního tvaru) - odtržené písmeno sazby ("F" od FORGIVE ME) se má rozpustit
        // v masce sousední přeložené bubliny, ne viset vedle ní na kresbě.
        if (!u.isAbsorbableFragment()) continue
        var bestTi = -1
        var bestRatio = 0f
        for (ti in blocks.indices) {
            if (ti == ui) continue
            val t = blocks[ti]
            // seamCover fragment nikdy neni cilem - jeho box je kryci rezim nad vlastnim
            // OCR rozsahem (viz TranslationOverlay coverOnly); absorbovany sourozenec
            // by ho roztahl a zaplata by zakryla kus kresby navic.
            if (t.isSfx || t.isUntranslated || t.seamCover) continue
            if (centerInsideShapeRow(u, t)) { bestTi = ti; break }
            val overlapX = minOf(u.rightF, t.rightF) - maxOf(u.leftF, t.leftF)
            val narrowerW = minOf(u.rightF - u.leftF, t.rightF - t.leftF)
            if (narrowerW <= 0f) continue
            val ratio = overlapX / narrowerW
            if (ratio < SIBLING_MIN_HORIZONTAL_OVERLAP) continue
            val tLine = (t.bottomF - t.topF) / t.lineCount.coerceAtLeast(1)
            val gap = maxOf(0f, maxOf(u.topF, t.topF) - minOf(u.bottomF, t.bottomF))
            if (gap > tLine * SIBLING_MAX_GAP_LINES) continue
            if (ratio > bestRatio) { bestTi = ti; bestRatio = ratio }
        }
        if (bestTi >= 0) absorbedInto[ui] = bestTi
    }
    if (absorbedInto.isEmpty()) return blocks

    val out = blocks.toMutableList()
    for ((ui, ti) in absorbedInto) {
        val u = blocks[ui]
        val t = out[ti]
        out[ti] = t.copy(
            leftF = minOf(t.leftF, u.leftF),
            topF = minOf(t.topF, u.topF),
            rightF = maxOf(t.rightF, u.rightF),
            bottomF = maxOf(t.bottomF, u.bottomF),
            shape = t.shape?.let { unionShapeWithRect(it, u) },
        )
    }
    return out
}

/** Leží střed [u] uvnitř řádkového profilu tvaru [t]? Přesnější "ta samá bublina" než bbox. */
private fun centerInsideShapeRow(u: TranslatedBlock, t: TranslatedBlock): Boolean {
    val shape = t.shape ?: return false
    val cx = (u.leftF + u.rightF) / 2f
    val cy = (u.topF + u.bottomF) / 2f
    val row = shape.minByOrNull { kotlin.math.abs(it.yF - cy) } ?: return false
    return cx >= row.leftF && cx <= row.rightF
}

/**
 * Union tvaru (řádkový profil BubbleShapePoint) s obdélníkem [rect]: po jeho výšce se
 * nasampluje 9 řádků a každý se buď připojí jako nový řádek profilu, nebo rozšíří
 * existující - výsledný obalový box i výplň tak pokryjí i glyphy absorbovaného bloku.
 */
private fun unionShapeWithRect(shape: List<BubbleShapePoint>, rect: TranslatedBlock): List<BubbleShapePoint> {
    val rows = shape.toMutableList()
    val samples = 8
    for (s in 0..samples) {
        val y = rect.topF + (rect.bottomF - rect.topF) * s / samples
        val idx = rows.indexOfFirst { kotlin.math.abs(it.yF - y) < 0.002f }
        if (idx >= 0) {
            val r = rows[idx]
            rows[idx] = r.copy(leftF = minOf(r.leftF, rect.leftF), rightF = maxOf(r.rightF, rect.rightF))
        } else {
            rows += BubbleShapePoint(yF = y, leftF = rect.leftF, rightF = rect.rightF)
        }
    }
    return rows.sortedBy { it.yF }
}

/** Ohraničující obdélník - společný tvar pro sousedy z [layoutHeuristic] i z pevných tvarových bublin. */
private data class NeighborRect(val leftF: Float, val topF: Float, val rightF: Float, val bottomF: Float)

private fun TranslatedBlock.toRect() = NeighborRect(leftF, topF, rightF, bottomF)

/** Vykreslovaný (ne obalový) obdélník tvarové bubliny - přesně to, co [layoutHeuristic] nesmí přejet. */
private fun PositionedTranslationBlock.toObstacleRect() = NeighborRect(leftF, minTopF, rightF, maxBottomF)

/**
 * @param shapeObstacles bubliny s detekovaným tvarem (viz [layoutTranslationBlocks]) - jejich box
 *   je přesný a NIKDY se nezmenšuje, jen heuristické bloky kolem nich musí "obcházet". Dřív o
 *   nich tahle funkce vůbec nevěděla (sousedství se hledalo jen mezi bloky BEZ tvaru), takže
 *   heuristický box klidně expandoval skrz sousední tvarovou bublinu i kresbu za ní - viz
 *   uživatelská zpětná vazba (bílý pruh z "NO TAK." přes sousední bublinu).
 */
private const val NEARBY_PROXIMITY_F = 0.005f

/** Kolik bloku se musi naskladat do "radku", aby stranka platila za listovy layout (TOC). */
private const val DENSE_LIST_MIN_BLOCKS = 6

/** Kolik procent sousednich paru musi horizontalne prekryvat, aby platil listovy rezim. */
private const val DENSE_LIST_ALIGNED_FRACTION = 0.6f

/** Kolik pruchodu shape-vs-shape kolizniho reseni - viz [resolveShapeOverlaps]. */
private const val SHAPE_COLLISION_PASSES = 3

/**
 * Dva bloky s detekovanym tvarem se muzou prekryvat (flood-fill obrys je sirokorysejsi nez
 * text uvnitr, dve bubliny se u sebe dotykaji, nebo detekce spojila kus pozadi navic) -
 * driv se takove dvojice vykreslily pres sebe doslova (audit TOC stranky Vagabondu:
 * neprehledny shluk prekrytych boxu). Reseni: sdilena hranice v PULI mezery mezi textovymi
 * recty - shape boxy se smi zmensit jen do vlastniho OCR textu, nikdy pod nej, takze oba
 * preklady zustanou citelne a kryjou cely svuj glyph region. Kdyz se textove recty samotne
 * prekryvaji (nepravda skoro nikdy - pak detekce udelala z dvou bublin jednu), dvojici
 * nechame byt: zmensit box pod glyphy by zakrylo puvodni pismo jeste pred vykreslenim.
 */
private fun resolveShapeOverlaps(
    positioned: List<PositionedTranslationBlock>,
): List<PositionedTranslationBlock> {
    val result = positioned.toMutableList()
    repeat(SHAPE_COLLISION_PASSES) {
        var changed = false
        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                val a = result[i]; val b = result[j]
                val overlapX = minOf(a.rightF, b.rightF) - maxOf(a.leftF, b.leftF)
                val overlapY = minOf(a.maxBottomF, b.maxBottomF) - maxOf(a.minTopF, b.minTopF)
                if (overlapX <= 0f || overlapY <= 0f) continue

                val resolved = splitShapePair(a, b) ?: continue
                result[i] = resolved.first
                result[j] = resolved.second
                changed = true
            }
        }
        if (!changed) return result
    }
    return result
}

/** Svisly split preferujeme (bubliny v mange se typicky radeji pod sebe); horizontalni jen jako fallback. */
private fun splitShapePair(
    a: PositionedTranslationBlock,
    b: PositionedTranslationBlock,
): Pair<PositionedTranslationBlock, PositionedTranslationBlock>? {
    val upper = if (a.block.topF <= b.block.topF) a else b
    val lower = if (a.block.topF <= b.block.topF) b else a
    if (upper.block.bottomF <= lower.block.topF) {
        val boundary = (upper.block.bottomF + lower.block.topF) / 2f
        val newUpper = upper.copy(maxBottomF = upper.maxBottomF.coerceAtMost(boundary))
        val newLower = lower.copy(minTopF = lower.minTopF.coerceAtLeast(boundary))
        if (newUpper.maxBottomF < upper.maxBottomF || newLower.minTopF > lower.minTopF) {
            return if (upper === a) newUpper to newLower else newLower to newUpper
        }
    }
    val left = if (a.block.leftF <= b.block.leftF) a else b
    val right = if (a.block.leftF <= b.block.leftF) b else a
    if (left.block.rightF <= right.block.leftF) {
        val boundary = (left.block.rightF + right.block.leftF) / 2f
        val newLeft = left.copy(rightF = left.rightF.coerceAtMost(boundary))
        val newRight = right.copy(leftF = right.leftF.coerceAtLeast(boundary))
        if (newLeft.rightF < left.rightF || newRight.leftF > right.leftF) {
            return if (left === a) newLeft to newRight else newRight to newLeft
        }
    }
    return null
}

/**
 * Pravda, kdyz heuristicke bloky tvori husty "seznam" (obsahove stranky, TOC): serazene podle
 * topF maji po dvou silny horizontalni prekryv. Meri se jen ne-SFX bloky - stranky plne SFX
 * hexagonu nejsou seznam. Na normalni manga strance bubliny skacou vodorovne i svisle, takze
 * podminka se temer nikdy netrefi.
 */
internal fun isDenseListPage(blocks: List<TranslatedBlock>): Boolean {
    if (blocks.size < DENSE_LIST_MIN_BLOCKS) return false
    val sorted = blocks.sortedBy { it.topF }
    var aligned = 0
    for (i in 1 until sorted.size) {
        val prev = sorted[i - 1]; val cur = sorted[i]
        val overlapX = minOf(prev.rightF, cur.rightF) - maxOf(prev.leftF, cur.leftF)
        val narrower = minOf(prev.rightF - prev.leftF, cur.rightF - cur.leftF).coerceAtLeast(0.0001f)
        if (overlapX / narrower > 0.5f) aligned++
    }
    return aligned >= (sorted.size - 1) * DENSE_LIST_ALIGNED_FRACTION
}

/** Viz komentář u výpočtu `maxBottom` v [layoutHeuristic] - nezávislý strop na to, jak daleko
 * smí box expandovat SMĚREM K SOUSEDOVI, i když je skutečný soused dál. Řádově stejné jako
 * existující 3x šířkový strop. */
private const val NEIGHBOR_DISTANCE_CAP_MULTIPLIER = 6f

private fun layoutHeuristic(
    blocks: List<IndexedValue<TranslatedBlock>>,
    shapeObstacles: List<PositionedTranslationBlock> = emptyList(),
    denseList: Boolean = false,
): List<PositionedTranslationBlock> {
    fun verticallyOverlaps(a: NeighborRect, b: NeighborRect) =
        a.topF < b.bottomF + NEARBY_PROXIMITY_F && a.bottomF > b.topF - NEARBY_PROXIMITY_F

    val obstacleRects = shapeObstacles.map { it.toObstacleRect() }

    val positioned = blocks.map { (sourceIndex, b) ->
        val bRect = b.toRect()
        // Sourozenci = stejná vstupní množina - porovnává se přes sourceIndex
        // (identity bloků), ne přes `!==` na IndexedValue obálky.
        val peerRects = blocks.filter { it.index != sourceIndex }.map { it.value.toRect() } + obstacleRects

        // SFX blok se sam nevykresluje - nema smysl mu expandovat (nevyditelny box by jen
        // zbytecne tiskl sousedni dialogove boxy na stranu). Drzi vlastni OCR rect a slouzi
        // ciste jako prekazka v peerRects ostatnich.
        if (b.isSfx) {
            return@map PositionedTranslationBlock(
                block = b,
                leftF = b.leftF,
                topF = b.topF,
                rightF = b.rightF,
                maxBottomF = b.bottomF,
                minTopF = b.topF,
                sourceIndex = sourceIndex,
            )
        }

        val leftNeighbor = peerRects.filter { verticallyOverlaps(it, bRect) && it.rightF <= b.leftF + 0.001f }
            .maxByOrNull { it.rightF }
        val rightNeighbor = peerRects.filter { verticallyOverlaps(it, bRect) && it.leftF >= b.rightF - 0.001f }
            .minByOrNull { it.leftF }

        // Bez souseda by expandLimit spadl na 0f/1f (okraj celé stránky) - box teď fyzicky
        // vyplňuje celý vypočtený prostor (viz ReaderScreen.kt .heightIn/.width), takže
        // "žádný soused = roztáhni se přes půl stránky" už není neškodné, ale viditelná chyba.
        // Strop 3x vlastní OCR rozměr dá dost místa na kompresi překladu - ALE jen u
        // rovnoměrného pozadí (skutečná bublina, jen se jí nepodařilo najít uzavřený tvar).
        // U nerovnoměrného pozadí (titulkový/dekorativní text přímo přes kresbu, viz
        // OcrEngine.isColorUniform) je box beztak jen barevná placka, co nikdy nesplyne s
        // pestrým okolím - roztahovat ji 3x by zbytečně zakrylo mnohem víc kresby, než kolik
        // zabíral původní text (viz uživatelská zpětná vazba - hnědá placka přes titulní stránku).
        // Platí i bez souseda (izolovaný odznak/praporek) - riziko "zakrytí barevné kresby"
        // je stejné, ať už má blok souseda, nebo ne (viz BubbleTextFit.DEFAULT_MAX_ITERATIONS
        // pro řešení namačkaného textu jinou, bezpečnější cestou - přes fitter, ne přes
        // rozšiřování boxu do kresby).
        // U uniformního pozadí bez tvaru se už nekreslí plochá výplň, ale záplata ze
        // skutečných pixelů (viz patchPlan) - čím menší box, tím menší bitmapa a tím míň
        // se text může rozšířit přes okraj skutečné bubliny. 1.6x hrubě odpovídá poměru
        // "bublina vs text v ní" a výplň-padací fallback pak přeteče jen nepatrně.
        // Na listovych strankach (TOC - viz isDenseListPage) navic volna expanze bez souseda
        // znamenala rozlezeni radkoveho textu pres sousedni radky = auditovana katastrofa;
        // faktor 0 necha jen vlastni rect (midpoint expanze k realnym sousedum jede dal).
        val expandFactor = if (denseList) 0f else if (b.bgUniform) 1.6f else 1.15f
        val ownWidth = b.rightF - b.leftF
        val expandLimitLeft = leftNeighbor?.let { (b.leftF + it.rightF) / 2f } ?: (b.leftF - ownWidth * expandFactor).coerceAtLeast(0f)
        val expandLimitRight = rightNeighbor?.let { (b.rightF + it.leftF) / 2f } ?: (b.rightF + ownWidth * expandFactor).coerceAtMost(1f)

        // Symetrická expanze kolem středu originálu - vizuálně stabilnější než nezávislé
        // roztažení každou stranou zvlášť (bublina pak "nesedí" mimo střed originálu).
        val center = (b.leftF + b.rightF) / 2f
        val halfWidth = minOf(
            center - expandLimitLeft,
            expandLimitRight - center,
            ownWidth * expandFactor / 2f,
        ).coerceAtLeast(ownWidth / 2f)
        val finalLeft = (center - halfWidth).coerceIn(0f, b.leftF)
        val finalRight = (center + halfWidth).coerceIn(b.rightF, 1f)

        fun horizontallyOverlaps(o: NeighborRect) =
            o.leftF < finalRight + NEARBY_PROXIMITY_F && o.rightF > finalLeft - NEARBY_PROXIMITY_F
        val belowNeighbor = peerRects.filter { horizontallyOverlaps(it) && it.topF >= b.bottomF - 0.001f }
            .minByOrNull { it.topF }
        // Strop odvozený z výšky JEDNOHO řádku (ne z celé výšky bloku) - u bloku sloučeného
        // z 5 OCR řádků by "3x vlastní výška" znamenalo 15 řádků volného místa, což je
        // přesně to, co způsobilo box přetékající přes zbytek stránky až za sousední SFX.
        // Stejný důvod jako u expandFactor výše - nerovnoměrné pozadí dostává jen minimální
        // rezervu, ne plných 2 řádky navíc. U uniformního bez tvaru 1,25 řádku: větší
        // rezerva jen nafukovala záplatu/box přes okraj skutečné bubliny (uživatelská
        // zpětná vazba - výplň přes kraj bubliny); český text místo toho dostane menší písmo.
        val avgLineHeightForCap = (b.bottomF - b.topF) / b.lineCount.coerceAtLeast(1)
        val verticalExpandFactor = if (b.bgUniform) 1.25f else 0.5f
        // Bez souseda mame prirozeny strop (avgLineHeightForCap * verticalExpandFactor), ale
        // KDYZ soused existuje, puvodni kod expandoval AZ K NEMU bez ohledu na vzdalenost -
        // u male SFX bubliny (napr. "GULP GULP" osamocene v panelu) s dalsim blokem daleko
        // dole to znamenalo box pres pulku panelu (nahlaseno v auditu). Nezavisly strop:
        // nikdy vic nez NEIGHBOR_DISTANCE_CAP_MULTIPLIER-nasobek vlastni vysky bloku, i kdyz
        // je soused dal - stejny princip jako uz ma sirka (ownWidth*expandFactor je vzdy
        // jeden z minOf(...) kandidatu pri vypoctu halfWidth vyse).
        val ownHeightForCap = (b.bottomF - b.topF).coerceAtLeast(0.001f)
        val neighborDistanceCap = b.bottomF + ownHeightForCap * NEIGHBOR_DISTANCE_CAP_MULTIPLIER
        val maxBottom = (belowNeighbor?.let { minOf(it.topF - 0.005f, neighborDistanceCap) } ?: (b.bottomF + avgLineHeightForCap * verticalExpandFactor))
            .coerceAtLeast(b.bottomF).coerceIn(0f, 1f)

        // Jen víceřádkové bloky (viz doc komentář [PositionedTranslationBlock.minTopF]) -
        // jednořádkový box se nikdy neroztáhne nahoru, i kdyby měl nad sebou volný prostor.
        val minTop = if (b.lineCount > 1) {
            val aboveNeighbor = peerRects.filter { horizontallyOverlaps(it) && it.bottomF <= b.topF + 0.001f }
                .maxByOrNull { it.bottomF }
            val expandLimitTop = aboveNeighbor?.let { (b.topF + it.bottomF) / 2f } ?: 0f
            val avgLineHeight = (b.bottomF - b.topF) / b.lineCount
            (b.topF - avgLineHeight * 0.6f).coerceAtLeast(expandLimitTop).coerceIn(0f, b.topF)
        } else {
            b.topF
        }

        PositionedTranslationBlock(
            block = b,
            leftF = finalLeft,
            topF = b.topF,
            rightF = finalRight,
            maxBottomF = maxBottom,
            minTopF = minTop,
            sourceIndex = sourceIndex,
        )
    }.toMutableList()

    // Řádková heuristika výše nezachytí diagonálně sousedící bloky (jeden začíná výš,
    // ale je posunutý vpravo mimo "stejnou řadu") - po prvotní expanzi ještě projdeme
    // všechny dvojice a případný přesah zmenšíme, přednostně svisle (zkrácením
    // maxBottomF horního bloku), a teprve když by to zmenšilo box pod jeho původní OCR
    // rozměr, vodorovně (posunutím sdílené hranice na střed přesahu). Iterujeme do
    // konvergence (max 4 průchody): pevné 2 kolá dřív na hustych strankach (TOC)
    // zanechavala zbytkove prekryvy - jeden pass vyresi par A/B a zaroven muze zpusobit
    // novy prekryv proti C.
    var collisionPasses = 0
    while (collisionPasses < 4) {
        collisionPasses++
        var changed = false
        for (i in positioned.indices) {
            for (j in positioned.indices) {
                if (i == j) continue
                val a = positioned[i]; val b = positioned[j]
                val overlapX = minOf(a.rightF, b.rightF) - maxOf(a.leftF, b.leftF)
                val overlapY = minOf(a.maxBottomF, b.maxBottomF) - maxOf(a.minTopF, b.minTopF)
                if (overlapX <= 0f || overlapY <= 0f) continue
                changed = true

                val upperIdx = if (a.minTopF <= b.minTopF) i else j
                val lowerIdx = if (a.minTopF <= b.minTopF) j else i
                val upper = positioned[upperIdx]
                val lower = positioned[lowerIdx]
                val shrunkBottom = lower.minTopF - 0.003f

                if (shrunkBottom >= upper.block.bottomF && shrunkBottom < upper.maxBottomF) {
                    positioned[upperIdx] = upper.copy(maxBottomF = shrunkBottom)
                } else {
                    val leftIdx = if (a.leftF <= b.leftF) i else j
                    val rightIdx = if (a.leftF <= b.leftF) j else i
                    val leftB = positioned[leftIdx]
                    val rightB = positioned[rightIdx]
                    val split = (leftB.rightF + rightB.leftF) / 2f
                    positioned[leftIdx] = leftB.copy(rightF = split.coerceAtLeast(leftB.block.rightF))
                    positioned[rightIdx] = rightB.copy(leftF = split.coerceAtMost(rightB.block.leftF))
                }
            }
        }
        if (!changed) break
    }

    // Stejná oprava jako výše, ale proti tvarovým bublinám (viz [shapeObstacles]) - ty se
    // NIKDY nezmenšují (jejich obrys je přesný z flood-fillu), takže ustupuje jen heuristický
    // box, a jen na tolik, kolik dovolí jeho vlastní OCR rozsah (aby si nezakryl vlastní text).
    for (idx in positioned.indices) {
        var a = positioned[idx]
        for (obstacle in obstacleRects) {
            val overlapX = minOf(a.rightF, obstacle.rightF) - maxOf(a.leftF, obstacle.leftF)
            val overlapY = minOf(a.maxBottomF, obstacle.bottomF) - maxOf(a.minTopF, obstacle.topF)
            if (overlapX <= 0f || overlapY <= 0f) continue

            val aIsAbove = a.minTopF <= obstacle.topF
            val shrunkBottom = obstacle.topF - 0.003f
            val shrunkTop = obstacle.bottomF + 0.003f
            a = when {
                aIsAbove && shrunkBottom >= a.block.bottomF -> a.copy(maxBottomF = shrunkBottom)
                !aIsAbove && shrunkTop <= a.block.topF -> a.copy(minTopF = shrunkTop)
                a.leftF <= obstacle.leftF -> a.copy(rightF = ((a.rightF + obstacle.leftF) / 2f).coerceAtLeast(a.block.rightF).coerceAtMost(a.rightF))
                else -> a.copy(leftF = ((a.leftF + obstacle.rightF) / 2f).coerceAtMost(a.block.leftF).coerceAtLeast(a.leftF))
            }
        }
        positioned[idx] = a
    }

    return positioned
}

package com.haise.jiyu.translate

/**
 * Slučování OCR řádků do bublinových bloků - extrahováno z [OcrEngine], aby šlo testovat čistě
 * (bez Bitmap/Androidu), stejný důvod jako [ReadingOrder]/[BubbleShapeAnalysis].
 *
 * Bez [hasWallBetween] vetovalo sloučení pouze geometrii ([shouldMerge]) - dvě GEOMETRICKY
 * blízké, ale VIZUÁLNĚ oddělené bubliny/captions (jiná bublina vedle, jiný barevný box na
 * stránce s reklamou) se tak sloučily do jednoho bloku: jeden zmizel beze zbytku (viz uživatelská
 * zpětná vazba - "HOW DID YOU MANAGE..." bublina úplně chyběla) a druhý na stránce s reklamou
 * na anime vznikl jako jedna přebujelá barevná placka přes půl stránky. [hasWallBetween] tomu
 * brání kontrolou pixelů MEZI kandidáty - přes jednu bublinu jde vždycky rovná čára stejné
 * barvy pozadí, mezi dvěma RŮZNÝMI bublinami/boxy je vždycky někde obrys nebo jiná barva.
 */

/**
 * Řádky stejné bubliny mívají mezeru mnohem menší než výška písma; mezi bublinami bývá mezera
 * srovnatelná s výškou písma nebo větší. Čistě geometrický odhad - žádná záruka, proto
 * [hasWallBetween] jako druhá, vizuální pojistka v [mergeNearbyLines].
 *
 * Svislý práh je záměrně shovívavý (2,6x prům. výšky, ne jen o málo přes 1x) - komiksový
 * lettering běžně sází PRVNÍ SLOVO repliky větším/tučným písmem kvůli důrazu ("WHY DON'T you
 * ask me anything?"), a zbytek věty normální velikostí o kus níž ve STEJNÉ bublině. Naměřeno
 * na nahlášené stránce (Vagabond kap. 2, str. 10): mezera mezi "WH" (0,54-0,548) a zbytkem věty
 * (0,571-0,60) byla 2,4x průměr obou výšek - původní práh (0,9x) ji odmítl, "WH" osamocené
 * navíc spustilo SFX heuristiku ([BubbleClassifier.detectSfx] - krátký text bez mezery na
 * nerovném pozadí), takže bublina vyšla napůl anglicky, napůl česky. Bezpečnost proti sloučení
 * doopravdy ROZDÍLNÝCH bublin drží [hasWallBetween], ne tenhle geometrický odhad - viz jeho
 * doc komentář.
 */
internal fun shouldMerge(a: RawTextBlock, b: RawTextBlock): Boolean {
    // Svisle sázená japonština je jiný svět: ML Kit vrací celý SLOUPEC jako jeden "řádek",
    // takže vodorovné pravidlo níž by porovnávalo mezeru mezi sloupci s výškou sloupce.
    // Naměřeno na zařízení (VerticalJapaneseOnDeviceTest): dvě bubliny 350 px od sebe se
    // slily do jednoho bloku, protože 1,8x výška sloupce je přes půl stránky. Sloupce mají
    // vlastní, zrcadlově otočené pravidlo.
    if (a.isVertical != b.isVertical) return false
    if (a.isVertical) return shouldMergeVerticalColumns(a, b)

    val avgHeight = ((a.bottomF - a.topF) + (b.bottomF - b.topF)) / 2f
    if (avgHeight <= 0f) return false

    val verticalGap = maxOf(0f, maxOf(a.topF, b.topF) - minOf(a.bottomF, b.bottomF))
    val horizontalOverlap = minOf(a.rightF, b.rightF) - maxOf(a.leftF, b.leftF)
    val horizontalGap = maxOf(0f, maxOf(a.leftF, b.leftF) - minOf(a.rightF, b.rightF))

    return verticalGap < avgHeight * VERTICAL_GAP_TOLERANCE &&
        (horizontalOverlap > 0f || horizontalGap < avgHeight * 1.8f)
}

/** Viz doc komentář [shouldMerge] - kryje i mezeru za tučným/zvětšeným zdůrazněným slovem. */
private const val VERTICAL_GAP_TOLERANCE = 2.6f

/**
 * Totéž co [shouldMerge], ale s prohozenými osami: u sloupce hraje roli měřítka jeho ŠÍŘKA,
 * ne výška, a sousední sloupce téže bubliny stojí vedle sebe místo pod sebou.
 *
 * Ověřeno proti naměřeným boxům (VerticalJapaneseOnDeviceTest, sloupce široké ~50 px):
 * sousední sloupce jedné bubliny měly mezeru 26-31 px a sloučí se; sloupce dvou různých
 * bublin měly 350 px a nesloučí se. Dřív se slily všechny čtyři do jednoho bloku.
 */
private fun shouldMergeVerticalColumns(a: RawTextBlock, b: RawTextBlock): Boolean {
    val avgWidth = ((a.rightF - a.leftF) + (b.rightF - b.leftF)) / 2f
    if (avgWidth <= 0f) return false

    val horizontalGap = maxOf(0f, maxOf(a.leftF, b.leftF) - minOf(a.rightF, b.rightF))
    val verticalOverlap = minOf(a.bottomF, b.bottomF) - maxOf(a.topF, b.topF)
    val verticalGap = maxOf(0f, maxOf(a.topF, b.topF) - minOf(a.bottomF, b.bottomF))

    return horizontalGap < avgWidth * 0.9f && (verticalOverlap > 0f || verticalGap < avgWidth * 1.8f)
}

/**
 * Nad touhle mezí poměru nejvyšší/nejnižší výšky řádku ve sloučené skupině už nejde o
 * běžné zvýraznění prvního slova věty, ale o vizuálně ODLIŠNÁ POLE - typicky herní/systémový
 * "stat box" (popisek/jméno/podtitul, např. "God's Legion Support" / "Lucian" / "L-Rank
 * Stellar-Commander"). Takové řádky se spojovaly MEZEROU do jedné prosaické věty, která pak
 * přetekla přes box a ztratila vizuální hierarchii (nahlášeno uživatelem).
 *
 * Poměr výšek SÁM O SOBĚ ale nestačí - viz [shouldMerge] dok. komentář (zvýraznění prvního
 * slova, "WH" vs zbytek věty) a [BubbleMergeTest]: tenhle konkrétní testovací blok "b" v
 * sobě slučuje dva reálné řádky ML Kit do jednoho ("YOU ASK ME ANY..."), takže jeho výška
 * v testu vychází 0,0292 - poměr proti "WH" (0,0083) je ~3,5x, tedy klidně nad libovolně
 * rozumnou mezí, přestože jde jen o zvýraznění v JEDNÉ větě. Proto se navíc vyžaduje aspoň
 * [STRUCTURED_FIELD_MIN_LINES] řádků ve skupině - zvýraznění prvního slova je vždy jen
 * DVOUDÍLNÝ vzor (důraz + zbytek věty), kdežto stat-box mívá 3+ oddělených polí. Kombinace
 * obou podmínek je proto mnohem bezpečnější než poměr výšek samotný.
 *
 * Přesná hranice NENÍ změřená na reálném zařízení (na rozdíl od [VERTICAL_GAP_TOLERANCE]) -
 * jen odhad s bezpečnou rezervou nad jediným změřeným datovým bodem (1,30x pro DVOUŘÁDKový
 * případ, který teď navíc podmínka [STRUCTURED_FIELD_MIN_LINES] sama vyloučí). `adb logcat -s
 * StructuredFieldMerge` při běžném čtení ukáže skutečné rozložení a hodnotu bude potřeba
 * podle dat doladit.
 */
internal const val STRUCTURED_FIELD_HEIGHT_RATIO = 1.8f

/** Viz [STRUCTURED_FIELD_HEIGHT_RATIO] - zvýraznění prvního slova je vždy 2dílné, stat-box 3+. */
internal const val STRUCTURED_FIELD_MIN_LINES = 3

/**
 * Spojí OCR řádky, které leží blízko sebe (viz [shouldMerge]) A mezi kterými není vizuální
 * "zeď" (viz [noWallBetween], výchozí hodnota `{ _, _ -> true }` = stará čistě geometrická
 * logika, používaná v testech bez Bitmapy) do jednoho bloku - to bývá jedna bublina s víc
 * řádky. Union-Find nad dvojicovým testem: O(n²), ale n (řádků na stránku) bývá v řádu
 * jednotek až nízkých desítek, takže to není problém výkonu.
 */
internal fun mergeNearbyLines(
    lines: List<RawTextBlock>,
    noWallBetween: (RawTextBlock, RawTextBlock) -> Boolean = { _, _ -> true },
    onStructuredFieldMerge: (heightRatio: Float) -> Unit = {},
): List<RawTextBlock> {
    if (lines.isEmpty()) return emptyList()
    val parent = IntArray(lines.size) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
        return r
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    for (i in lines.indices) {
        for (j in i + 1 until lines.size) {
            if (shouldMerge(lines[i], lines[j]) && noWallBetween(lines[i], lines[j])) union(i, j)
        }
    }

    return lines.indices.groupBy { find(it) }.map { (_, idxs) ->
        val members = idxs.map { lines[it] }
        // Svislé sloupce se řadí ZPRAVA DOLEVA (japonské pořadí čtení), vodorovné řádky
        // shora dolů jako dosud. Bez toho by se text bubliny složil pozpátku a model by
        // dostal větu s prohozenými částmi.
        val vertical = members.all { it.isVertical }
        val group = if (vertical) {
            members.sortedWith(compareByDescending<RawTextBlock> { it.rightF }.thenBy { it.topF })
        } else {
            members.sortedWith(compareBy({ it.topF }, { it.leftF }))
        }
        // Svisle sazenym sloupcum (japonstina) je "vyska" na jinou notu nez sirka - pomer
        // vysek by tam nedaval smysl. Struktura poli se navic tyka jen vodorovneho letteringu
        // (herni UI boxy), takze u sloupcu se poznamka o strukturovanych polich neresi vubec.
        val heights = if (vertical) emptyList() else group.map { it.bottomF - it.topF }
        val minHeight = heights.minOrNull() ?: 0f
        val maxHeight = heights.maxOrNull() ?: 0f
        val isStructuredFields = !vertical && group.size >= STRUCTURED_FIELD_MIN_LINES && minHeight > 0f &&
            (maxHeight / minHeight) >= STRUCTURED_FIELD_HEIGHT_RATIO
        if (isStructuredFields) onStructuredFieldMerge(maxHeight / minHeight)
        val joiner = if (isStructuredFields) "\n" else " "
        RawTextBlock(
            text = group.joinToString(joiner) { it.text },
            leftF = group.minOf { it.leftF },
            topF = group.minOf { it.topF },
            rightF = group.maxOf { it.rightF },
            bottomF = group.maxOf { it.bottomF },
            lineCount = group.size,
            isVertical = vertical,
            // Prumer vysky JEDNOTLIVYCH puvodnich radku (kazdy prvek "lines" je jeste jeden
            // radek z ML Kit, pred timhle slouceni) - NE vyska cele sloucene bubliny. Zaklad
            // pro "nativni" velikost pisma, kterou se render pokusi napodobit - viz
            // [TranslatedBlock.nativeLineHeightF].
            //
            // U svislého sloupce je protějškem výšky řádku jeho ŠÍŘKA: velikost písma určuje
            // to, jak je sloupec široký, kdežto jeho výška říká jen, kolik znaků v něm je.
            // Bez tohohle rozlišení by render u svislé bubliny odvodil velikost písma z délky
            // celého sloupce a nasadil obří text.
            nativeLineHeightF = if (vertical) {
                group.map { it.rightF - it.leftF }.average().toFloat()
            } else {
                group.map { it.bottomF - it.topF }.average().toFloat()
            },
        )
    }
}

private fun colorDistance(a: Int, b: Int): Double {
    val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
    val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
}

/** Průměrná barva prstence bodů kolem bloku (viz [ringSeeds]) - "co je vlastní pozadí tohohle řádku". */
private fun ringColor(source: PixelSource, width: Int, height: Int, block: RawTextBlock): Int {
    val seeds = ringSeeds(block.leftF, block.topF, block.rightF, block.bottomF, width, height)
        .filter { (x, y) -> x in 0 until width && y in 0 until height }
    if (seeds.isEmpty()) return source.colorAt((width / 2).coerceIn(0, width - 1), (height / 2).coerceIn(0, height - 1))
    val colors = seeds.map { (x, y) -> source.colorAt(x, y) }
    val r = colors.sumOf { (it shr 16) and 0xFF } / colors.size
    val g = colors.sumOf { (it shr 8) and 0xFF } / colors.size
    val b = colors.sumOf { it and 0xFF } / colors.size
    return (r shl 16) or (g shl 8) or b
}

/**
 * True, když cesta mezi [a] a [b] (vzorkovaná uprostřed skutečné mezery mezi nimi, mimo dosah
 * samotného textu na obou koncích) protíná barvu, která neodpovídá pozadí ANI jednoho z bloků -
 * to je skutečná hranice (obrys bubliny, jiný barevný box, kus kresby mezi nimi), ne pokračování
 * téže bubliny. Volající (viz [mergeNearbyLines]) tohle bere jako veto proti sloučení, i když
 * geometrie ([shouldMerge]) sloučení jinak dovoluje.
 *
 * Cesta se vede StřED PŘEKRYVU (vodorovného, nebo když ten není, svislého) mezi bloky, NE mezi
 * jejich úplnými středy. U kaskádové/"dvouhrbé" bubliny (dva odstavce, druhý vykreslený vodorovně
 * posunutý oproti prvnímu - běžné u ručně sázeného komiksového letteringu) úsečka mezi ÚPLNÝMI
 * středy jde šikmo a u úzkého hrdla mezi výdutěmi snadno mine skutečnou bílou výplň a narazí na
 * pozadí vedle ní - vyhodnoceno jako "zeď", i když jde o jednu bublinu (viz uživatelská zpětná
 * vazba - bublina "IF I'D KNOWN...IN THE FIRST PLACE." se v překladu objevila jen jako
 * "THE FIRST PLACE.", protože se takhle rozdělila na dvě). Střed PŘEKRYVU je tam, kde spojující
 * výplň nejspíš leží, ať jsou bloky sesazené sebevíc stranou.
 */
fun hasWallBetween(
    source: PixelSource,
    width: Int,
    height: Int,
    a: RawTextBlock,
    b: RawTextBlock,
    colorDistanceThreshold: Int = 40,
): Boolean {
    if (width <= 0 || height <= 0) return false

    val aColor = ringColor(source, width, height, a)
    val bColor = ringColor(source, width, height, b)

    val hOverlapLeft = maxOf(a.leftF, b.leftF)
    val hOverlapRight = minOf(a.rightF, b.rightF)
    val vOverlapTop = maxOf(a.topF, b.topF)
    val vOverlapBottom = minOf(a.bottomF, b.bottomF)

    val (p1x, p1y, p2x, p2y) = if (hOverlapRight > hOverlapLeft) {
        // Svislá mezera (typicky dva řádky pod sebou) - veď úsečku středem VODOROVNÉHO
        // překryvu, ne středy celých bloků, aby zůstala uvnitř spojující výplně i u
        // vodorovně posunutých odstavců.
        val midX = (hOverlapLeft + hOverlapRight) / 2f * width
        val (top, bottom) = if (a.topF <= b.topF) a to b else b to a
        listOf(midX, top.bottomF * height, midX, bottom.topF * height)
    } else if (vOverlapBottom > vOverlapTop) {
        // Vodorovná mezera (bloky vedle sebe) - analogicky středem svislého překryvu.
        val midY = (vOverlapTop + vOverlapBottom) / 2f * height
        val (left, right) = if (a.leftF <= b.leftF) a to b else b to a
        listOf(left.rightF * width, midY, right.leftF * width, midY)
    } else {
        // Bez jakéhokoli překryvu (diagonální sousedství) - vzácný okraj shouldMerge,
        // kde nejde spolehlivě určit "pás" mezery; spadni na středy celých bloků jako dřív.
        listOf(
            (a.leftF + a.rightF) / 2f * width,
            (a.topF + a.bottomF) / 2f * height,
            (b.leftF + b.rightF) / 2f * width,
            (b.topF + b.bottomF) / 2f * height,
        )
    }

    // Jen prostřední úsek úsečky (t=0.3..0.7) - blízko konců bychom snadno vzorkovali
    // ještě uvnitř samotného textu jednoho z bloků, ne skutečnou mezeru mezi nimi.
    val gapFractions = listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
    var wallHits = 0
    for (t in gapFractions) {
        val x = (p1x + (p2x - p1x) * t).toInt().coerceIn(0, width - 1)
        val y = (p1y + (p2y - p1y) * t).toInt().coerceIn(0, height - 1)
        val c = source.colorAt(x, y)
        val distA = colorDistance(c, aColor)
        val distB = colorDistance(c, bColor)
        if (distA >= colorDistanceThreshold && distB >= colorDistanceThreshold) wallHits++
    }
    // Skutečná zeď (obrys, jiný box) zabírá souvislý úsek cesty - VĚTŠINA vzorků na ni
    // narazí. Tenký/diagonální vodoznak nastříknutý přes bublinu (viz uživatelská zpětná
    // vazba - "VORTEXSCANS.COM" ležící mezi dvěma půlkami jedné bubliny) protne přímou
    // úsečku typicky jen v 1-2 bodech z 5 - jediný zásah proto nesmí stačit na verdikt "zeď".
    return wallHits > gapFractions.size / 2
}

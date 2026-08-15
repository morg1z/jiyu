package com.haise.jiyu.translate

/** Jeden zalomený řádek textu při konkrétní velikosti písma/šířce - viz [fitFontSizeToBox]. */
data class LineMetrics(val widthPx: Float, val topPx: Float, val bottomPx: Float)

/**
 * Výsledek měření textu při dané velikosti písma a maximální šířce.
 *
 * [longestWordWidthPx] je šířka NEJDELŠÍHO jednotlivého slova (nezalomitelného celku) - bez
 * něj nešlo poznat rozdíl mezi "text se hezky zalomil" a "Compose musel slovo rozsekat
 * uprostřed po písmenech". Oba případy totiž vypadají v [lines] stejně (všechny řádky se
 * vejdou do limitu), protože Compose při zalamování slovo prostě rozřízne - viz
 * [fitFontSizeToBox] a uživatelská zpětná vazba ("KDYBYCH" vykreslené jako "KDYB"/"YCH").
 */
data class TextMeasurement(
    val totalHeightPx: Float,
    val lines: List<LineMetrics>,
    val longestWordWidthPx: Float = 0f,
)

/** Vybraná velikost písma + šířka, na kterou se text má zalomit (viz [fitFontSizeToBox]). */
data class ShapeFitResult(val fontSp: Float, val widthPx: Float)

/**
 * Obdélník (normalizované 0..1 souřadnice stránky) vepsaný do tvaru bubliny - viz
 * [largestInscribedRect].
 */
data class InscribedRect(val leftF: Float, val topF: Float, val rightF: Float, val bottomF: Float) {
    val widthF: Float get() = rightF - leftF
    val heightF: Float get() = bottomF - topF
}

/**
 * Lineárně interpolované levý/pravý okraj obrysu bubliny v konkrétní výšce [yF] (normalizované
 * 0..1 souřadnice stránky) - viz [BubbleShapeDetector]. Body jsou seřazené odshora dolů
 * (rostoucí yF), mimo rozsah se hodnota přichytí na krajní bod.
 */
internal fun shapeBoundsAtYF(shape: List<BubbleShapePoint>, yF: Float): Pair<Float, Float> {
    if (shape.isEmpty()) return 0f to 1f
    if (shape.size == 1) return shape[0].leftF to shape[0].rightF

    val clamped = yF.coerceIn(shape.first().yF, shape.last().yF)
    var i = 0
    while (i < shape.size - 2 && shape[i + 1].yF < clamped) i++
    val a = shape[i]
    val b = shape[i + 1]
    val span = (b.yF - a.yF)
    if (span <= 0.0001f) return a.leftF to a.rightF
    val t = (clamped - a.yF) / span
    val left = a.leftF + (b.leftF - a.leftF) * t
    val right = a.rightF + (b.rightF - a.rightF) * t
    return left to right
}

/** Šířka obrysu bubliny v konkrétní výšce [yF] - viz [shapeBoundsAtYF]. */
fun shapeWidthAtYF(shape: List<BubbleShapePoint>, yF: Float): Float {
    val (left, right) = shapeBoundsAtYF(shape, yF)
    return (right - left).coerceAtLeast(0.0001f)
}

/**
 * Největší osově zarovnaný obdélník, který se CELÝ vejde dovnitř obrysu bubliny.
 *
 * Tohle nahrazuje dřívější (a opakovaně rozbitý) přístup "zalamuj text podle siluety bubliny
 * řádek po řádku". Ten byl principiálně křehký: šířka se počítala zvlášť pro každý řádek,
 * takže stačilo, aby jeden řádek spadl do užšího místa, a zúžení pak rozbilo zalomení všech
 * ostatních; a u složených tvarů (dvojkruhová bublina) navíc text potřeboval i jiný vodorovný
 * střed pro každý řádek, což vedlo na vykreslování řádek-po-řádku a to zase na překrývající se
 * řádky (viz uživatelská zpětná vazba - "OBCHODNÍ"/"STEZKA" přes sebe).
 *
 * Vepsaný obdélník je to, co dělá skutečná sazba: najdi bezpečnou plochu uvnitř tvaru a vysázej
 * text do ní jako jeden normální blok. Text pak nikdy nemůže zasáhnout obrys, řádkování řeší
 * Compose sám (žádné překryvy) a velikost písma vychází z plochy, takže je napříč bublinami
 * konzistentní.
 *
 * O(n²) přes vzorky obrysu (n = 24, viz [BubbleShapeDetector.SAMPLE_COUNT]) - pro každý pár
 * řádků (i, j) je vepsaná šířka dána nejtěsnějším levým/pravým okrajem v tom rozsahu.
 * Vrací null, když tvar nedává použitelný obdélník (prázdný/degenerovaný).
 */
fun largestInscribedRect(shape: List<BubbleShapePoint>): InscribedRect? {
    if (shape.size < 2) return null

    var best: InscribedRect? = null
    var bestArea = 0f

    for (i in shape.indices) {
        var maxLeft = shape[i].leftF
        var minRight = shape[i].rightF
        for (j in i until shape.size) {
            maxLeft = maxOf(maxLeft, shape[j].leftF)
            minRight = minOf(minRight, shape[j].rightF)
            val width = minRight - maxLeft
            if (width <= 0f) break // dál už se rozsah jen zužuje, nemá smysl pokračovat

            val height = shape[j].yF - shape[i].yF
            if (height <= 0f) continue

            val area = width * height
            if (area > bestArea) {
                bestArea = area
                best = InscribedRect(leftF = maxLeft, topF = shape[i].yF, rightF = minRight, bottomF = shape[j].yF)
            }
        }
    }
    return best
}

/**
 * Nejmenší velikost písma, pod kterou sazba překladu nikdy nesmí klesnout.
 *
 * Je to podlaha POSLEDNÍ ZÁCHRANY, ne běžná velikost - fitter vždycky vybírá největší písmo,
 * které se do bubliny vejde, takže sem dosáhne jen text, co se jinam nevejde vůbec.
 */
const val ABSOLUTE_MIN_FONT_SP = 4f

/**
 * Dolní mez velikosti písma pro sazbu překladu, odvozená z uživatelova nastavení velikosti
 * textu ve čtečce (0,7-1,6).
 *
 * ## Proč se podlaha NIKDY nezvedá nahoru
 * Dřív se počítala prostě jako `6 * textScale`, takže při nastavení 1,6 vycházela na 9,6 sp.
 * Do malé bubliny (drobná myšlenková bublinka, popisek v rohu panelu) se takové písmo nevejde
 * ani teoreticky - jenže fitter nemá kam ustoupit, vrátí podlahu, a přebytek pak OŘÍZNE obrys
 * bubliny při vykreslení (`Modifier.clip` v TranslationLayer). Výsledek: text z malých bublin
 * mizí, a mizí tím víc, čím větší písmo si uživatel nastavil. Přesně opačně, než co si přál.
 *
 * Nastavení velikosti textu má tedy vliv jen na strop (`36 * textScale`) a na preferovanou
 * velikost. Podlaha se smí posunout jen DOLŮ, u kdo si písmo zmenšuje - tam je menší text
 * vyslovené přání, ne nouzové řešení.
 *
 * ## Proč radši drobné písmo než ořezaný text
 * Nahlášeno uživatelem se snímkem: "když je ten text moc velký a nevleze se do bubliny, tak ho
 * udelej proste menší". Nečitelně malý text jde pořád klepnutím přepnout na originál a dlouhým
 * stiskem ručně opravit; z chybějícího textu se uživatel nedozví ani to, že tam něco bylo.
 */
fun minTranslationFontSp(textScale: Float): Float =
    (ABSOLUTE_MIN_FONT_SP * textScale).coerceAtMost(ABSOLUTE_MIN_FONT_SP)

/** Hrubý krok prvního sestupu z [ShapeFitResult] hledání (viz [fitFontSizeToBox]). */
private const val COARSE_STEP_SP = 2f

/** Jemný krok doladění kolem hrubě nalezené hranice. */
private const val FINE_STEP_SP = 0.25f

/**
 * Najde největší velikost písma (mezi [minFontSp] a [maxFontSp]), při které se text vejde do
 * zadaného obdélníku ([boxWidthPx] x [maxHeightPx]) - A ZÁROVEŇ se do šířky vejde i nejdelší
 * jednotlivé slovo.
 *
 * Ta druhá podmínka je to podstatné a dřív úplně chyběla: Compose při zalamování slovo, které
 * se nevejde, prostě rozsekne uprostřed po písmenech ("KDYBYCH" -> "KDYB"/"YCH"). Vzniklé
 * řádky pak samozřejmě VŠECHNY splňují šířkový limit, takže starý fitter takové zmrzačené
 * rozvržení vyhodnotil jako úspěch a ještě se ho snažil "vylepšit" zvětšením písma. Kontrola
 * [TextMeasurement.longestWordWidthPx] tenhle celý druh selhání odřízne u kořene - fitter
 * musí zvolit menší písmo, dokud se nejdelší slovo nevejde vcelku.
 *
 * Hledání je dvoufázové (hrubý krok [COARSE_STEP_SP], pak jemné doladění [FINE_STEP_SP]) -
 * lineární krok od velkého stropu dolů by u každé bubliny znamenal desítky měření navíc.
 *
 * @param preferredFontSp velikost, jakou mělo písmo v ORIGINÁLU (odvozená z výšky OCR řádku,
 *   viz [TranslatedBlock.nativeLineHeightF]) - když je zadaná, hledání ji použije jako strop
 *   místo [maxFontSp]: zkusí ji jako první volbu (překlad pak vizuálně sedí na originální
 *   lettering, ne na uměle nafouknuté maximum, co se do bubliny vejde) a teprve když se
 *   nevejde, zmenšuje - ale NIKDY nezvětší nad tuhle hodnotu, i kdyby v bublině zbylo místo.
 *   Null = dřívější chování (hledej rovnou největší velikost, co se vejde).
 * @param onCapProbe pozorovací hák: zavolá se právě jednou, KDYŽ výsledná velikost narazila
 *   přesně na [preferredFontSp] (odhad z [estimateNativeFontPx] byl skutečný limitující
 *   faktor, ne obecné [maxFontSp] ani nedostatek místa) - s jedním levným extra měřením
 *   (`fits(strop + krok)`), jestli by se vešla i o krok větší velikost. `roomToGrow = true`
 *   znamená, že odhad nechal na stole nevyužité místo - sbírá se, aby šlo časem posoudit,
 *   jestli se [estimateNativeFontPx] má ladit výš, místo dalšího odhadu. Nic v návratové
 *   hodnotě nemění, čistě observabilita.
 */
fun fitFontSizeToBox(
    minFontSp: Float,
    maxFontSp: Float,
    boxWidthPx: Float,
    maxHeightPx: Float,
    measure: (fontSp: Float, maxWidthPx: Float) -> TextMeasurement,
    preferredFontSp: Float? = null,
    onCapProbe: (preferredFontSp: Float, roomToGrow: Boolean) -> Unit = { _, _ -> },
): ShapeFitResult {
    fun fits(fontSp: Float): Boolean {
        val measured = measure(fontSp, boxWidthPx)
        if (measured.totalHeightPx > maxHeightPx) return false
        // Rezerva 0.5px na zaokrouhlení mezi měřením a skutečným vykreslením.
        if (measured.longestWordWidthPx > boxWidthPx + 0.5f) return false
        return true
    }

    val searchCeiling = preferredFontSp?.coerceIn(minFontSp, maxFontSp) ?: maxFontSp

    var coarse = searchCeiling
    while (coarse > minFontSp && !fits(coarse)) {
        coarse -= COARSE_STEP_SP
    }
    if (!fits(coarse)) return ShapeFitResult(minFontSp, boxWidthPx)

    var fine = coarse
    while (fine + FINE_STEP_SP <= searchCeiling && fits(fine + FINE_STEP_SP)) {
        fine += FINE_STEP_SP
    }
    val chosen = fine.coerceIn(minFontSp, maxFontSp)

    // preferredFontSp byl skutecny limitujici faktor, jen kdyz je PRISNEJSI nez maxFontSp -
    // jinak dosazeni stropu nic nerika o tom, jestli odhad nechal misto na stole.
    if (preferredFontSp != null && searchCeiling < maxFontSp && chosen >= searchCeiling) {
        val probeSp = searchCeiling + FINE_STEP_SP
        val roomToGrow = probeSp <= maxFontSp && fits(probeSp)
        onCapProbe(searchCeiling, roomToGrow)
    }

    return ShapeFitResult(chosen, boxWidthPx)
}

package com.haise.jiyu.translate

/**
 * Vyvážené lámání textu do řádků + odvození povolené šířky každého řádku z tvaru bubliny.
 *
 * Proč vůbec: Compose (a jakékoli "hladové" zalamování) cpe do každého řádku maximum slov a
 * zbytek posune dolů. V bublině pak vznikají rozvržení typu "4 slova / 1 slovo", která na první
 * pohled prozradí strojový překlad. Profesionální komiksový lettering dělá blok textu
 * kosočtvercový - krátký první řádek, delší uprostřed, krátký poslední - aby kopíroval kulatý
 * tvar bubliny.
 *
 * Tenhle soubor obsahuje obě části: [shapeLineWidths] (kolik místa má který řádek podle tvaru)
 * a [breakIntoLines] (jak slova mezi řádky rozdělit, aby se šířky co nejvíc blížily povoleným).
 */

/** Nekonečná cena - řádek, do kterého se přiřazená slova nevejdou (viz [breakIntoLines]). */
private const val INFEASIBLE = Float.MAX_VALUE

/**
 * Rozdělí slova do PŘESNĚ [allowedWidths].size řádků tak, aby součet čtverců nevyužitého místa
 * na řádcích byl nejmenší (klasická "minimum raggedness" dynamika, jen s jinou povolenou šířkou
 * pro každý řádek).
 *
 * Čtverec (ne prostý rozdíl) je tu podstatný - trestá jeden hodně krátký řádek mnohem víc než
 * několik mírně kratších, což je přesně ten rozdíl mezi "4 slova / 1 slovo" a rovnoměrným blokem.
 *
 * @param wordWidths šířky jednotlivých slov (v px) při aktuální velikosti písma
 * @param spaceWidth šířka mezery mezi slovy
 * @param allowedWidths povolená šířka pro každý řádek - viz [shapeLineWidths]
 * @return indexy KONCŮ řádků (exkluzivně) - např. [3, 7] znamená slova 0..2 na prvním řádku
 *   a 3..6 na druhém. Null, když rozdělení do tohoto počtu řádků není možné (nějaké slovo se
 *   nevejde ani samo na svůj řádek) - volající pak zkusí víc řádků nebo menší písmo.
 */
fun breakIntoLines(
    wordWidths: List<Float>,
    spaceWidth: Float,
    allowedWidths: List<Float>,
): List<Int>? {
    val wordCount = wordWidths.size
    val lineCount = allowedWidths.size
    if (wordCount == 0 || lineCount == 0 || lineCount > wordCount) return null

    /** Šířka slov [from, to) položených na jeden řádek (včetně mezer mezi nimi). */
    fun lineWidth(from: Int, to: Int): Float {
        var w = 0f
        for (i in from until to) {
            w += wordWidths[i]
            if (i > from) w += spaceWidth
        }
        return w
    }

    // dp[i][j] = nejmenší cena rozmístění prvních j slov do prvních i řádků
    val dp = Array(lineCount + 1) { FloatArray(wordCount + 1) { INFEASIBLE } }
    val back = Array(lineCount + 1) { IntArray(wordCount + 1) { -1 } }
    dp[0][0] = 0f

    for (line in 1..lineCount) {
        val allowed = allowedWidths[line - 1]
        for (j in line..wordCount) {
            // Každý řádek musí dostat aspoň jedno slovo, proto k >= line-1.
            for (k in (line - 1) until j) {
                val prev = dp[line - 1][k]
                if (prev >= INFEASIBLE) continue
                val width = lineWidth(k, j)
                if (width > allowed) continue // slovo/slova se na tenhle řádek nevejdou
                val slack = allowed - width
                val cost = prev + slack * slack
                if (cost < dp[line][j]) {
                    dp[line][j] = cost
                    back[line][j] = k
                }
            }
        }
    }

    if (dp[lineCount][wordCount] >= INFEASIBLE) return null

    val ends = ArrayDeque<Int>()
    var j = wordCount
    for (line in lineCount downTo 1) {
        ends.addFirst(j)
        j = back[line][j]
        if (j < 0) return null
    }
    return ends.toList()
}

/**
 * Hotová sazba textu do bubliny - viz [fitTextToShape].
 *
 * [centerYF] je výška, na kterou sazba blok skutečně vycentrovala (po zarážce do obrysu).
 * Vykreslení se musí řídit tímhle číslem, ne tím, o co se žádalo: šířky řádků jsou spočítané
 * z tvaru přesně v tomhle pásu, takže vykreslit blok jinde by znamenalo sázet podle jiného
 * místa, než kam text nakonec půjde - a u zúžení obrysu by přetekl přes okraj bubliny.
 */
data class ShapedTextLayout(val fontSp: Float, val lines: List<String>, val centerYF: Float)

/** Hrubý krok hledání velikosti písma (viz [fitTextToShape]). */
private const val SHAPED_COARSE_STEP_SP = 2f

/** Jemný krok doladění kolem hrubě nalezené hranice. */
private const val SHAPED_FINE_STEP_SP = 0.25f

/**
 * Najde největší velikost písma, při které se [words] dají vyváženě rozsázet do tvaru bubliny -
 * a rovnou vrátí i hotové rozdělení na řádky.
 *
 * Pro každou zkoušenou velikost se projdou počty řádků od nejmenšího (nejmíň řádků = největší
 * text) a hledá se první, do kterého se text vejde: blok se svisle vycentruje v bublině, každý
 * řádek dostane šířku podle tvaru ve svém pásu ([shapeLineWidths]) a slova se mezi řádky
 * rozdělí vyváženě ([breakIntoLines]). Když se nevejde do žádného počtu řádků, velikost se
 * zmenší.
 *
 * Protože [breakIntoLines] vrátí null, kdykoli se jediné slovo nevejde na svůj řádek, nemůže
 * tudy projít rozvržení, ve kterém by se slovo muselo rozseknout uprostřed - to byla hlavní
 * příčina dřívějších "KDYB/YCH" chyb.
 *
 * @param maxLineWidthPx tvrdý strop šířky řádku daný tím, kolik místa dostane SKUTEČNÝ `Text`
 *   composable při vykreslení (šířka boxu bubliny minus jeho padding). Bez něj se sázelo jen
 *   podle geometrie obrysu, jenže obrys bubliny bývá širší než box, do kterého se text nakonec
 *   vykreslí - typicky u hranatého popiskového rámečku, kde tvar pokrývá celý šedý obdélník,
 *   ale box kopíruje jen užší OCR rozsah textu. Sazba pak prošla kontrolou ("slovo se do řádku
 *   vejde"), ale Compose měl při vykreslení k dispozici míň místa a slovo rozsekal uprostřed
 *   po písmenech - viz uživatelský screenshot "SPOLEČNOS" + "T" na dalším řádku.
 * @param preferredFontSp velikost, jakou mělo písmo v ORIGINÁLU (viz [TranslatedBlock.nativeLineHeightF])
 *   - když je zadaná, hledání ji použije jako strop místo [maxFontSp]: zkusí ji jako první
 *   volbu a teprve když se text nevejde, zmenšuje, ale nikdy nezvětší nad tuhle hodnotu.
 *   Null = dřívější chování (hledej rovnou největší velikost, co se vejde).
 * @param centerYF výška, na kterou se má blok textu svisle vycentrovat. Null = střed obalového
 *   obdélníku tvaru (dřívější chování).
 *
 *   Proč to nestačilo: obrys z flood-fillu obsahuje i OCÁSEK bubliny - ten úzký výběžek
 *   ukazující na mluvčího - a u kaskádové bubliny navíc kus nad/pod vlastním lalokem. Obalový
 *   obdélník je pak o dost vyšší než plocha, kde text doopravdy je, a jeho střed leží mimo ni.
 *   Text se tak odtáhne od středu bubliny směrem k ocásku. Změřeno na nahlášené stránce
 *   (1440x3120): horní lalok měl tvar y=0.488..0.645 (střed 0,567), ale vepsaný obdélník
 *   y=0.559..0.645 (střed 0,602) a originální text y=0.572..0.627 (střed 0,600) - sazba tedy
 *   mířila o 0,033 výšky stránky výš, než kde text v originále byl. Střed vepsaného obdélníku
 *   sedí na originál na tři tisíciny, střed obalového obdélníku se mýlí desetkrát víc.
 *
 *   Posun se musí promítnout UŽ SEM, ne až do vykreslení: šířky řádků se odvozují z tvaru
 *   v tom pásu, kde blok leží (viz [shapeLineWidths]), takže posunout hotovou sazbu dodatečně
 *   by znamenalo sázet podle jiného místa, než kam se text nakonec vykreslí.
 * @return null, když se text nevejde ani při [minFontSp] - volající pak spadne na jednodušší
 *   sazbu do vepsaného obdélníku.
 * @param onCapProbe pozorovací hák: zavolá se právě jednou, KDYŽ výsledná velikost narazila
 *   přesně na [preferredFontSp] (odhad z `estimateNativeFontPx` byl skutečný limitující faktor,
 *   ne obecné [maxFontSp]) - s jedním levným extra pokusem o krok větší velikost, jestli by se
 *   vešla i ta. `roomToGrow = true` znamená, že odhad nechal na stole nevyužité místo - viz
 *   stejný hák u [fitFontSizeToBox]. Nic v návratové hodnotě nemění, čistě observabilita.
 */
fun fitTextToShape(
    words: List<String>,
    minFontSp: Float,
    maxFontSp: Float,
    shape: List<BubbleShapePoint>,
    centerF: Float,
    shapeTopF: Float,
    shapeBottomF: Float,
    pageWidthPx: Float,
    pageHeightPx: Float,
    measureWord: (word: String, fontSp: Float) -> Float,
    spaceWidth: (fontSp: Float) -> Float,
    lineHeightPx: (fontSp: Float) -> Float,
    maxLines: Int = 12,
    maxLineWidthPx: Float = Float.MAX_VALUE,
    preferredFontSp: Float? = null,
    centerYF: Float? = null,
    onCapProbe: (preferredFontSp: Float, roomToGrow: Boolean) -> Unit = { _, _ -> },
): ShapedTextLayout? {
    if (words.isEmpty() || shape.size < 2 || pageHeightPx <= 0f) return null
    val shapeHeightF = shapeBottomF - shapeTopF
    if (shapeHeightF <= 0f) return null
    val searchCeiling = preferredFontSp?.coerceIn(minFontSp, maxFontSp) ?: maxFontSp

    /** Hotové řádky + výška, na kterou se blok vycentroval (viz [ShapedTextLayout.centerYF]). */
    fun attempt(fontSp: Float): Pair<List<String>, Float>? {
        val wordWidths = words.map { measureWord(it, fontSp) }
        val space = spaceWidth(fontSp)
        val lineHeight = lineHeightPx(fontSp)
        if (lineHeight <= 0f) return null

        val maxLineCount = minOf(maxLines, words.size)
        for (lineCount in 1..maxLineCount) {
            val blockHeightF = (lineCount * lineHeight) / pageHeightPx
            if (blockHeightF > shapeHeightF) break // víc řádků se do bubliny svisle nevejde

            // Blok se centruje na [centerYF] (těžiště SKUTEČNÉ textové plochy), ne na střed
            // obalového obdélníku tvaru - viz doc komentář parametru. Zarážka drží blok celý
            // uvnitř obrysu i pro střed, který by ho jinak vytlačil ven.
            val blockTopF = centerYF
                ?.let { (it - blockHeightF / 2f).coerceIn(shapeTopF, shapeBottomF - blockHeightF) }
                ?: (shapeTopF + (shapeHeightF - blockHeightF) / 2f)
            val allowed = shapeLineWidths(
                shape = shape,
                centerF = centerF,
                blockTopF = blockTopF,
                blockBottomF = blockTopF + blockHeightF,
                lineCount = lineCount,
                pageWidthPx = pageWidthPx,
            ).map { it.coerceAtMost(maxLineWidthPx) } // viz [maxLineWidthPx]
            val ends = breakIntoLines(wordWidths, space, allowed) ?: continue
            return assembleLines(words, ends) to (blockTopF + blockHeightF / 2f)
        }
        return null
    }

    var coarse = searchCeiling
    var result = attempt(coarse)
    while (result == null && coarse - SHAPED_COARSE_STEP_SP >= minFontSp) {
        coarse -= SHAPED_COARSE_STEP_SP
        result = attempt(coarse)
    }
    var best = result ?: return null

    var fine = coarse
    while (fine + SHAPED_FINE_STEP_SP <= searchCeiling) {
        val next = attempt(fine + SHAPED_FINE_STEP_SP) ?: break
        fine += SHAPED_FINE_STEP_SP
        best = next
    }

    // preferredFontSp byl skutecny limitujici faktor, jen kdyz je PRISNEJSI nez maxFontSp -
    // jinak dosazeni stropu nic nerika o tom, jestli odhad nechal misto na stole (viz stejna
    // uvaha u fitFontSizeToBox).
    if (preferredFontSp != null && searchCeiling < maxFontSp && fine >= searchCeiling) {
        val probeSp = searchCeiling + SHAPED_FINE_STEP_SP
        val roomToGrow = probeSp <= maxFontSp && attempt(probeSp) != null
        onCapProbe(searchCeiling, roomToGrow)
    }

    return ShapedTextLayout(fontSp = fine, lines = best.first, centerYF = best.second)
}

/** Pomocník - z indexů konců řádků (viz [breakIntoLines]) složí skutečné řádky textu. */
fun assembleLines(words: List<String>, lineEnds: List<Int>): List<String> {
    val lines = mutableListOf<String>()
    var start = 0
    for (end in lineEnds) {
        lines += words.subList(start, end).joinToString(" ")
        start = end
    }
    return lines
}

/**
 * Povolená šířka pro každý z [lineCount] řádků textového bloku, odvozená ze skutečného tvaru
 * bubliny.
 *
 * Blok textu je svisle vycentrovaný v bublině (rozsah [blockTopF]..[blockBottomF]) a KAŽDÝ
 * řádek dostane šířku podle nejužšího místa tvaru ve SVÉM vodorovném pásu. V oválné bublině
 * tak prostřední řádky dostanou víc místa než krajní - a [breakIntoLines] z toho udělá ten
 * kosočtvercový blok, který dělá skutečný lettering.
 *
 * Šířka se měří SYMETRICKY kolem pevné osy [centerF]: `2 * min(centerF - left, right - centerF)`.
 * Díky tomu můžou být všechny řádky vycentrované na jednu osu (jeden obyčejný `Text` se
 * `TextAlign.Center`) a přesto je zaručeno, že žádný nepřeteče obrys. Dřívější pokus dávat
 * každému řádku vlastní vodorovný střed vedl na vykreslování řádek po řádku a to zase na
 * překrývající se řádky - tomuhle se tím vyhneme.
 *
 * @param samplesPerLine kolik bodů se v pásu jednoho řádku vzorkuje (min přes ně)
 */
fun shapeLineWidths(
    shape: List<BubbleShapePoint>,
    centerF: Float,
    blockTopF: Float,
    blockBottomF: Float,
    lineCount: Int,
    pageWidthPx: Float,
    samplesPerLine: Int = 3,
): List<Float> {
    if (lineCount <= 0) return emptyList()
    val blockHeight = blockBottomF - blockTopF
    if (blockHeight <= 0f) return List(lineCount) { 0f }

    val lineHeight = blockHeight / lineCount
    return (0 until lineCount).map { i ->
        val bandTop = blockTopF + i * lineHeight
        var halfWidth = Float.MAX_VALUE
        for (s in 0 until samplesPerLine) {
            val t = if (samplesPerLine == 1) 0.5f else s / (samplesPerLine - 1).toFloat()
            val yF = bandTop + lineHeight * t
            val (left, right) = shapeBoundsAtYF(shape, yF)
            halfWidth = minOf(halfWidth, centerF - left, right - centerF)
        }
        (2f * halfWidth * pageWidthPx).coerceAtLeast(0f)
    }
}

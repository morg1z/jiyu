package com.haise.jiyu.translate

/**
 * Detektor "seznamových"/hustě popsaných stránek - obsah kapitoly (TOC), titulní tabule
 * s mřížkou drobných nápisů, poděkování, reklamní karty s paragrafy.
 *
 * ## Proč existuje
 * Audit Vagabondu (ch1 s001-s002): TOC stránka s obloukově rozloženými položkami obsahu
 * vyprodukovala ~10+ drobných OCR bloků bez skutečných bublin. Model dostal samotný šum
 * ("Chapl"er 5", "Trouhly"), přeložil ho do gibberishe a placeholder tokenů ("__g8__")
 * a overlay pak překryl původní sazbu šedými patche. Výsledek byl horší než žádný překlad.
 *
 * Taková stránka má jiný ekonomický model než dialog: překlad desítek fragmentů je drahý,
 * šanci na katastrofu zvyšuje hustota malých boxů (patch nad artem nikdy nesplyne) a
 * čtenář stejně potřebuje především příběh - TOC je okrajový obsah. Proto ji pipeline
 * ponechá v originále (preserve), ne že by se pokoušela překládat šum.
 *
 * ## Rozhodovací signály
 * Počítá se jen obsahový text (SFX se vynechávají - akční stránka posetá zvuky není
 * seznam). Stránka je "dense" tehdy, když současně:
 *  - má aspoň [DENSE_MIN_BLOCKS] obsahových bloků (obyčejná dialogová stránka má 3-7),
 *  - mediánová délka textu je krátká (položky seznamu, ne věty),
 *  - většina bloků je VYSOKÁ MÉNĚ NEŽ ~3.5 % stránky (drobný lettering),
 *  - detekce tvarů našla skoro nic (seznam nemá nakreslené balónky - když ano, je to
 *    spíš nakreslená tabulka, kde se překlad vyplatí nechat),
 *  - bloky se táhnou přes značnou část stránky alespoň v jedné ose (seznam pokrývá
 *    stránku, shluk textu uprostřed prázdné kresby ji nepokrývá).
 *
 * Všechny hranice jsou záměrně konzervativní - chybně označit dialogovou stránku za
 * seznam by znamenalo zahodit překlad, což je horší než občas překládat skutečný seznam.
 */
internal fun isDenseTextPage(classified: List<ClassifiedBubble>): Boolean {
    // Bloky bez písmen (interpunkce, šum) se nepočítají - nejsou obsah seznamu.
    val content = classified.filter { !it.isSfx && it.raw.text.count(Char::isLetter) >= 2 }
    if (content.size < DENSE_MIN_BLOCKS) return false

    val lengths = content.map { it.raw.text.trim().length }.sorted()
    if (lengths[lengths.size / 2] > DENSE_MAX_MEDIAN_CHARS) return false

    val shortShare = content.count { (it.raw.bottomF - it.raw.topF) < DENSE_SHORT_HEIGHT_F }
        .toFloat() / content.size
    if (shortShare < DENSE_MIN_SHORT_SHARE) return false

    // Spolehlivá evidence nakreslených bublin znamená: je to dialogová stránka, ne seznam.
    // Flood-fill/segmentační tvar se u dialogu typicky najde aspoň u části bublin.
    val shapedShare = content.count { it.raw.shape != null }.toFloat() / content.size
    if (shapedShare > DENSE_MAX_SHAPED_SHARE) return false

    val centerXs = content.map { (it.raw.leftF + it.raw.rightF) / 2f }
    val centerYs = content.map { (it.raw.topF + it.raw.bottomF) / 2f }
    val xSpan = centerXs.max() - centerXs.min()
    val ySpan = centerYs.max() - centerYs.min()
    return xSpan >= DENSE_MIN_AXIS_SPAN || ySpan >= DENSE_MIN_AXIS_SPAN
}

/** Aspoň tolik obsahových (ne-SFX) bloků musí stránka mít, aby mohla být "seznamem". */
private const val DENSE_MIN_BLOCKS = 8

/** Mediánová délka položky seznamu - položky TOC/obsahu jsou krátké nápisy, ne věty. */
private const val DENSE_MAX_MEDIAN_CHARS = 30

/** Výška "drobného letteringu" jako zlomek výšky stránky. */
private const val DENSE_SHORT_HEIGHT_F = 0.035f

/** Podíl krátkých bloků potřebný pro dense rozhodnutí. */
private const val DENSE_MIN_SHORT_SHARE = 0.6f

/** Přes tenhle podíl bloků s nalezeným tvarem bubliny jde o dialog, ne seznam. */
private const val DENSE_MAX_SHAPED_SHARE = 0.2f

/** Seznam se táhne přes většinu stránky - alespoň v jedné ose. */
private const val DENSE_MIN_AXIS_SPAN = 0.45f

/**
 * Označí všechny obsahové bloky stránky jako "preserve" - vrátí se jako SFX, takže se
 * nikdy neposílají do modelu ani se přes ně nekreslí overlay (originál zůstane vidět).
 * Sdíleno pro [isDenseTextPage]==true i pro per-block stylizovaný art text.
 */
internal fun ClassifiedBubble.asPreservedBlock(): ClassifiedBubble =
    copy(isSfx = true, sizeTag = SizeTag.SFX, bubbleType = BubbleType.SFX)

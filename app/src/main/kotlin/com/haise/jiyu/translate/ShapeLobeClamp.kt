package com.haise.jiyu.translate

import kotlin.math.max
import kotlin.math.min

/**
 * Ořízne obrys bubliny tak, aby nesahal přes text bubliny sousední.
 *
 * ## Co se dělo
 * Kaskádová replika bývá nakreslená jako dvě PŘEKRÝVAJÍCÍ SE bublinky - a ty tvoří jednu
 * spojitou bílou plochu. Flood-fill, který [BubbleShapeDetector] pouští kolem spodní bubliny,
 * se přes ten pas přelije nahoru a vrátí tvar pokrývající OBA laloky. Výplň se pak natáhne
 * přes obojí, a protože se bubliny kreslí shora dolů (viz [sortIntoReadingOrder]), spodní
 * přemaluje text té horní - včetně textu, který se vůbec nepřeložil.
 *
 * ## Proč se to napoprvé neopravilo
 * První verze ořezávala jen podle sousedů, jejichž OCR BOX se s tím naším vodorovně překrýval
 * aspoň ze čtvrtiny. Jenže u kaskádové bubliny jsou laloky ZÁMĚRNĚ posunuté do stran (horní
 * vpravo, spodní vlevo) - právě to jim dává ten schodovitý tvar - takže se boxy překrývají
 * sotva a podmínka neprošla. Změřeno na zařízení: oba bloky pak dostaly totožný tvar celého
 * balónu, přesně jako bez opravy.
 *
 * Správná otázka nezní "překrývají se boxy", ale "POKRÝVÁ MŮJ TVAR CIZÍ TEXT?". Na to je
 * přesný nástroj: obrys zná svoje levé a pravé okraje v každé výšce (viz [shapeBoundsAtYF]),
 * takže stačí ověřit, jestli střed cizího bloku padne dovnitř.
 *
 * ## Jak se to řeší
 * Tvar se zkrátí na půli cesty mezi vlastní bublinou a tou sousední - dost na to, aby vlastní
 * bublina zůstala celá zakrytá, ale ne tak daleko, aby zasáhla cizí text.
 *
 * Soused může ležet nad/pod (svisle) NEBO vedle (vodorovně, laloky na stejné výšce - jiná
 * varianta stejné kaskádové bubliny). Odlišuje je to, jestli se svisle překrývají: soused nad
 * (`other.bottomF <= own.topF`) nebo pod (`other.topF >= own.bottomF`) se svisle nepřekrývá s
 * vlastní bublinou vůbec, takže se ořízne svisle. Soused, který se svisle PŘEKRÝVÁ (ani jedna
 * podmínka), leží vedle - to je právě ta chybějící větev, kterou první verze neřešila vůbec:
 * smyčka takového souseda jen přeskočila beze změny limitu, takže tvar zůstal roztažený přes
 * oba laloky (nahlášeno - "spojená" bublina se dvěma replikami vedle sebe na stejné výšce
 * skončila v překladu jen s jednou, uprostřed celé spojené plochy).
 *
 * @param own OCR box bubliny, které tenhle obrys patří
 * @param others OCR boxy ostatních bublin na stránce
 */
internal fun clampShapeToOwnLobe(
    shape: List<BubbleShapePoint>,
    own: RawTextBlock,
    others: List<RawTextBlock>,
): List<BubbleShapePoint> {
    if (shape.isEmpty()) return shape

    var upperLimit = 0f
    var lowerLimit = 1f
    var leftLimit = 0f
    var rightLimit = 1f
    for (other in others) {
        if (!shapeCovers(shape, other)) continue
        if (other.bottomF <= own.topF) {
            // Soused nad námi - tvar smí sahat nanejvýš do půli mezery mezi nimi.
            upperLimit = max(upperLimit, (other.bottomF + own.topF) / 2f)
        } else if (other.topF >= own.bottomF) {
            lowerLimit = min(lowerLimit, (own.bottomF + other.topF) / 2f)
        } else {
            // Soused vedle nás (svisle se překrývá, ale není celý nad ani pod) - ořízne se
            // vodorovně, na tu stranu, kde leží (podle středu jeho OCR boxu proti našemu).
            val ownCenterX = (own.leftF + own.rightF) / 2f
            val otherCenterX = (other.leftF + other.rightF) / 2f
            if (otherCenterX >= ownCenterX) {
                rightLimit = min(rightLimit, (own.rightF + other.leftF) / 2f)
            } else {
                leftLimit = max(leftLimit, (own.leftF + other.rightF) / 2f)
            }
        }
    }

    // Vlastní bublina musí zůstat zakrytá za všech okolností - limit ji nikdy nesmí ukrojit.
    upperLimit = min(upperLimit, own.topF)
    lowerLimit = max(lowerLimit, own.bottomF)
    leftLimit = min(leftLimit, own.leftF)
    rightLimit = max(rightLimit, own.rightF)
    if (upperLimit <= 0f && lowerLimit >= 1f && leftLimit <= 0f && rightLimit >= 1f) return shape

    val clamped = shape.mapNotNull { p ->
        if (p.yF !in upperLimit..lowerLimit) return@mapNotNull null
        // coerceAtLeast/coerceAtMost místo prostého filtru jako u yF výš - vodorovný limit
        // platí pro KAŽDÝ přeživší řádek zvlášť (šířka obrysu se mezi řádky mění), ne pro
        // tvar jako celek. Bod, u kterého by to obrátilo levý/pravý okraj (řádek ležící celý
        // za hranicí, ne jen zasahující přes ni), se zahodí - stejná pojistka jako prázdný
        // tvar níž, radši mírně velkorysý obrys než neplatný bod.
        val left = p.leftF.coerceAtLeast(leftLimit)
        val right = p.rightF.coerceAtMost(rightLimit)
        if (left > right) null else p.copy(leftF = left, rightF = right)
    }
    // Prázdný tvar by znamenal, že se bublina vůbec nezakryje a prosvítal by originál pod
    // překladem - to je horší než mírně velkorysý obrys.
    return clamped.ifEmpty { shape }
}

/**
 * Leží střed cizího bloku uvnitř tohohle obrysu? Tedy: přemaloval by mu tvar text?
 *
 * Testuje se střed, ne celý box - u kaskádové bubliny cizí lalok z tvaru kouskem vyčnívá, ale
 * jeho text v něm leží celý.
 */
private fun shapeCovers(shape: List<BubbleShapePoint>, other: RawTextBlock): Boolean {
    val centerY = (other.topF + other.bottomF) / 2f
    if (centerY < shape.first().yF || centerY > shape.last().yF) return false
    val (left, right) = shapeBoundsAtYF(shape, centerY)
    val centerX = (other.leftF + other.rightF) / 2f
    return centerX in left..right
}
